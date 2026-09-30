ALTER TABLE iam_user_account
    DROP CONSTRAINT ck_iam_user_account_status;

ALTER TABLE iam_user_account
    ADD CONSTRAINT ck_iam_user_account_status CHECK (
        account_status IN ('ACTIVE', 'PENDING_APPROVAL', 'PENDING_HR_APPROVAL', 'REJECTED', 'DISABLED')
    );
