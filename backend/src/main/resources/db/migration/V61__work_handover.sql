-- Original ownership is captured before the first controlled handover. Pre-V61 tasks have
-- no transfer operation; historical submission names/authors remain unknown unless captured.
ALTER TABLE department_work_task
 ADD COLUMN original_employee_id uuid,
 ADD COLUMN assignment_revision bigint NOT NULL DEFAULT 0 CHECK(assignment_revision>=0);
UPDATE department_work_task SET original_employee_id=employee_id;
ALTER TABLE department_work_task ALTER COLUMN original_employee_id SET NOT NULL;
ALTER TABLE work_task_audit_record ADD COLUMN assignment_revision bigint NOT NULL DEFAULT 0 CHECK(assignment_revision>=0);

CREATE TABLE work_task_handover (
 id uuid PRIMARY KEY,
 task_id uuid NOT NULL REFERENCES department_work_task(id),
 from_employee_id uuid NOT NULL, from_name varchar(170) NOT NULL,
 to_employee_id uuid NOT NULL, to_name varchar(170) NOT NULL,
 actor_user_id uuid NOT NULL, actor_name varchar(170) NOT NULL,
 reason varchar(1000) NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 1000),
 effective_at timestamptz NOT NULL, occurred_at timestamptz NOT NULL,
 assignment_revision bigint NOT NULL CHECK(assignment_revision>0),
 previous_submission_version bigint,
 previous_task_snapshot jsonb NOT NULL,
 previous_audit_snapshot jsonb,
 UNIQUE(task_id,assignment_revision),
 CHECK(from_employee_id<>to_employee_id),
 CHECK(effective_at=occurred_at)
);
CREATE INDEX ix_work_task_handover_history ON work_task_handover(task_id,assignment_revision DESC);
CREATE TABLE work_handover_notice_receipt (
 event_key varchar(160) PRIMARY KEY,
 notification_id uuid NOT NULL UNIQUE REFERENCES internal_call_notification(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION protect_work_handover_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Handover history and notification receipts are immutable and retained' USING ERRCODE='23514';
END $$;
CREATE TRIGGER immutable_work_handover BEFORE UPDATE OR DELETE ON work_task_handover
 FOR EACH ROW EXECUTE FUNCTION protect_work_handover_receipt();
CREATE TRIGGER immutable_work_handover_notice BEFORE UPDATE OR DELETE ON work_handover_notice_receipt
 FOR EACH ROW EXECUTE FUNCTION protect_work_handover_receipt();

CREATE FUNCTION protect_work_assignment_and_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_submission jsonb; new_submission jsonb; old_draft jsonb; old_payload jsonb; new_payload jsonb; pos integer;
BEGIN
 IF TG_OP='INSERT' THEN
  -- Existing native SQL fixtures and compatible deployment writers may omit the new field.
  NEW.original_employee_id:=coalesce(NEW.original_employee_id,NEW.employee_id);
  RETURN NEW;
 END IF;
 IF NEW.original_employee_id IS DISTINCT FROM OLD.original_employee_id
    OR NEW.original_due_date IS DISTINCT FROM OLD.original_due_date
    OR NEW.original_due_date_known IS DISTINCT FROM OLD.original_due_date_known THEN
  RAISE EXCEPTION 'Original assignee and original deadline are immutable' USING ERRCODE='23514';
 END IF;
 IF NEW.employee_id IS DISTINCT FROM OLD.employee_id THEN
  IF NEW.assignment_revision<>OLD.assignment_revision+1 OR NOT EXISTS(
      SELECT 1 FROM work_task_handover h WHERE h.task_id=OLD.id AND h.assignment_revision=NEW.assignment_revision
        AND h.from_employee_id=OLD.employee_id AND h.to_employee_id=NEW.employee_id
        AND h.previous_task_snapshot=to_jsonb(OLD)) THEN
   RAISE EXCEPTION 'Assignment changes require a retained controlled handover' USING ERRCODE='23514';
  END IF;
 ELSIF NEW.assignment_revision IS DISTINCT FROM OLD.assignment_revision THEN
  RAISE EXCEPTION 'Assignment revision cannot change without a handover' USING ERRCODE='23514';
 END IF;
 pos:=0;
 FOR old_submission IN SELECT value FROM jsonb_array_elements(coalesce(OLD.planning_state->'submissions','[]'::jsonb)) LOOP
  new_submission:=NEW.planning_state->'submissions'->pos;
  -- Normalize missing legacy nullable/default fields without inventing an author.
  old_payload:=jsonb_strip_nulls(jsonb_build_object('authorshipKnown',false) || (old_submission-'acceptedAt'-'acceptedByRole'-'currentlyAccepted'));
  new_payload:=jsonb_strip_nulls(jsonb_build_object('authorshipKnown',false) || (new_submission-'acceptedAt'-'acceptedByRole'-'currentlyAccepted'));
  IF new_submission IS NULL OR old_payload IS DISTINCT FROM new_payload
      OR ((old_submission->>'acceptedAt') IS NOT NULL AND
          ((old_submission->'acceptedAt') IS DISTINCT FROM (new_submission->'acceptedAt') OR
           (old_submission->'acceptedByRole') IS DISTINCT FROM (new_submission->'acceptedByRole'))) THEN
   RAISE EXCEPTION 'Authored submission snapshots and prior acceptance are immutable' USING ERRCODE='23514';
  END IF;
  pos:=pos+1;
 END LOOP;
 pos:=0;
 FOR old_draft IN SELECT value FROM jsonb_array_elements(coalesce(OLD.planning_state->'retainedDrafts','[]'::jsonb)) LOOP
  IF old_draft IS DISTINCT FROM (NEW.planning_state->'retainedDrafts'->pos) THEN
   RAISE EXCEPTION 'Retained authored drafts are immutable' USING ERRCODE='23514';
  END IF;
  pos:=pos+1;
 END LOOP;
 RETURN NEW;
END $$;
CREATE TRIGGER work_assignment_and_delivery_immutable BEFORE INSERT OR UPDATE ON department_work_task
 FOR EACH ROW EXECUTE FUNCTION protect_work_assignment_and_delivery();

-- Capture assignment identity separately from actual business rework counts.
-- A transfer starts a fresh audit record and must not inflate the rework rate.
CREATE FUNCTION capture_work_audit_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 SELECT assignment_revision,greatest(NEW.rework_cycle,rework_cycle)
   INTO NEW.assignment_revision,NEW.rework_cycle FROM department_work_task WHERE id=NEW.work_task_id;
 RETURN NEW;
END $$;
CREATE TRIGGER work_audit_assignment_capture BEFORE INSERT ON work_task_audit_record
 FOR EACH ROW EXECUTE FUNCTION capture_work_audit_assignment();
