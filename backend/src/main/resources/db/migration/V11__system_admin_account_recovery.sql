CREATE TABLE iam_account_recovery_request (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES iam_user_account(id),
    recovery_type varchar(20) NOT NULL CHECK (recovery_type IN ('PASSWORD', 'EMAIL')),
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'USED')),
    code_hash varchar(64) UNIQUE,
    approved_by_user_id uuid REFERENCES iam_user_account(id),
    approved_at timestamptz,
    expires_at timestamptz,
    rejected_by_user_id uuid REFERENCES iam_user_account(id),
    rejected_at timestamptz,
    rejection_reason varchar(500),
    used_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_account_recovery_decision CHECK (
        (status = 'PENDING' AND approved_at IS NULL AND rejected_at IS NULL AND used_at IS NULL)
        OR (status = 'APPROVED' AND approved_at IS NOT NULL AND expires_at IS NOT NULL AND code_hash IS NOT NULL)
        OR (status = 'REJECTED' AND rejected_at IS NOT NULL)
        OR (status = 'USED' AND approved_at IS NOT NULL AND used_at IS NOT NULL AND code_hash IS NULL)
    )
);

CREATE INDEX ix_account_recovery_status_created
    ON iam_account_recovery_request(status, created_at);
CREATE INDEX ix_account_recovery_user_type
    ON iam_account_recovery_request(user_id, recovery_type, status);
