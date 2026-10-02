-- Existing refresh families remain usable only after sign-in: old JWTs have no sid.
-- Preserve original family creation time across later token rotations.
ALTER TABLE iam_refresh_token_session
    ADD COLUMN mfa_verified_at timestamptz,
    ADD COLUMN session_started_at timestamptz;
UPDATE iam_refresh_token_session s
SET session_started_at = family.started_at
FROM (SELECT family_id, min(created_at) AS started_at FROM iam_refresh_token_session GROUP BY family_id) family
WHERE family.family_id = s.family_id;
ALTER TABLE iam_refresh_token_session ALTER COLUMN session_started_at SET NOT NULL;
CREATE INDEX ix_refresh_active_family_owner
    ON iam_refresh_token_session(user_id, family_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE iam_mfa_credential (
    user_id uuid PRIMARY KEY REFERENCES iam_user_account(id),
    version bigint NOT NULL DEFAULT 0,
    secret_ciphertext varchar(512),
    pending_secret_ciphertext varchar(512),
    pending_family_id uuid,
    pending_expires_at timestamptz,
    enrolled_at timestamptz,
    last_accepted_step bigint,
    failure_window_at timestamptz,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    CONSTRAINT ck_mfa_enrollment CHECK ((enrolled_at IS NULL AND secret_ciphertext IS NULL)
        OR (enrolled_at IS NOT NULL AND secret_ciphertext IS NOT NULL AND last_accepted_step IS NOT NULL)),
    CONSTRAINT ck_mfa_pending CHECK ((pending_secret_ciphertext IS NULL AND pending_family_id IS NULL AND pending_expires_at IS NULL)
        OR (pending_secret_ciphertext IS NOT NULL AND pending_family_id IS NOT NULL AND pending_expires_at IS NOT NULL))
);
CREATE TABLE iam_mfa_recovery_code (
    user_id uuid NOT NULL REFERENCES iam_mfa_credential(user_id) ON DELETE CASCADE,
    code_hash varchar(64) NOT NULL,
    PRIMARY KEY (user_id, code_hash)
);
