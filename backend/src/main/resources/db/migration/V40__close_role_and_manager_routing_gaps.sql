-- Enforce exactly one committed role for every account touched by role changes.
-- The check remains deferred so Hibernate can delete and insert the element
-- collection rows in either order inside one atomic transaction.
CREATE OR REPLACE FUNCTION enforce_exactly_one_iam_role()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_user_id uuid;
BEGIN
    checked_user_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END;

    IF EXISTS (SELECT 1 FROM iam_user_account account WHERE account.id = checked_user_id)
       AND (SELECT count(*) FROM iam_user_role role WHERE role.user_id = checked_user_id) <> 1 THEN
        RAISE EXCEPTION 'BrainServe account % must have exactly one effective role', checked_user_id
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.user_id IS DISTINCT FROM NEW.user_id
       AND EXISTS (SELECT 1 FROM iam_user_account account WHERE account.id = OLD.user_id)
       AND (SELECT count(*) FROM iam_user_role role WHERE role.user_id = OLD.user_id) <> 1 THEN
        RAISE EXCEPTION 'BrainServe account % must have exactly one effective role', OLD.user_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS ck_iam_user_exactly_one_role ON iam_user_role;
CREATE CONSTRAINT TRIGGER ck_iam_user_exactly_one_role
AFTER INSERT OR UPDATE OR DELETE ON iam_user_role
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_exactly_one_iam_role();

-- Repair legacy CEO visits that were parked in an older CEO/HR queue when the
-- selected routing department now has an active Manager. Rows without a valid
-- Manager remain untouched and therefore fail closed for manual reconciliation.
UPDATE appointment visit
   SET status = 'PENDING_MANAGER_APPROVAL',
       updated_at = now(),
       updated_by = 'flyway-v40',
       version = version + 1
 WHERE visit.type = 'CEO_VISIT'
   AND visit.status IN ('PENDING_HR_APPROVAL', 'PENDING_CEO_APPROVAL')
   AND EXISTS (
       SELECT 1
         FROM department_manager_assignment assignment
        WHERE assignment.department_id = visit.routing_department_id
          AND assignment.active
   );

-- Replace only the original sample company name. Customer-defined legal names
-- remain unchanged.
UPDATE system_setting
   SET setting_value = 'BrainServe Connect',
       description = 'Public product and company name shown in BrainServe Connect',
       updated_at = now(),
       updated_by = 'flyway-v40',
       version = version + 1
 WHERE setting_key = 'COMPANY.NAME'
   AND setting_value = 'Brain Serve Pvt. Ltd.';
