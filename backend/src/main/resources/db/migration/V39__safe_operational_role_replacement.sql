-- Hibernate may transiently insert the new element-collection role before deleting
-- the previous one during a flush. Keep the one-role invariant, but validate it at
-- transaction commit so an atomic role replacement cannot hit a false unique error.
ALTER TABLE iam_user_role
    DROP CONSTRAINT IF EXISTS uq_iam_user_single_effective_role;

DROP INDEX IF EXISTS uq_iam_user_single_effective_role;

ALTER TABLE iam_user_role
    ADD CONSTRAINT uq_iam_user_single_effective_role
    UNIQUE (user_id)
    DEFERRABLE INITIALLY DEFERRED;
