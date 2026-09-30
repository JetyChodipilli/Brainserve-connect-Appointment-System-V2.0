-- Preserve every archive cycle while allowing one current archived snapshot per account.
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT con.conname
      INTO constraint_name
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
     WHERE rel.relname = 'archived_account'
       AND con.contype = 'u'
       AND pg_get_constraintdef(con.oid) LIKE '%original_user_id%'
     LIMIT 1;
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE archived_account DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE archived_account
    ADD COLUMN recovered_at timestamptz,
    ADD COLUMN recovered_by_user_id uuid REFERENCES iam_user_account(id),
    ADD COLUMN recovered_role varchar(60),
    ADD COLUMN recovered_department_id uuid REFERENCES org_department(id),
    ADD COLUMN recovery_reason varchar(1000);

ALTER TABLE archived_account
    ADD CONSTRAINT ck_archived_account_recovery_complete CHECK (
        (recovered_at IS NULL
            AND recovered_by_user_id IS NULL
            AND recovered_role IS NULL
            AND recovered_department_id IS NULL
            AND recovery_reason IS NULL)
        OR
        (recovered_at IS NOT NULL
            AND recovered_by_user_id IS NOT NULL
            AND recovered_role IS NOT NULL
            AND recovery_reason IS NOT NULL)
    );

CREATE UNIQUE INDEX ux_archived_account_current
    ON archived_account(original_user_id)
    WHERE recovered_at IS NULL;

CREATE INDEX ix_archived_account_recovered
    ON archived_account(recovered_at DESC)
    WHERE recovered_at IS NOT NULL;
