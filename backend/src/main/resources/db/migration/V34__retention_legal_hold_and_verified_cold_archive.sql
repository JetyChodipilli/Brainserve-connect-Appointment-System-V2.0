-- End-to-end retention governance for long-lived BrainServe data.
--
-- Operational tables remain small and useful, immutable monthly history is
-- archived to encrypted object storage, legal holds block every destructive
-- step, and every governance decision is appended to a hash-chained ledger.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE data_retention_policy
    ADD COLUMN disposal_action varchar(20) NOT NULL DEFAULT 'DELETE';

ALTER TABLE data_retention_policy
    ADD CONSTRAINT ck_data_retention_disposal_action
        CHECK (disposal_action IN ('DELETE', 'ANONYMIZE'));

UPDATE data_retention_policy
   SET disposal_action = CASE WHEN dataset IN ('EMPLOYEE', 'APPOINTMENT') THEN 'ANONYMIZE' ELSE 'DELETE' END;

INSERT INTO data_retention_policy(dataset, hot_days, warm_months, archive_years, disposal_action) VALUES
    ('EMPLOYEE', 365, 60, 7, 'ANONYMIZE'),
    ('VISITOR', 90, 24, 7, 'DELETE'),
    ('APPOINTMENT', 365, 36, 7, 'ANONYMIZE'),
    ('ESSENTIAL_LOG', 180, 36, 10, 'DELETE')
ON CONFLICT (dataset) DO NOTHING;

-- The old visitor-only day setting is superseded by the governed VISITOR
-- policy above, preventing two conflicting retention sources.
DELETE FROM system_setting WHERE setting_key = 'VISITOR.RETENTION_DAYS';

CREATE TABLE data_legal_hold (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset varchar(60) NOT NULL,
    hold_kind varchar(30) NOT NULL CHECK (hold_kind IN ('LEGAL_HOLD', 'ACTIVE_INVESTIGATION')),
    scope_type varchar(20) NOT NULL CHECK (scope_type IN ('DATASET', 'PARTITION', 'SUBJECT')),
    scope_ref varchar(180),
    case_reference varchar(120) NOT NULL,
    reason varchar(1200) NOT NULL,
    review_on date,
    placed_by varchar(120) NOT NULL,
    placed_at timestamptz NOT NULL DEFAULT now(),
    released_by varchar(120),
    released_at timestamptz,
    release_reason varchar(1200),
    CONSTRAINT ck_data_legal_hold_scope CHECK (
        (scope_type = 'DATASET' AND scope_ref IS NULL)
        OR (scope_type IN ('PARTITION', 'SUBJECT') AND scope_ref IS NOT NULL)
    ),
    CONSTRAINT ck_data_legal_hold_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL AND release_reason IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_active_data_legal_hold
    ON data_legal_hold(dataset, hold_kind, scope_type, COALESCE(scope_ref, ''), case_reference)
    WHERE released_at IS NULL;
CREATE INDEX ix_data_legal_hold_active_scope
    ON data_legal_hold(dataset, scope_type, scope_ref)
    WHERE released_at IS NULL;

CREATE TABLE data_governance_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_sequence bigint NOT NULL UNIQUE,
    action_type varchar(100) NOT NULL,
    dataset varchar(60) NOT NULL,
    target_ref varchar(220) NOT NULL,
    actor varchar(120) NOT NULL,
    outcome varchar(30) NOT NULL,
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    canonical_payload text NOT NULL,
    previous_hash varchar(64) NOT NULL,
    entry_hash varchar(64) NOT NULL
);

CREATE INDEX ix_data_governance_log_time
    ON data_governance_log(occurred_at DESC, ledger_sequence DESC);
CREATE INDEX ix_data_governance_log_dataset_target
    ON data_governance_log(dataset, target_ref, occurred_at DESC);

CREATE OR REPLACE FUNCTION prepare_data_governance_log_entry()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('brainserve-data-governance-ledger'));
    SELECT ledger_sequence + 1, entry_hash
      INTO NEW.ledger_sequence, NEW.previous_hash
      FROM data_governance_log
     ORDER BY ledger_sequence DESC
     LIMIT 1;
    NEW.ledger_sequence := COALESCE(NEW.ledger_sequence, 1);
    NEW.previous_hash := COALESCE(NEW.previous_hash, repeat('0', 64));
    NEW.canonical_payload := concat_ws('|',
        NEW.ledger_sequence::text,
        NEW.action_type,
        NEW.dataset,
        NEW.target_ref,
        NEW.actor,
        NEW.outcome,
        to_char(NEW.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        NEW.details_json::text
    );
    NEW.entry_hash := encode(digest(NEW.previous_hash || '|' || NEW.canonical_payload, 'sha256'), 'hex');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prepare_data_governance_log
BEFORE INSERT ON data_governance_log
FOR EACH ROW EXECUTE FUNCTION prepare_data_governance_log_entry();

CREATE OR REPLACE FUNCTION reject_data_governance_log_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'data_governance_log is append-only';
END;
$$;

CREATE TRIGGER trg_reject_data_governance_log_mutation
BEFORE UPDATE OR DELETE ON data_governance_log
FOR EACH ROW EXECUTE FUNCTION reject_data_governance_log_mutation();

CREATE TABLE backup_expiry_register (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset varchar(60) NOT NULL,
    target_ref varchar(220) NOT NULL,
    disposed_at timestamptz NOT NULL,
    backup_expires_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'CONFIRMED')),
    confirmed_at timestamptz
);

CREATE INDEX ix_backup_expiry_pending
    ON backup_expiry_register(status, backup_expires_at);

ALTER TABLE data_archive_manifest
    DROP CONSTRAINT IF EXISTS data_archive_manifest_status_check;

ALTER TABLE data_archive_manifest
    ADD COLUMN encryption_algorithm varchar(40),
    ADD COLUMN encryption_key_version varchar(60),
    ADD COLUMN object_version_id varchar(220),
    ADD COLUMN object_size_bytes bigint NOT NULL DEFAULT 0,
    ADD COLUMN verification_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN verified_at timestamptz,
    ADD COLUMN restore_tested_at timestamptz,
    ADD COLUMN verified_row_count bigint,
    ADD COLUMN database_removed_at timestamptz,
    ADD COLUMN disposed_at timestamptz,
    ADD COLUMN backup_expires_at timestamptz,
    ADD COLUMN last_error varchar(1600);

ALTER TABLE data_archive_manifest
    ADD CONSTRAINT ck_data_archive_manifest_status CHECK (status IN (
        'WARM', 'ARCHIVE_ELIGIBLE', 'ARCHIVING', 'ARCHIVED', 'VERIFYING',
        'VERIFIED', 'DATABASE_REMOVED', 'HOLD_BLOCKED', 'FAILED', 'DISPOSED'
    ));

CREATE INDEX ix_archive_manifest_disposal
    ON data_archive_manifest(status, period_end)
    WHERE status IN ('VERIFIED', 'DATABASE_REMOVED');

-- Immutable monthly snapshots for the five independently governed datasets.
-- The snapshot row is self-contained so a cold archive can be validated and
-- restored without joining against mutable operational tables.
CREATE TABLE employee_history_event (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL,
    subject_id uuid NOT NULL,
    department_id uuid,
    event_type varchar(20) NOT NULL,
    row_data jsonb NOT NULL,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE visitor_history_event (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL,
    subject_id uuid NOT NULL,
    event_type varchar(20) NOT NULL,
    row_data jsonb NOT NULL,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE appointment_history_event (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL,
    subject_id uuid NOT NULL,
    department_id uuid,
    event_type varchar(20) NOT NULL,
    row_data jsonb NOT NULL,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE essential_log_history (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL,
    subject_id uuid NOT NULL,
    event_type varchar(20) NOT NULL,
    row_data jsonb NOT NULL,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_employee_history_subject_time
    ON employee_history_event(subject_id, occurred_at DESC);
CREATE INDEX ix_employee_history_department_time
    ON employee_history_event(department_id, occurred_at DESC);
CREATE INDEX ix_visitor_history_subject_time
    ON visitor_history_event(subject_id, occurred_at DESC);
CREATE INDEX ix_appointment_history_subject_time
    ON appointment_history_event(subject_id, occurred_at DESC);
CREATE INDEX ix_appointment_history_department_time
    ON appointment_history_event(department_id, occurred_at DESC);
CREATE INDEX ix_essential_history_subject_time
    ON essential_log_history(subject_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION ensure_brainserve_history_partition(parent_table text, event_month date)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    month_start date := date_trunc('month', event_month)::date;
    month_end date := (date_trunc('month', event_month) + interval '1 month')::date;
    child_table text;
BEGIN
    IF parent_table NOT IN (
        'audit_event_history', 'visitor_checkpoint_event', 'workboard_activity_event',
        'employee_history_event', 'visitor_history_event', 'appointment_history_event',
        'essential_log_history'
    ) THEN
        RAISE EXCEPTION 'Unsupported history parent table: %', parent_table;
    END IF;
    child_table := parent_table || '_' || to_char(month_start, 'YYYY_MM');
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                   child_table, parent_table, month_start, month_end);
END;
$$;

DO $$
DECLARE
    month_cursor date := date_trunc('month', LEAST(
        COALESCE((SELECT min(created_at)::date FROM employee), current_date),
        COALESCE((SELECT min(created_at)::date FROM visitor), current_date),
        COALESCE((SELECT min(created_at)::date FROM appointment), current_date),
        COALESCE((SELECT min(occurred_at)::date FROM essential_log_record), current_date)
    ))::date;
    final_month date := (date_trunc('month', current_date) + interval '3 months')::date;
BEGIN
    WHILE month_cursor <= final_month LOOP
        PERFORM ensure_brainserve_history_partition('employee_history_event', month_cursor);
        PERFORM ensure_brainserve_history_partition('visitor_history_event', month_cursor);
        PERFORM ensure_brainserve_history_partition('appointment_history_event', month_cursor);
        PERFORM ensure_brainserve_history_partition('essential_log_history', month_cursor);
        month_cursor := (month_cursor + interval '1 month')::date;
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION mirror_employee_to_history()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    snapshot jsonb := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    event_time timestamptz := CASE
        WHEN TG_OP = 'DELETE' THEN now()
        WHEN TG_OP = 'INSERT' THEN NEW.created_at
        ELSE NEW.updated_at
    END;
    subject uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    department uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.department_id ELSE NEW.department_id END;
BEGIN
    PERFORM ensure_brainserve_history_partition('employee_history_event', event_time::date);
    INSERT INTO employee_history_event(occurred_at, subject_id, department_id, event_type, row_data)
    VALUES (event_time, subject, department, TG_OP, snapshot);
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_employee_history
AFTER INSERT OR UPDATE OR DELETE ON employee
FOR EACH ROW EXECUTE FUNCTION mirror_employee_to_history();

CREATE OR REPLACE FUNCTION mirror_visitor_to_history()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    snapshot jsonb := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    event_time timestamptz := CASE
        WHEN TG_OP = 'DELETE' THEN now()
        WHEN TG_OP = 'INSERT' THEN NEW.created_at
        ELSE NEW.updated_at
    END;
    subject uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
BEGIN
    PERFORM ensure_brainserve_history_partition('visitor_history_event', event_time::date);
    INSERT INTO visitor_history_event(occurred_at, subject_id, event_type, row_data)
    VALUES (event_time, subject, TG_OP, snapshot);
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_visitor_history
AFTER INSERT OR UPDATE OR DELETE ON visitor
FOR EACH ROW EXECUTE FUNCTION mirror_visitor_to_history();

CREATE OR REPLACE FUNCTION mirror_appointment_to_history()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    snapshot jsonb := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    event_time timestamptz := CASE
        WHEN TG_OP = 'DELETE' THEN now()
        WHEN TG_OP = 'INSERT' THEN NEW.created_at
        ELSE NEW.updated_at
    END;
    subject uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    department uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.routing_department_id ELSE NEW.routing_department_id END;
BEGIN
    PERFORM ensure_brainserve_history_partition('appointment_history_event', event_time::date);
    INSERT INTO appointment_history_event(occurred_at, subject_id, department_id, event_type, row_data)
    VALUES (event_time, subject, department, TG_OP, snapshot);
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_appointment_history
AFTER INSERT OR UPDATE OR DELETE ON appointment
FOR EACH ROW EXECUTE FUNCTION mirror_appointment_to_history();

CREATE OR REPLACE FUNCTION mirror_essential_log_to_history()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ensure_brainserve_history_partition('essential_log_history', NEW.occurred_at::date);
    INSERT INTO essential_log_history(occurred_at, subject_id, event_type, row_data)
    VALUES (NEW.occurred_at, NEW.id, 'INSERT', to_jsonb(NEW));
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_essential_log_history
AFTER INSERT ON essential_log_record
FOR EACH ROW EXECUTE FUNCTION mirror_essential_log_to_history();

INSERT INTO employee_history_event(occurred_at, subject_id, department_id, event_type, row_data)
SELECT created_at, id, department_id, 'BACKFILL', to_jsonb(source)
  FROM employee source;

INSERT INTO visitor_history_event(occurred_at, subject_id, event_type, row_data)
SELECT created_at, id, 'BACKFILL', to_jsonb(source)
  FROM visitor source;

INSERT INTO appointment_history_event(occurred_at, subject_id, department_id, event_type, row_data)
SELECT created_at, id, routing_department_id, 'BACKFILL', to_jsonb(source)
  FROM appointment source;

INSERT INTO essential_log_history(occurred_at, subject_id, event_type, row_data)
SELECT occurred_at, id, 'BACKFILL', to_jsonb(source)
  FROM essential_log_record source;

ALTER TABLE employee ADD COLUMN retention_anonymized_at timestamptz;
ALTER TABLE appointment ADD COLUMN retention_anonymized_at timestamptz;

CREATE INDEX ix_employee_retention_eligible
    ON employee(relieving_date, id)
    WHERE retention_anonymized_at IS NULL
      AND status IN ('RESIGNED', 'TERMINATED', 'INACTIVE');
CREATE INDEX ix_appointment_retention_eligible
    ON appointment(slot_end, id)
    WHERE retention_anonymized_at IS NULL
      AND status IN ('REJECTED', 'CANCELLED', 'COMPLETED', 'NO_SHOW', 'EXPIRED');
CREATE INDEX ix_visitor_retention_eligible
    ON visitor(consented_at, id)
    WHERE restricted = false;
