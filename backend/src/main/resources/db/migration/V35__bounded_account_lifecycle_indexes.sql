CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_iam_user_operational_name_id
    ON iam_user_account (account_status, enabled, archived, full_name, id);
CREATE INDEX IF NOT EXISTS idx_iam_user_full_name_trgm
    ON iam_user_account USING gin (lower(full_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_iam_user_email_trgm
    ON iam_user_account USING gin (lower(email) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_iam_user_role_name_user
    ON iam_user_role (role_name, user_id);

CREATE INDEX IF NOT EXISTS idx_archived_account_archived_id_desc
    ON archived_account (archived_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_archived_account_full_name_trgm
    ON archived_account USING gin (lower(full_name_snapshot) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_archived_account_email_trgm
    ON archived_account USING gin (lower(email_snapshot) gin_trgm_ops);
