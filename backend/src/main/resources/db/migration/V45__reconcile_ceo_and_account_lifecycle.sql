-- Compensate for V43 without changing applied history.
-- An independently approved active CEO always wins over an identity revived by
-- V43. Extra governing identities are disabled first so the singleton trigger
-- remains satisfied throughout this compensating migration.
WITH governing_ceos AS (
    SELECT account.id,
           row_number() OVER (
               ORDER BY
                   CASE WHEN account.updated_by IS DISTINCT FROM 'flyway-v43' THEN 0 ELSE 1 END,
                   account.approved_at DESC NULLS LAST,
                   account.created_at,
                   account.id
           ) AS governance_rank
      FROM iam_user_account account
      JOIN iam_user_role role ON role.user_id = account.id
     WHERE role.role_name = 'ROLE_CEO'
       AND account.archived = false
       AND account.enabled = true
       AND account.account_status = 'ACTIVE'
)
UPDATE iam_user_account account
   SET account_status = 'REJECTED',
       enabled = false,
       rejected_at = COALESCE(account.rejected_at, now()),
       updated_at = now(),
       updated_by = 'flyway-v45',
       version = account.version + 1
  FROM governing_ceos ranked
 WHERE ranked.id = account.id
   AND ranked.governance_rank > 1;

-- V43 inferred CEO authority from a stale manager assignment and could revive a
-- rejected or disabled account. Inference is not approval: even when it is the
-- only remaining CEO identity, return it to a disabled review state. The System
-- Admin must explicitly approve it or execute the governed succession workflow.
UPDATE iam_user_account account
   SET account_status = 'PENDING_APPROVAL',
       enabled = false,
       approved_at = null,
       approved_by_user_id = null,
       rejected_by_user_id = null,
       rejected_at = null,
       updated_at = now(),
       updated_by = 'flyway-v45-review',
       version = account.version + 1
  FROM iam_user_role role
 WHERE role.user_id = account.id
   AND role.role_name = 'ROLE_CEO'
   AND account.archived = false
   AND account.enabled = true
   AND account.account_status = 'ACTIVE'
   AND account.updated_by = 'flyway-v43';

UPDATE iam_refresh_token_session session
   SET revoked_at = COALESCE(session.revoked_at, now()),
       updated_at = now(),
       updated_by = 'flyway-v45-review',
       version = session.version + 1
 WHERE session.revoked_at IS NULL
   AND session.user_id IN (
       SELECT account.id
         FROM iam_user_account account
         JOIN iam_user_role role ON role.user_id = account.id
        WHERE role.role_name = 'ROLE_CEO'
          AND account.account_status = 'PENDING_APPROVAL'
          AND account.enabled = false
          AND account.updated_by = 'flyway-v45-review'
   );

-- Archived employee-linked identities must not remain eligible as hosts,
-- assignees, or active workforce members.
UPDATE employee employee
   SET status = 'INACTIVE',
       updated_at = now(),
       updated_by = 'flyway-v45',
       version = employee.version + 1
  FROM iam_user_account account
 WHERE account.employee_id = employee.id
   AND account.archived = true
   AND employee.status NOT IN ('RESIGNED', 'TERMINATED', 'INACTIVE');

-- End stale leadership ownership for archived or non-active accounts.
UPDATE department_manager_assignment assignment
   SET active = false,
       ended_at = COALESCE(assignment.ended_at, now()),
       ended_by_user_id = COALESCE(assignment.ended_by_user_id, assignment.assigned_by_user_id),
       updated_at = now(),
       updated_by = 'flyway-v45',
       version = assignment.version + 1
  FROM iam_user_account account
 WHERE assignment.manager_user_id = account.id
   AND assignment.active
   AND (account.archived OR NOT account.enabled OR account.account_status <> 'ACTIVE');

UPDATE department_hr_assignment assignment
   SET active = false,
       ended_at = COALESCE(assignment.ended_at, now()),
       ended_by_user_id = COALESCE(assignment.ended_by_user_id, assignment.assigned_by_user_id),
       updated_at = now(),
       updated_by = 'flyway-v45',
       version = assignment.version + 1
  FROM iam_user_account account
 WHERE assignment.hr_user_id = account.id
   AND assignment.active
   AND (account.archived OR NOT account.enabled OR account.account_status <> 'ACTIVE');

UPDATE department_team_lead assignment
   SET active = false,
       ended_at = COALESCE(assignment.ended_at, now()),
       ended_by_user_id = COALESCE(assignment.ended_by_user_id, assignment.assigned_by_user_id),
       updated_at = now(),
       updated_by = 'flyway-v45',
       version = assignment.version + 1
  FROM iam_user_account account
 WHERE assignment.team_lead_user_id = account.id
   AND assignment.active
   AND (account.archived OR NOT account.enabled OR account.account_status <> 'ACTIVE');

ALTER TABLE internal_call_notification
    ADD COLUMN IF NOT EXISTS delivery_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_delivery_attempt_at timestamptz,
    ADD COLUMN IF NOT EXISTS kafka_published_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_delivery_error varchar(240);

UPDATE internal_call_notification
   SET next_delivery_attempt_at = COALESCE(next_delivery_attempt_at, sent_at)
 WHERE delivery_status IN ('QUEUED', 'FAILED');

CREATE INDEX IF NOT EXISTS ix_internal_call_delivery_retry
    ON internal_call_notification(delivery_status, next_delivery_attempt_at, sent_at)
    WHERE delivery_status IN ('QUEUED', 'FAILED');

-- Public visitor retries must return the original record rather than create
-- duplicates. Existing rows receive a stable migration-only key.
ALTER TABLE visitor
    ADD COLUMN IF NOT EXISTS idempotency_key varchar(100);

UPDATE visitor
   SET idempotency_key = 'legacy-' || id::text
 WHERE idempotency_key IS NULL;

ALTER TABLE visitor
    ALTER COLUMN idempotency_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_visitor_idempotency_key
    ON visitor(idempotency_key);
