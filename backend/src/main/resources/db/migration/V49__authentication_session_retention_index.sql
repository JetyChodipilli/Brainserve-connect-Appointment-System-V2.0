-- Expired refresh-token hashes are retained briefly for security investigation,
-- then removed by the bounded authentication-session retention worker.
CREATE INDEX IF NOT EXISTS ix_refresh_expires_at
    ON iam_refresh_token_session(expires_at);