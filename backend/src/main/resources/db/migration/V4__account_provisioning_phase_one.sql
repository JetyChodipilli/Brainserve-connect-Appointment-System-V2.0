ALTER TABLE iam_user_account
    ADD COLUMN full_name varchar(170),
    ADD COLUMN account_status varchar(40),
    ADD COLUMN created_by_user_id uuid,
    ADD COLUMN approved_by_user_id uuid,
    ADD COLUMN approved_at timestamptz;

UPDATE iam_user_account
SET full_name = split_part(email, '@', 1),
    account_status = CASE WHEN enabled THEN 'ACTIVE' ELSE 'DISABLED' END;

ALTER TABLE iam_user_account
    ALTER COLUMN full_name SET NOT NULL,
    ALTER COLUMN account_status SET NOT NULL,
    ADD CONSTRAINT ck_iam_user_account_status CHECK (
        account_status IN ('ACTIVE', 'PENDING_APPROVAL', 'REJECTED', 'DISABLED')
    ),
    ADD CONSTRAINT fk_iam_user_created_by_user FOREIGN KEY (created_by_user_id) REFERENCES iam_user_account(id),
    ADD CONSTRAINT fk_iam_user_approved_by_user FOREIGN KEY (approved_by_user_id) REFERENCES iam_user_account(id);

CREATE INDEX ix_iam_user_account_status ON iam_user_account(account_status);
CREATE INDEX ix_iam_user_account_created_by_user ON iam_user_account(created_by_user_id);
CREATE INDEX ix_iam_user_account_approved_by_user ON iam_user_account(approved_by_user_id);
