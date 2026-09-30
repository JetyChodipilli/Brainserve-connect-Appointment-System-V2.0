ALTER TABLE iam_user_account
    ADD COLUMN rejected_by_user_id uuid,
    ADD COLUMN rejected_at timestamptz,
    ADD CONSTRAINT fk_iam_user_rejected_by_user
        FOREIGN KEY (rejected_by_user_id) REFERENCES iam_user_account(id);

CREATE INDEX ix_iam_user_account_rejected_by_user ON iam_user_account(rejected_by_user_id);
