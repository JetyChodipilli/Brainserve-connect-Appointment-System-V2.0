-- The CEO may belong to a department for normal work, but the department is
-- never an HR lifecycle boundary. Reconcile any legacy drift before installing
-- a database-level guard.
UPDATE employee employee
   SET status = 'ACTIVE',
       relieving_date = NULL,
       updated_at = now(),
       updated_by = 'flyway-v46',
       version = employee.version + 1
  FROM iam_user_account account
  JOIN iam_user_role role
    ON role.user_id = account.id
   AND role.role_name = 'ROLE_CEO'
 WHERE (account.employee_id = employee.id
        OR lower(account.email) = lower(employee.official_email))
   AND account.account_status = 'ACTIVE'
   AND account.enabled = true
   AND account.archived = false
   AND (employee.status <> 'ACTIVE' OR employee.relieving_date IS NOT NULL);

CREATE OR REPLACE FUNCTION protect_active_ceo_employee_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM iam_user_account account
          JOIN iam_user_role role
            ON role.user_id = account.id
           AND role.role_name = 'ROLE_CEO'
         WHERE (account.employee_id = OLD.id
                OR lower(account.email) = lower(OLD.official_email))
           AND account.account_status = 'ACTIVE'
           AND account.enabled = true
           AND account.archived = false
    ) THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION
                'CEO_LIFECYCLE_PROTECTED: active CEO employee records cannot be deleted';
        END IF;
        IF NEW.status <> 'ACTIVE' OR NEW.relieving_date IS NOT NULL THEN
            RAISE EXCEPTION
                'CEO_LIFECYCLE_PROTECTED: transfer CEO authority before changing employment status';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_protect_active_ceo_employee_status ON employee;
CREATE TRIGGER trg_protect_active_ceo_employee_status
BEFORE UPDATE OF status, relieving_date ON employee
FOR EACH ROW
EXECUTE FUNCTION protect_active_ceo_employee_lifecycle();

DROP TRIGGER IF EXISTS trg_protect_active_ceo_employee_delete ON employee;
CREATE TRIGGER trg_protect_active_ceo_employee_delete
BEFORE DELETE ON employee
FOR EACH ROW
EXECUTE FUNCTION protect_active_ceo_employee_lifecycle();

COMMENT ON FUNCTION protect_active_ceo_employee_lifecycle() IS
    'Prevents department-scoped HR or any accidental write path from deactivating, terminating, relieving, or deleting the active company CEO employee record.';
