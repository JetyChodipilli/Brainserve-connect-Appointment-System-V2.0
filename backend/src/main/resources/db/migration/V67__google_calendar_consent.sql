ALTER TABLE integration_connection DROP CONSTRAINT integration_connection_provider_check;
ALTER TABLE integration_connection ADD CONSTRAINT integration_connection_provider_check
 CHECK(provider IN ('SIMULATOR_CALENDAR','SIMULATOR_MESSAGING','GOOGLE_CALENDAR'));
-- A not-yet-consented Google connection has no credential. Simulator invariants remain intact.
DO $$ DECLARE constraint_name text; BEGIN
 SELECT conname INTO STRICT constraint_name FROM pg_constraint
 WHERE conrelid='integration_connection'::regclass AND contype='c'
 AND pg_get_constraintdef(oid) LIKE '%status%REVOKED%credential_ciphertext IS NOT NULL%';
 EXECUTE format('ALTER TABLE integration_connection DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE integration_connection ADD CONSTRAINT ck_integration_credential_presence
 CHECK(status='REVOKED' OR credential_ciphertext IS NOT NULL OR (provider='GOOGLE_CALENDAR' AND status='NEEDS_RECONNECT'));
CREATE TABLE integration_google_calendar (
 connection_id uuid PRIMARY KEY REFERENCES integration_connection(id),
 calendar_id varchar(512),
 provisioning_status varchar(24) NOT NULL DEFAULT 'UNPROVISIONED'
 CHECK(provisioning_status IN ('UNPROVISIONED','PROVISIONING_UNKNOWN','READY')),
 revocation_status varchar(12) NOT NULL DEFAULT 'NONE'
 CHECK(revocation_status IN ('NONE','PENDING','RETRYING','COMPLETE','FAILED')),
 last_result_code varchar(40) NOT NULL DEFAULT 'CONSENT_REQUIRED',
 CHECK((provisioning_status='READY')=(calendar_id IS NOT NULL))
);
CREATE TABLE integration_google_consent (
 id uuid PRIMARY KEY,
 request_id uuid NOT NULL UNIQUE,
 connection_id uuid NOT NULL REFERENCES integration_google_calendar(connection_id),
 owner_id uuid NOT NULL REFERENCES iam_user_account(id),
 session_id uuid NOT NULL,
 observed_version bigint NOT NULL,
 is_reconsent boolean NOT NULL,
 label varchar(80) NOT NULL,
 ticket_hash varchar(64) UNIQUE,
 state_hash varchar(64) UNIQUE,
 ticket_ciphertext text,
 state_ciphertext text,
 verifier_ciphertext text,
 browser_hash varchar(64),
 code_ciphertext text,
 status varchar(24) NOT NULL CHECK(status IN ('INITIATED','AUTHORIZED','CALLBACK_RECEIVED','EXCHANGING','COMPLETED','DENIED','EXPIRED','CANCELLED','EXCHANGE_UNKNOWN','FAILED')),
 expires_at timestamptz NOT NULL,
 last_result_code varchar(40) NOT NULL DEFAULT 'CONSENT_REQUIRED',
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(length(ticket_ciphertext)<=1024 AND length(state_ciphertext)<=1024 AND length(verifier_ciphertext)<=1024 AND length(code_ciphertext)<=8192)
);
CREATE INDEX ix_google_consent_owner ON integration_google_consent(owner_id,created_at DESC);
CREATE INDEX ix_google_consent_expiry ON integration_google_consent(expires_at)
 WHERE status IN ('INITIATED','AUTHORIZED','CALLBACK_RECEIVED','EXCHANGING');
CREATE TABLE integration_google_revocation (
 id uuid PRIMARY KEY,
 connection_id uuid NOT NULL REFERENCES integration_google_calendar(connection_id),
 credential_version bigint NOT NULL,
 token_ciphertext text NOT NULL CHECK(length(token_ciphertext)<=16384),
 status varchar(12) NOT NULL CHECK(status IN ('PENDING','RETRYING','COMPLETE','FAILED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 10),
 recovery_count integer NOT NULL DEFAULT 0 CHECK(recovery_count BETWEEN 0 AND 3),
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 last_result_code varchar(40) NOT NULL DEFAULT 'REVOKE_PENDING',
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(connection_id,credential_version)
);
CREATE INDEX ix_google_revocation_due ON integration_google_revocation(next_attempt_at,id) WHERE status IN ('PENDING','RETRYING');
