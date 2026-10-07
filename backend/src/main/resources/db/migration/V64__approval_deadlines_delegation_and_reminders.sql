CREATE TABLE approval_policy (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 kind varchar(8) NOT NULL CHECK(kind IN ('WORK','VISIT')),
 stage varchar(20) NOT NULL CHECK(stage IN ('HOST','TEAM_LEAD','HR_ADMIN','MANAGER','CEO')),
 version bigint NOT NULL,
 enabled boolean NOT NULL DEFAULT false,
 deadline_minutes integer NOT NULL CHECK(deadline_minutes BETWEEN 5 AND 43200),
 reminder_minutes integer NOT NULL CHECK(reminder_minutes BETWEEN 5 AND 10080),
 escalation_role varchar(20) NOT NULL CHECK(escalation_role IN ('HR_ADMIN','MANAGER','CEO')),
 created_at timestamptz NOT NULL DEFAULT now(),
 -- Retain the creator identity independently of the live account lifecycle.
 created_by uuid,
 UNIQUE(kind,stage,version)
);
INSERT INTO approval_policy(kind,stage,version,deadline_minutes,reminder_minutes,escalation_role)
 SELECT kind,stage,1,120,60,CASE WHEN stage IN ('MANAGER','CEO') THEN 'CEO' ELSE 'MANAGER' END
 FROM (VALUES('WORK'),('VISIT')) k(kind) CROSS JOIN (VALUES('TEAM_LEAD'),('HR_ADMIN'),('MANAGER'),('CEO')) s(stage);
INSERT INTO approval_policy(kind,stage,version,deadline_minutes,reminder_minutes,escalation_role)
 VALUES('VISIT','HOST',1,120,60,'HR_ADMIN');
CREATE TRIGGER immutable_approval_policy BEFORE UPDATE OR DELETE ON approval_policy
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();
CREATE TABLE approval_stage (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 kind varchar(8) NOT NULL CHECK(kind IN ('WORK','VISIT')),
 resource_id uuid NOT NULL,
 department_id uuid REFERENCES org_department(id),
 stage varchar(20) NOT NULL,
 entry_key varchar(160) NOT NULL,
 entered_at timestamptz NOT NULL,
 entry_known boolean NOT NULL DEFAULT true,
 policy_id bigint NOT NULL REFERENCES approval_policy(id),
 deadline_at timestamptz,
 next_reminder_at timestamptz,
 closed_at timestamptz
);
CREATE UNIQUE INDEX uq_approval_open_stage ON approval_stage(kind,resource_id) WHERE closed_at IS NULL;
CREATE INDEX ix_approval_overdue ON approval_stage(deadline_at,id) WHERE closed_at IS NULL;
CREATE TABLE approval_delegation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 stage_id uuid NOT NULL REFERENCES approval_stage(id),
 delegator_id uuid NOT NULL REFERENCES iam_user_account(id),
 delegate_id uuid NOT NULL REFERENCES iam_user_account(id),
 expires_at timestamptz NOT NULL,
 reason varchar(500) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 revoked_at timestamptz,
 CHECK(delegator_id<>delegate_id), CHECK(expires_at>created_at)
);
CREATE UNIQUE INDEX uq_approval_live_grant ON approval_delegation(stage_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_approval_delegate ON approval_delegation(delegate_id,stage_id) WHERE revoked_at IS NULL;
CREATE TABLE approval_reminder_receipt (
 stage_id uuid NOT NULL REFERENCES approval_stage(id),
 window_number bigint NOT NULL CHECK(window_number>=0),
 recipient_id uuid NOT NULL REFERENCES iam_user_account(id),
 notification_id uuid NOT NULL REFERENCES internal_call_notification(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(stage_id,window_number,recipient_id)
);
CREATE TRIGGER immutable_approval_reminder BEFORE UPDATE OR DELETE ON approval_reminder_receipt
 FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();

CREATE FUNCTION sync_approval_stage(resource_kind varchar, target_id uuid, known boolean DEFAULT true) RETURNS void LANGUAGE plpgsql AS $$
DECLARE current_stage varchar; dept uuid; entry_key_value varchar; previous approval_stage%ROWTYPE; p approval_policy%ROWTYPE;
BEGIN
 IF resource_kind='WORK' THEN
  SELECT work_current_review_stage(t.id),t.department_id,
   t.assignment_revision::text||':'||coalesce(t.submission_version,0)::text||':'||coalesce(a.rework_cycle,0)::text
  INTO current_stage,dept,entry_key_value FROM department_work_task t
  LEFT JOIN work_task_audit_record a ON a.work_task_id=t.id WHERE t.id=target_id FOR UPDATE OF t;
 ELSE
  SELECT CASE status WHEN 'PENDING_APPROVAL' THEN 'HOST' WHEN 'PENDING_HR_APPROVAL' THEN 'HR_ADMIN'
   WHEN 'PENDING_TEAM_LEAD_APPROVAL' THEN 'TEAM_LEAD' WHEN 'PENDING_MANAGER_APPROVAL' THEN 'MANAGER'
   WHEN 'PENDING_CEO_APPROVAL' THEN 'CEO' END,
   coalesce(routing_department_id,(SELECT department_id FROM employee WHERE id=host_employee_id)),status
  INTO current_stage,dept,entry_key_value FROM appointment WHERE id=target_id FOR UPDATE;
 END IF;
 SELECT * INTO previous FROM approval_stage WHERE kind=resource_kind AND resource_id=target_id AND closed_at IS NULL FOR UPDATE;
 IF previous.id IS NOT NULL AND (current_stage IS NULL OR previous.stage<>current_stage OR previous.entry_key<>entry_key_value) THEN
  UPDATE approval_stage SET closed_at=now() WHERE id=previous.id; previous.id:=NULL;
 END IF;
 IF current_stage IS NOT NULL AND previous.id IS NULL THEN
  SELECT * INTO p FROM approval_policy WHERE kind=resource_kind AND stage=current_stage ORDER BY version DESC LIMIT 1;
  INSERT INTO approval_stage(kind,resource_id,department_id,stage,entry_key,entered_at,entry_known,policy_id,deadline_at,next_reminder_at)
   VALUES(resource_kind,target_id,dept,current_stage,entry_key_value,now(),known,p.id,
    CASE WHEN p.enabled THEN now()+make_interval(mins=>p.deadline_minutes) END,
    CASE WHEN p.enabled THEN now()+make_interval(mins=>p.deadline_minutes) END);
 END IF;
END $$;
CREATE FUNCTION capture_approval_stage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='appointment' THEN PERFORM sync_approval_stage('VISIT',NEW.id);
 ELSIF TG_TABLE_NAME='department_work_task' THEN PERFORM sync_approval_stage('WORK',NEW.id);
 ELSE PERFORM sync_approval_stage('WORK',coalesce(NEW.work_task_id,OLD.work_task_id)); END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER approval_visit_stage AFTER INSERT OR UPDATE ON appointment
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION capture_approval_stage();
CREATE CONSTRAINT TRIGGER approval_work_stage AFTER INSERT OR UPDATE ON department_work_task
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION capture_approval_stage();
CREATE CONSTRAINT TRIGGER approval_audit_stage AFTER INSERT OR UPDATE OR DELETE ON work_task_audit_record
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION capture_approval_stage();
-- Legacy stages are observed now; their original entry age remains explicitly unknown.
DO $$ DECLARE row_id uuid; BEGIN
 FOR row_id IN SELECT id FROM department_work_task LOOP PERFORM sync_approval_stage('WORK',row_id,false); END LOOP;
 FOR row_id IN SELECT id FROM appointment WHERE status IN ('PENDING_APPROVAL','PENDING_HR_APPROVAL','PENDING_TEAM_LEAD_APPROVAL','PENDING_MANAGER_APPROVAL','PENDING_CEO_APPROVAL')
 LOOP PERFORM sync_approval_stage('VISIT',row_id,false); END LOOP;
END $$;
