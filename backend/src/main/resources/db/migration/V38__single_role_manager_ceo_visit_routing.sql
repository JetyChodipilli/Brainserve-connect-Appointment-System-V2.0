-- A user may have exactly one effective account role. Keep the most privileged
-- known role when repairing legacy rows created before this invariant existed.
WITH ranked_roles AS (
    SELECT user_id, role_name,
           row_number() OVER (
               PARTITION BY user_id
               ORDER BY CASE role_name
                   WHEN 'ROLE_SYSTEM_ADMIN' THEN 1
                   WHEN 'ROLE_CEO' THEN 2
                   WHEN 'ROLE_MANAGER' THEN 3
                   WHEN 'ROLE_HR_ADMIN' THEN 4
                   WHEN 'ROLE_TEAM_LEAD' THEN 5
                   WHEN 'ROLE_EMPLOYEE' THEN 6
                   WHEN 'ROLE_RECEPTIONIST' THEN 7
                   WHEN 'ROLE_SECURITY' THEN 8
                   ELSE 99
               END, role_name
           ) AS position
      FROM iam_user_role
)
DELETE FROM iam_user_role role
 USING ranked_roles ranked
 WHERE role.user_id = ranked.user_id
   AND role.role_name = ranked.role_name
   AND ranked.position > 1;

CREATE UNIQUE INDEX uq_iam_user_single_effective_role
    ON iam_user_role(user_id);

UPDATE department_team_lead assignment
   SET active = false,
       ended_at = now(),
       ended_by_user_id = assignment.assigned_by_user_id,
       updated_at = now(),
       updated_by = 'flyway-v38',
       version = version + 1
 WHERE assignment.active
   AND NOT EXISTS (
       SELECT 1 FROM iam_user_role role
        WHERE role.user_id = assignment.team_lead_user_id
          AND role.role_name = 'ROLE_TEAM_LEAD'
   );

UPDATE department_hr_assignment assignment
   SET active = false,
       ended_at = now(),
       ended_by_user_id = assignment.assigned_by_user_id,
       updated_at = now(),
       updated_by = 'flyway-v38',
       version = version + 1
 WHERE assignment.active
   AND NOT EXISTS (
       SELECT 1 FROM iam_user_role role
        WHERE role.user_id = assignment.hr_user_id
          AND role.role_name = 'ROLE_HR_ADMIN'
   );

CREATE TABLE department_manager_assignment (
    id uuid PRIMARY KEY,
    department_id uuid NOT NULL REFERENCES org_department(id),
    manager_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    manager_employee_id uuid NOT NULL REFERENCES employee(id),
    active boolean NOT NULL DEFAULT true,
    assigned_by_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    assigned_at timestamptz NOT NULL,
    ended_by_user_id uuid REFERENCES iam_user_account(id),
    ended_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_department_manager_end_state CHECK (
        (active AND ended_at IS NULL AND ended_by_user_id IS NULL)
        OR (NOT active AND ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_active_manager_per_department
    ON department_manager_assignment(department_id) WHERE active;
CREATE UNIQUE INDEX uq_active_department_per_manager
    ON department_manager_assignment(manager_user_id) WHERE active;
CREATE UNIQUE INDEX uq_active_department_per_manager_employee
    ON department_manager_assignment(manager_employee_id) WHERE active;
CREATE INDEX ix_department_manager_history
    ON department_manager_assignment(department_id, assigned_at DESC);

ALTER TABLE appointment
    ADD COLUMN manager_approval_actor_id uuid REFERENCES iam_user_account(id),
    ADD COLUMN manager_decision_at timestamptz,
    ADD COLUMN manager_decision_remarks varchar(500),
    ADD CONSTRAINT ck_appointment_manager_decision CHECK (
        (manager_decision_at IS NULL AND manager_approval_actor_id IS NULL)
        OR (manager_decision_at IS NOT NULL AND manager_approval_actor_id IS NOT NULL)
    );

CREATE INDEX ix_appointment_manager_queue
    ON appointment(routing_department_id, slot_start, id)
    WHERE status = 'PENDING_MANAGER_APPROVAL';

ALTER TABLE appointment DROP CONSTRAINT IF EXISTS ex_appointment_host_slot;
ALTER TABLE appointment ADD CONSTRAINT ex_appointment_host_slot
    EXCLUDE USING gist (host_employee_id WITH =, tstzrange(slot_start, slot_end, '[)') WITH &&)
    WHERE (status IN ('PENDING_VERIFICATION','PENDING_SECURITY_INTAKE',
                      'PENDING_RECEPTION_VERIFICATION','PENDING_APPROVAL',
                      'PENDING_HR_APPROVAL','PENDING_TEAM_LEAD_APPROVAL',
                      'PENDING_MANAGER_APPROVAL','PENDING_CEO_APPROVAL','APPROVED',
                      'RESCHEDULED','CHECKED_IN','IN_MEETING'));

UPDATE system_setting
   SET setting_value = 'false',
       description = 'CEO visits route from Reception to the assigned department Manager',
       updated_at = now(),
       updated_by = 'flyway-v38',
       version = version + 1
 WHERE setting_key = 'APPROVAL.CEO_VISIT.REQUIRES_CEO_AFTER_HR';
