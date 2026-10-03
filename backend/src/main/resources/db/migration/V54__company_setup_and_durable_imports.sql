-- Additive setup progress and bounded create-only import state. No business records or credentials are seeded.
CREATE TABLE company_setup_progress (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    current_step varchar(30) NOT NULL DEFAULT 'company',
    completed_at timestamptz,
    readiness_fingerprint varchar(64) NOT NULL DEFAULT '',
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO company_setup_progress(singleton) VALUES (true);

INSERT INTO system_setting(id, setting_key, setting_value, value_type, description, version, created_at, created_by, updated_at, updated_by)
VALUES ('00000000-0000-0000-0000-000000000254', 'COMPANY.OFFICE_ZONE', 'Asia/Kolkata', 'STRING',
        'Office time-zone preference; deployment brainserve.appointment.office-zone must match before setup is ready',
        0, now(), 'flyway-v54', now(), 'flyway-v54') ON CONFLICT(setting_key) DO NOTHING;

CREATE TABLE bulk_import_job (
    id uuid PRIMARY KEY,
    creator_id uuid NOT NULL REFERENCES iam_user_account(id),
    kind varchar(20) NOT NULL CHECK (kind IN ('DEPARTMENTS','EMPLOYEES','VISITORS')),
    duplicate_policy varchar(10) NOT NULL CHECK (duplicate_policy IN ('SKIP','FAIL')),
    status varchar(20) NOT NULL CHECK (status IN ('PREVIEW','QUEUED','RUNNING','COMPLETED','EXPIRED')),
    checksum varchar(64) NOT NULL,
    authority_fingerprint varchar(64) NOT NULL,
    scope_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    total_rows integer NOT NULL CHECK (total_rows BETWEEN 1 AND 1000)
);
CREATE INDEX ix_bulk_import_owner ON bulk_import_job(creator_id, created_at DESC);
CREATE TABLE bulk_import_row (
    job_id uuid NOT NULL REFERENCES bulk_import_job(id) ON DELETE CASCADE,
    row_number integer NOT NULL CHECK (row_number BETWEEN 2 AND 1001),
    status varchar(10) NOT NULL CHECK (status IN ('VALID','APPLIED','SKIPPED','FAILED')),
    values_json jsonb NOT NULL,
    references_json jsonb NOT NULL,
    errors_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    record_id uuid,
    PRIMARY KEY(job_id, row_number),
    CHECK ((status = 'APPLIED') = (record_id IS NOT NULL))
);
-- Keys bind an actor to exactly one job; multiple keys may safely resume that same durable job.
CREATE TABLE bulk_import_execution_key (
    creator_id uuid NOT NULL REFERENCES iam_user_account(id),
    idempotency_key varchar(100) NOT NULL,
    job_id uuid NOT NULL REFERENCES bulk_import_job(id) ON DELETE CASCADE,
    PRIMARY KEY(creator_id, idempotency_key)
);
