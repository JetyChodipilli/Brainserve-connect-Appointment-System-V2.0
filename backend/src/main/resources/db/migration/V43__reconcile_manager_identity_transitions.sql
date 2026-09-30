-- Reconcile legacy cases where a department Manager assignment was saved but
-- the same identity still carried ROLE_CEO. The assignment is treated as the
-- approved operational intent only when another non-archived CEO identity
-- exists, so this migration never leaves the company without a governing CEO.

CREATE TEMP TABLE v43_manager_identity_conflict ON COMMIT DROP AS
SELECT assignment.manager_user_id AS user_id,
       assignment.manager_employee_id AS employee_id,
       assignment.department_id
  FROM department_manager_assignment assignment
  JOIN iam_user_account account ON account.id = assignment.manager_user_id
  JOIN iam_user_role role ON role.user_id = account.id
  JOIN employee employee ON employee.id = assignment.manager_employee_id
 WHERE assignment.active
   AND role.role_name = 'ROLE_CEO'
   AND account.employee_id = assignment.manager_employee_id
   AND account.archived = false
   AND employee.status NOT IN ('RESIGNED', 'TERMINATED')
   AND EXISTS (
       SELECT 1
         FROM iam_user_account successor
         JOIN iam_user_role successor_role ON successor_role.user_id = successor.id
        WHERE successor.id <> account.id
          AND successor_role.role_name = 'ROLE_CEO'
          AND successor.archived = false
   );

CREATE TEMP TABLE v43_ceo_successor ON COMMIT DROP AS
SELECT DISTINCT ON (conflict.user_id)
       conflict.user_id AS former_ceo_user_id,
       successor.id AS successor_user_id
  FROM v43_manager_identity_conflict conflict
  JOIN iam_user_account successor ON successor.id <> conflict.user_id
  JOIN iam_user_role successor_role ON successor_role.user_id = successor.id
 WHERE successor_role.role_name = 'ROLE_CEO'
   AND successor.archived = false
 ORDER BY conflict.user_id,
          CASE
              WHEN successor.account_status = 'ACTIVE' AND successor.enabled THEN 0
              WHEN successor.account_status = 'PENDING_APPROVAL' THEN 1
              ELSE 2
          END,
          successor.created_at DESC,
          successor.id;

-- A CEO rejected only because V41 preserved the earlier identity becomes the
-- governing CEO before the former CEO is activated as Manager. Both constraint
-- checks are deferred and observe only the final, single-CEO transaction state.
UPDATE iam_user_account successor
   SET account_status = 'ACTIVE',
       enabled = true,
       rejected_by_user_id = NULL,
       rejected_at = NULL,
       failed_login_count = 0,
       locked_until = NULL,
       updated_at = now(),
       updated_by = 'flyway-v43',
       version = successor.version + 1
  FROM v43_ceo_successor choice
 WHERE successor.id = choice.successor_user_id;

DELETE FROM iam_user_role role
 USING v43_manager_identity_conflict conflict
 WHERE role.user_id = conflict.user_id;

INSERT INTO iam_user_role(user_id, role_name)
SELECT conflict.user_id, 'ROLE_MANAGER'
  FROM v43_manager_identity_conflict conflict;

UPDATE iam_user_account account
   SET account_status = 'ACTIVE',
       enabled = true,
       rejected_by_user_id = NULL,
       rejected_at = NULL,
       failed_login_count = 0,
       locked_until = NULL,
       updated_at = now(),
       updated_by = 'flyway-v43',
       version = account.version + 1
  FROM v43_manager_identity_conflict conflict
 WHERE account.id = conflict.user_id;

DELETE FROM iam_user_permission_grant permission
 USING v43_manager_identity_conflict conflict
 WHERE permission.user_id = conflict.user_id;

DELETE FROM iam_user_permission_deny permission
 USING v43_manager_identity_conflict conflict
 WHERE permission.user_id = conflict.user_id;

UPDATE employee employee
   SET department_id = conflict.department_id,
       designation = 'Department Manager',
       status = 'ACTIVE',
       relieving_date = NULL,
       updated_at = now(),
       updated_by = 'flyway-v43',
       version = employee.version + 1
  FROM v43_manager_identity_conflict conflict
 WHERE employee.id = conflict.employee_id;

UPDATE department_team_lead assignment
   SET active = false,
       ended_at = now(),
       ended_by_user_id = assignment.assigned_by_user_id,
       updated_at = now(),
       updated_by = 'flyway-v43',
       version = assignment.version + 1
  FROM v43_manager_identity_conflict conflict
 WHERE assignment.active
   AND assignment.team_lead_user_id = conflict.user_id;

UPDATE department_hr_assignment assignment
   SET active = false,
       ended_at = now(),
       ended_by_user_id = assignment.assigned_by_user_id,
       updated_at = now(),
       updated_by = 'flyway-v43',
       version = assignment.version + 1
  FROM v43_manager_identity_conflict conflict
 WHERE assignment.active
   AND assignment.hr_user_id = conflict.user_id;

UPDATE iam_refresh_token_session session
   SET revoked_at = COALESCE(session.revoked_at, now()),
       updated_at = now(),
       updated_by = 'flyway-v43',
       version = session.version + 1
 WHERE session.user_id IN (
       SELECT conflict.user_id FROM v43_manager_identity_conflict conflict
       UNION
       SELECT successor.successor_user_id FROM v43_ceo_successor successor
   )
   AND session.revoked_at IS NULL;

-- Enforce the identity/employee/assignment agreement at commit time. This
-- catches SQL imports or future service regressions that try to leave an active
-- Manager assignment attached to a CEO, disabled account, inactive employee,
-- different employee ID, or different department.
CREATE OR REPLACE FUNCTION validate_active_manager_identity(checked_user_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF checked_user_id IS NULL THEN
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM department_manager_assignment assignment
         WHERE assignment.manager_user_id = checked_user_id
           AND assignment.active
    ) AND NOT EXISTS (
        SELECT 1
          FROM department_manager_assignment assignment
          JOIN iam_user_account account ON account.id = assignment.manager_user_id
          JOIN iam_user_role role ON role.user_id = account.id
          JOIN employee employee ON employee.id = assignment.manager_employee_id
         WHERE assignment.manager_user_id = checked_user_id
           AND assignment.active
           AND role.role_name = 'ROLE_MANAGER'
           AND account.employee_id = assignment.manager_employee_id
           AND account.account_status = 'ACTIVE'
           AND account.enabled
           AND account.archived = false
           AND employee.status = 'ACTIVE'
           AND employee.department_id = assignment.department_id
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'ck_active_manager_identity_consistency',
            MESSAGE = 'Active Manager assignment, account role, employee status and department must agree';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_active_manager_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    checked_user_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'department_manager_assignment' THEN
        checked_user_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.manager_user_id ELSE NEW.manager_user_id END;
    ELSIF TG_TABLE_NAME = 'iam_user_role' THEN
        checked_user_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END;
    ELSIF TG_TABLE_NAME = 'iam_user_account' THEN
        checked_user_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    ELSIF TG_TABLE_NAME = 'employee' THEN
        SELECT account.id
          INTO checked_user_id
          FROM iam_user_account account
         WHERE account.employee_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    END IF;

    PERFORM validate_active_manager_identity(checked_user_id);
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS ck_active_manager_identity_assignment ON department_manager_assignment;
CREATE CONSTRAINT TRIGGER ck_active_manager_identity_assignment
AFTER INSERT OR UPDATE OR DELETE ON department_manager_assignment
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_active_manager_identity();

DROP TRIGGER IF EXISTS ck_active_manager_identity_role ON iam_user_role;
CREATE CONSTRAINT TRIGGER ck_active_manager_identity_role
AFTER INSERT OR UPDATE OR DELETE ON iam_user_role
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_active_manager_identity();

DROP TRIGGER IF EXISTS ck_active_manager_identity_account ON iam_user_account;
CREATE CONSTRAINT TRIGGER ck_active_manager_identity_account
AFTER UPDATE OF account_status, enabled, archived, employee_id ON iam_user_account
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_active_manager_identity();

DROP TRIGGER IF EXISTS ck_active_manager_identity_employee ON employee;
CREATE CONSTRAINT TRIGGER ck_active_manager_identity_employee
AFTER UPDATE OF status, department_id ON employee
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_active_manager_identity();
