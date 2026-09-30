-- A BrainServe company has one governing CEO. Historical rejected, disabled or
-- archived CEO identities remain available for audit, but only one ACTIVE or
-- PENDING_APPROVAL account may occupy the company CEO slot.

WITH ranked_governing_ceos AS (
    SELECT account.id,
           row_number() OVER (
               ORDER BY
                   CASE WHEN account.account_status = 'ACTIVE' AND account.enabled THEN 0 ELSE 1 END,
                   CASE WHEN lower(account.email) = 'althuf@brainserve.in' THEN 0 ELSE 1 END,
                   account.approved_at NULLS LAST,
                   account.created_at,
                   account.id
           ) AS governance_rank
      FROM iam_user_account account
      JOIN iam_user_role role ON role.user_id = account.id
     WHERE role.role_name = 'ROLE_CEO'
       AND account.archived = false
       AND account.account_status IN ('ACTIVE', 'PENDING_APPROVAL')
)
UPDATE iam_user_account account
   SET account_status = 'REJECTED',
       enabled = false,
       rejected_at = COALESCE(account.rejected_at, now()),
       updated_at = now(),
       updated_by = 'flyway-v41',
       version = account.version + 1
  FROM ranked_governing_ceos ranked
 WHERE ranked.id = account.id
   AND ranked.governance_rank > 1;

CREATE OR REPLACE FUNCTION enforce_single_governing_ceo()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    governing_ceo_count integer;
BEGIN
    -- Serialize zero-to-one CEO creation as well as approval, rejection and
    -- archival transitions. The deferred trigger sees the transaction's final
    -- account and role state.
    PERFORM pg_advisory_xact_lock(21841, 20260727);

    SELECT count(*)
      INTO governing_ceo_count
      FROM iam_user_account account
      JOIN iam_user_role role ON role.user_id = account.id
     WHERE role.role_name = 'ROLE_CEO'
       AND account.archived = false
       AND account.account_status IN ('ACTIVE', 'PENDING_APPROVAL');

    IF governing_ceo_count > 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            CONSTRAINT = 'uq_single_governing_ceo',
            MESSAGE = 'BrainServe Connect permits only one active or pending CEO account';
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS ck_single_governing_ceo_role ON iam_user_role;
CREATE CONSTRAINT TRIGGER ck_single_governing_ceo_role
AFTER INSERT OR UPDATE OR DELETE ON iam_user_role
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_single_governing_ceo();

DROP TRIGGER IF EXISTS ck_single_governing_ceo_account ON iam_user_account;
CREATE CONSTRAINT TRIGGER ck_single_governing_ceo_account
AFTER UPDATE OF account_status, enabled, archived ON iam_user_account
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_single_governing_ceo();

CREATE INDEX IF NOT EXISTS ix_iam_governing_account_status
    ON iam_user_account (account_status, archived, enabled, created_at, id);
