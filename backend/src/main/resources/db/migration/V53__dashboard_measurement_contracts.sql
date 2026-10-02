-- Additive evidence only: mutable legacy due_date is never an original commitment.
CREATE TABLE dashboard_measurement_coverage (
    id varchar(60) PRIMARY KEY,
    since timestamptz NOT NULL DEFAULT clock_timestamp(),
    reason varchar(1000) NOT NULL
);
INSERT INTO dashboard_measurement_coverage(id, reason) VALUES
('APPROVAL_STAGE_EVENTS', 'Stage transitions are captured from V53 onward; existing current stages have no proven entry time or governed deadline.'),
('ORIGINAL_WORK_COMMITMENTS', 'New task insert deadlines are immutable facts. Only non-backfilled TASK_CREATED history establishes legacy originals; current due_date is never inferred.'),
('WORK_ACCEPTANCE_HISTORY', 'Existing successful CEO final-decision audit history is used for acceptance, and workboard status history for rework. Employee approval/acknowledgment is not final acceptance. Migration-generated evidence is excluded.');

CREATE TABLE work_original_commitment (
    work_task_id uuid PRIMARY KEY,
    original_due_date date NOT NULL,
    committed_at timestamptz NOT NULL,
    source varchar(40) NOT NULL CHECK (source IN ('TASK_INSERT', 'CREATION_HISTORY'))
);
CREATE INDEX ix_work_original_commitment_due ON work_original_commitment(original_due_date, work_task_id);

-- V29 synthetic backfill reflects the then-current deadline, not task creation.
-- Reject malformed/ambiguous originals instead of selecting a convenient value.
INSERT INTO work_original_commitment(work_task_id, original_due_date, committed_at, source)
SELECT work_task_id, min((details_json->>'dueDate')::date), min(occurred_at), 'CREATION_HISTORY'
  FROM workboard_activity_event
 WHERE event_type = 'TASK_CREATED'
   AND NOT coalesce((details_json->>'backfilled')::boolean, false)
   AND actor_id NOT LIKE 'flyway%'
   AND details_json->>'dueDate' ~ '^\d{4}-\d{2}-\d{2}$'
   AND pg_input_is_valid(details_json->>'dueDate', 'date')
 GROUP BY work_task_id
HAVING count(DISTINCT details_json->>'dueDate') = 1;

CREATE FUNCTION capture_original_work_commitment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO work_original_commitment(work_task_id, original_due_date, committed_at, source)
    VALUES (NEW.id, NEW.due_date, NEW.created_at, 'TASK_INSERT');
    RETURN NEW;
END;
$$;
CREATE TRIGGER dashboard_original_work_commitment AFTER INSERT ON department_work_task
    FOR EACH ROW EXECUTE FUNCTION capture_original_work_commitment();

CREATE TABLE appointment_stage_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    appointment_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    previous_stage varchar(40),
    stage varchar(40) NOT NULL,
    actor_id varchar(120) NOT NULL
);
CREATE INDEX ix_appointment_stage_event_visit ON appointment_stage_event(appointment_id, occurred_at, id);
CREATE FUNCTION capture_appointment_stage_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status THEN
        INSERT INTO appointment_stage_event(appointment_id, occurred_at, previous_stage, stage, actor_id)
        VALUES (NEW.id, CASE WHEN TG_OP = 'INSERT' THEN NEW.created_at ELSE NEW.updated_at END,
                CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.status END, NEW.status,
                CASE WHEN TG_OP = 'INSERT' THEN NEW.created_by ELSE NEW.updated_by END);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER dashboard_appointment_stage_event AFTER INSERT OR UPDATE OF status ON appointment
    FOR EACH ROW EXECUTE FUNCTION capture_appointment_stage_event();

CREATE FUNCTION reject_dashboard_fact_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Dashboard source facts are immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER immutable_work_original_commitment BEFORE UPDATE OR DELETE ON work_original_commitment
    FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();
CREATE TRIGGER immutable_appointment_stage_event BEFORE UPDATE OR DELETE ON appointment_stage_event
    FOR EACH ROW EXECUTE FUNCTION reject_dashboard_fact_mutation();

-- Extend V52's committed generation to the operational sources used by cards.
CREATE TRIGGER reporting_account_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON iam_user_account
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_outbox_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON notification_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_audit_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_work_audit_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON work_task_audit_record
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE INDEX ix_dashboard_arrival_time ON appointment(security_intake_at, id) WHERE security_intake_at IS NOT NULL;
CREATE INDEX ix_dashboard_checkin_time ON visit_access_record(checked_in_at, id);
CREATE INDEX ix_dashboard_dead_outbox ON notification_outbox(created_at, id) WHERE status = 'DEAD';
CREATE INDEX ix_dashboard_ceo_acceptance ON audit_event_history((details_json->>'workTaskId'), occurred_at DESC, id DESC)
    WHERE event_type = 'WORK_INSIGHT_CEO_APPROVED' AND target_type = 'WORK_TASK_AUDIT' AND outcome = 'SUCCESS';
