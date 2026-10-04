-- Additive recurrence data. Existing worksheets and delivery evidence are untouched.
CREATE TABLE work_routine_template (
 id uuid PRIMARY KEY, department_id uuid NOT NULL REFERENCES org_department(id),
 owner_user_id uuid NOT NULL REFERENCES iam_user_account(id), request_id uuid NOT NULL,
 request_json jsonb NOT NULL, version bigint NOT NULL DEFAULT 0,
 title varchar(160) NOT NULL, instructions varchar(1000) NOT NULL,
 checklist_json jsonb NOT NULL, assignee_rule varchar(20) NOT NULL CHECK (assignee_rule IN ('EMPLOYEE','TEAM_LEAD')),
 due_offset_days integer NOT NULL CHECK (due_offset_days BETWEEN 0 AND 365),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(owner_user_id,request_id)
);
CREATE INDEX work_routine_template_scope ON work_routine_template(department_id,updated_at DESC,id);
CREATE TABLE work_routine_template_version (
 template_id uuid NOT NULL REFERENCES work_routine_template(id), version bigint NOT NULL,
 snapshot_json jsonb NOT NULL, created_at timestamptz NOT NULL,
 PRIMARY KEY(template_id,version)
);
CREATE TABLE work_routine_schedule (
 id uuid PRIMARY KEY, department_id uuid NOT NULL REFERENCES org_department(id),
 creator_user_id uuid NOT NULL REFERENCES iam_user_account(id), creator_role varchar(60) NOT NULL CHECK(creator_role IN ('ROLE_HR_ADMIN','ROLE_TEAM_LEAD')), request_id uuid NOT NULL,
 request_json jsonb NOT NULL, template_id uuid NOT NULL REFERENCES work_routine_template(id),
 employee_id uuid NOT NULL REFERENCES employee(id), definition_json jsonb NOT NULL,
 office_zone varchar(80) NOT NULL, paused boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 0, next_occurrence_date date, next_occurrence_at timestamptz,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(creator_user_id,request_id),
 CHECK ((next_occurrence_date IS NULL) = (next_occurrence_at IS NULL))
);
CREATE INDEX work_routine_schedule_scope ON work_routine_schedule(department_id,updated_at DESC,id);
CREATE INDEX work_routine_schedule_due ON work_routine_schedule(next_occurrence_at,id) WHERE NOT paused;
CREATE TABLE work_routine_occurrence (
 schedule_id uuid NOT NULL REFERENCES work_routine_schedule(id), occurrence_date date NOT NULL,
 scheduled_at timestamptz NOT NULL, template_version bigint NOT NULL, snapshot_json jsonb NOT NULL,
 task_id uuid REFERENCES department_work_task(id), status varchar(10) NOT NULL CHECK (status IN ('CREATED','BLOCKED')),
 exception_code varchar(100), message varchar(1000), attempts integer NOT NULL CHECK(attempts>0),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(schedule_id,occurrence_date), UNIQUE(task_id),
 CHECK ((status='CREATED' AND task_id IS NOT NULL AND exception_code IS NULL) OR
        (status='BLOCKED' AND task_id IS NULL AND exception_code IS NOT NULL))
);
CREATE INDEX work_routine_occurrence_history ON work_routine_occurrence(schedule_id,occurrence_date DESC);
-- Internal-call notifications already are the durable queued delivery outbox. Receipt ties
-- one occurrence to one queued message, including the crash window before dispatcher wakeup.
CREATE TABLE work_routine_notice_receipt (
 event_key varchar(160) PRIMARY KEY,
 notification_id uuid NOT NULL UNIQUE REFERENCES internal_call_notification(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
-- Versions and captured snapshots are append-only even if a future writer is incorrect.
CREATE FUNCTION protect_work_routine_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME IN ('work_routine_template_version','work_routine_notice_receipt') THEN
  RAISE EXCEPTION 'Routine versions and notification receipts are immutable';
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Routine occurrence receipts are retained'; END IF;
 IF NEW.schedule_id IS DISTINCT FROM OLD.schedule_id OR NEW.occurrence_date IS DISTINCT FROM OLD.occurrence_date
  OR NEW.snapshot_json IS DISTINCT FROM OLD.snapshot_json OR NEW.template_version IS DISTINCT FROM OLD.template_version
  OR NEW.scheduled_at IS DISTINCT FROM OLD.scheduled_at OR (OLD.status='CREATED' AND NEW IS DISTINCT FROM OLD) THEN
  RAISE EXCEPTION 'Routine occurrence snapshots and created receipts are immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER work_routine_version_immutable BEFORE UPDATE OR DELETE ON work_routine_template_version
 FOR EACH ROW EXECUTE FUNCTION protect_work_routine_snapshot();
CREATE TRIGGER work_routine_occurrence_immutable BEFORE UPDATE OR DELETE ON work_routine_occurrence
 FOR EACH ROW EXECUTE FUNCTION protect_work_routine_snapshot();
CREATE TRIGGER work_routine_notice_immutable BEFORE UPDATE OR DELETE ON work_routine_notice_receipt
 FOR EACH ROW EXECUTE FUNCTION protect_work_routine_snapshot();
