-- Discussion has its own revision history. Removing a comment never alters business approvals.
CREATE TABLE task_comment (
    id uuid PRIMARY KEY,
    work_task_id uuid NOT NULL REFERENCES department_work_task(id) ON DELETE CASCADE,
    author_user_id uuid NOT NULL,
    author_name varchar(170) NOT NULL,
    author_role varchar(40) NOT NULL,
    body text NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    mentions jsonb NOT NULL DEFAULT '[]'::jsonb,
    attachments jsonb NOT NULL DEFAULT '[]'::jsonb,
    client_request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    edited_at timestamptz,
    deleted_at timestamptz,
    UNIQUE (work_task_id, author_user_id, client_request_id)
);
CREATE INDEX ix_task_comment_task_time ON task_comment(work_task_id,created_at,id);
CREATE TABLE task_comment_revision (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    comment_id uuid NOT NULL REFERENCES task_comment(id) ON DELETE CASCADE,
    version bigint NOT NULL,
    operation varchar(10) NOT NULL CHECK (operation IN ('CREATED','EDITED','REMOVED')),
    body text NOT NULL,
    mentions jsonb NOT NULL,
    attachments jsonb NOT NULL,
    actor_user_id uuid NOT NULL,
    actor_name varchar(170) NOT NULL,
    actor_role varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    correlation_id varchar(100),
    UNIQUE (comment_id,version)
);

-- Capture identity at event time. Old records remain explicitly without a snapshot;
-- current names are never presented as historical names.
CREATE FUNCTION brainserve_activity_actor(actor text) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT jsonb_build_object('id',actor,'name',a.full_name,'role',
        (SELECT CASE WHEN count(*)=1 THEN min(role_name) ELSE NULL END FROM iam_user_role WHERE user_id=a.id))
    FROM iam_user_account a WHERE a.id::text=actor
$$;
CREATE FUNCTION capture_activity_actor() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor_snapshot jsonb;
BEGIN
    actor_snapshot:=brainserve_activity_actor(NEW.actor_id);
    NEW.details_json:=COALESCE(NEW.details_json,'{}'::jsonb) || jsonb_build_object('_activity',
        COALESCE(NEW.details_json->'_activity','{}'::jsonb) || jsonb_build_object('actor',actor_snapshot));
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_activity_actor_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION capture_activity_actor();
CREATE TRIGGER trg_activity_actor_checkpoint BEFORE INSERT ON visitor_checkpoint_event FOR EACH ROW EXECUTE FUNCTION capture_activity_actor();
CREATE TRIGGER trg_activity_actor_workboard BEFORE INSERT ON workboard_activity_event FOR EACH ROW EXECUTE FUNCTION capture_activity_actor();

-- The existing task mirror receives NEW values after flush, including the correct cycle
-- and frozen submission version. The audit writer can run before an ORM flush, so it
-- deliberately does not infer these values from the previous stored task version.
CREATE OR REPLACE FUNCTION mirror_workboard_activity_to_history()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE activity_type varchar(40); event_time timestamptz;
BEGIN
    IF TG_OP='INSERT' THEN activity_type:='TASK_CREATED'; event_time:=NEW.created_at;
    ELSIF OLD.team_lead_user_id IS DISTINCT FROM NEW.team_lead_user_id THEN activity_type:='RESPONSIBILITY_REASSIGNED'; event_time:=NEW.updated_at;
    ELSIF OLD.status IS DISTINCT FROM NEW.status THEN activity_type:='STATUS_CHANGED'; event_time:=NEW.updated_at;
    ELSE activity_type:='TASK_UPDATED'; event_time:=NEW.updated_at;
    END IF;
    PERFORM ensure_brainserve_history_partition('workboard_activity_event',event_time::date);
    INSERT INTO workboard_activity_event(occurred_at,work_task_id,department_id,employee_id,team_lead_user_id,event_type,previous_status,current_status,actor_id,details_json)
    VALUES(event_time,NEW.id,NEW.department_id,NEW.employee_id,NEW.team_lead_user_id,activity_type,
        CASE WHEN TG_OP='INSERT' THEN NULL ELSE OLD.status END,NEW.status,
        CASE WHEN TG_OP='INSERT' THEN NEW.created_by ELSE NEW.updated_by END,
        jsonb_build_object('title',NEW.title,'dueDate',NEW.due_date,'_activity',
            jsonb_build_object('departmentId',NEW.department_id,'cycle',NEW.rework_cycle,'evidenceVersion',NEW.submission_version,
                'taskVersion',NEW.version,'status',NEW.status)));
    RETURN NEW;
END;
$$;
