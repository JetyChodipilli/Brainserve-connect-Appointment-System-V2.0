-- Current valid delivery acceptance is separate from final governance closure.
-- Existing submission snapshots and immutable original commitments remain the source.
CREATE VIEW reporting_work_current_acceptance AS
SELECT t.id work_task_id, t.submission_version, min((s->>'acceptedAt')::timestamptz) accepted_at
FROM department_work_task t
CROSS JOIN LATERAL jsonb_array_elements(coalesce(t.planning_state->'submissions','[]'::jsonb)) s
WHERE ((t.assignee_role='EMPLOYEE' AND t.status IN ('APPROVED','ACKNOWLEDGED'))
  OR (t.assignee_role='TEAM_LEAD' AND t.status IN ('COMPLETED','APPROVED','ACKNOWLEDGED')))
  AND pg_input_is_valid(s->>'version','bigint') AND (s->>'version')::bigint=t.submission_version
  AND pg_input_is_valid(s->>'acceptedAt','timestamp with time zone')
  AND pg_input_is_valid(s->>'submittedAt','timestamp with time zone')
  AND (s->>'acceptedAt')::timestamptz >= (s->>'submittedAt')::timestamptz
  AND ((t.assignee_role='EMPLOYEE' AND s->>'acceptedByRole'='TEAM_LEAD')
    OR (t.assignee_role='TEAM_LEAD' AND s->>'acceptedByRole'='HR_ADMIN'))
  AND ((t.assignment_revision=0 AND s->>'assignmentRevision' IS NULL)
    OR (pg_input_is_valid(s->>'assignmentRevision','bigint') AND (s->>'assignmentRevision')::bigint=t.assignment_revision))
GROUP BY t.id,t.submission_version;

-- Actual transition facts from this release forward. There is deliberately no
-- backfill of an entry time for work already in a review queue.
CREATE TABLE work_review_stage_event (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 work_task_id uuid NOT NULL REFERENCES department_work_task(id),
 stage varchar(20) CHECK(stage IN ('TEAM_LEAD','HR_ADMIN','MANAGER','CEO')),
 previous_stage varchar(20) CHECK(previous_stage IN ('TEAM_LEAD','HR_ADMIN','MANAGER','CEO')),
 occurred_at timestamptz NOT NULL,
 assignment_revision bigint NOT NULL,
 submission_version bigint,
 audit_cycle integer NOT NULL DEFAULT 0,
 source varchar(20) NOT NULL CHECK(source IN ('TASK','AUDIT'))
);
CREATE INDEX ix_work_review_stage_task ON work_review_stage_event(work_task_id,id);
CREATE INDEX ix_work_review_stage_time ON work_review_stage_event(occurred_at,id);
CREATE TRIGGER immutable_work_review_stage_event BEFORE UPDATE OR DELETE ON work_review_stage_event
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();

CREATE FUNCTION work_current_review_stage(task_id uuid) RETURNS varchar LANGUAGE sql VOLATILE AS $$
 SELECT CASE
  WHEN t.status='INSIGHT_REWORK_REQUESTED' AND a.audit_status IN ('HR_REWORK_REQUESTED','MANAGER_REWORK_REQUESTED','CEO_REWORK_REQUESTED') THEN 'TEAM_LEAD'
  WHEN t.status IN ('ASSIGNED','IN_PROGRESS','CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED') THEN NULL
  WHEN a.audit_status='PENDING_MANAGER_APPROVAL' THEN 'MANAGER'
  WHEN a.audit_status='PENDING_CEO_APPROVAL' THEN 'CEO'
  WHEN a.audit_status='CEO_APPROVED' THEN NULL
  WHEN t.status='COMPLETED' AND t.assignee_role='EMPLOYEE' THEN 'TEAM_LEAD'
  WHEN t.status='COMPLETED' AND t.assignee_role='TEAM_LEAD' THEN 'HR_ADMIN'
  WHEN t.status IN ('APPROVED','ACKNOWLEDGED') THEN 'HR_ADMIN'
  ELSE NULL END
 FROM department_work_task t LEFT JOIN work_task_audit_record a ON a.work_task_id=t.id WHERE t.id=task_id
$$;
CREATE FUNCTION capture_work_review_stage_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE task_id uuid; next_stage varchar; previous work_review_stage_event%ROWTYPE; task department_work_task%ROWTYPE; cycle integer;
BEGIN
 IF TG_TABLE_NAME='department_work_task' THEN task_id:=NEW.id;
 ELSIF TG_OP='DELETE' THEN task_id:=OLD.work_task_id;
 ELSE task_id:=NEW.work_task_id; END IF;
 SELECT * INTO task FROM department_work_task WHERE id=task_id;
 IF NOT FOUND THEN RETURN NULL; END IF;
 next_stage:=work_current_review_stage(task_id);
 SELECT * INTO previous FROM work_review_stage_event WHERE work_task_id=task_id ORDER BY id DESC LIMIT 1;
 SELECT rework_cycle INTO cycle FROM work_task_audit_record WHERE work_task_id=task_id;
 cycle:=coalesce(cycle,task.rework_cycle);
 IF (previous.id IS NULL AND next_stage IS NOT NULL)
 OR (previous.id IS NOT NULL AND (previous.stage IS DISTINCT FROM next_stage
  OR (next_stage IS NOT NULL AND (previous.assignment_revision IS DISTINCT FROM task.assignment_revision
   OR previous.submission_version IS DISTINCT FROM task.submission_version OR previous.audit_cycle IS DISTINCT FROM cycle)))) THEN
  INSERT INTO work_review_stage_event(work_task_id,stage,previous_stage,occurred_at,assignment_revision,submission_version,audit_cycle,source)
  VALUES(task_id,next_stage,CASE WHEN previous.assignment_revision=task.assignment_revision
     AND (task.status<>'INSIGHT_REWORK_REQUESTED' OR previous.stage=CASE task.insight_review_source WHEN 'HR' THEN 'HR_ADMIN' WHEN 'MANAGER' THEN 'MANAGER' WHEN 'CEO' THEN 'CEO' END)
     AND (previous.submission_version IS NOT DISTINCT FROM task.submission_version AND previous.audit_cycle=cycle
      OR (next_stage IS NULL AND task.status='CHANGES_REQUESTED' AND previous.stage='TEAM_LEAD')
      OR (next_stage IS NULL AND task.status='INSIGHT_REWORK_REQUESTED' AND previous.stage=CASE task.insight_review_source WHEN 'HR' THEN 'HR_ADMIN' WHEN 'MANAGER' THEN 'MANAGER' WHEN 'CEO' THEN 'CEO' END))
     THEN previous.stage ELSE NULL END,
   clock_timestamp(),task.assignment_revision,task.submission_version,cycle,CASE WHEN TG_TABLE_NAME='department_work_task' THEN 'TASK' ELSE 'AUDIT' END);
 END IF;
 RETURN NULL;
END;
$$;
CREATE TRIGGER reporting_work_review_task AFTER INSERT OR UPDATE OF status,submission_version,assignment_revision ON department_work_task
 FOR EACH ROW EXECUTE FUNCTION capture_work_review_stage_event();
CREATE TRIGGER reporting_work_review_audit AFTER INSERT OR UPDATE OF audit_status,rework_cycle ON work_task_audit_record
 FOR EACH ROW EXECUTE FUNCTION capture_work_review_stage_event();
INSERT INTO dashboard_measurement_coverage(id,reason) VALUES
 ('WORK_REVIEW_STAGE_EVENTS','Real stage transitions are captured from V62. Existing pending stages have unknown entry times. Missing entries and invalid intervals are excluded; unresolved counts still include them.');
CREATE INDEX ix_work_analytics_leave ON employee_leave_request(employee_id,start_date,end_date) WHERE status='APPROVED';
CREATE INDEX ix_work_analytics_final_event ON audit_event_history(occurred_at,(details_json->>'workTaskId'))
 WHERE event_type='WORK_INSIGHT_CEO_APPROVED' AND target_type='WORK_TASK_AUDIT' AND outcome='SUCCESS';

CREATE TRIGGER reporting_work_review_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON work_review_stage_event
 FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();

UPDATE dashboard_measurement_coverage SET reason='Current delivery acceptance comes from retained V56 submission versions, accepted by Lead for Employee work or HR for direct Lead work. Unknown legacy submission evidence remains unknown; rework and assignment revisions invalidate current acceptance.' WHERE id='WORK_ACCEPTANCE_HISTORY';

-- Workload availability and scoped authority belong to the same established
-- committed reporting generation; a concurrent lifecycle/leave change forces reload.
CREATE TRIGGER reporting_work_leave_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON employee_leave_request
 FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_work_roles_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON iam_user_role
 FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_work_grants_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON iam_user_permission_grant
 FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_work_denies_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON iam_user_permission_deny
 FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_work_lead_assignment_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON department_team_lead
 FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_work_hr_assignment_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON department_hr_assignment
 FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_work_manager_assignment_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON department_manager_assignment
 FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
