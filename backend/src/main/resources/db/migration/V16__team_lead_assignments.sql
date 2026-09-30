CREATE TABLE department_team_lead (
    id uuid PRIMARY KEY,
    department_id uuid NOT NULL REFERENCES org_department(id),
    team_lead_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    team_lead_employee_id uuid NOT NULL REFERENCES employee(id),
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
    CONSTRAINT ck_team_lead_end_state CHECK ((active AND ended_at IS NULL AND ended_by_user_id IS NULL)
        OR (NOT active AND ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_active_team_lead_per_department
    ON department_team_lead(department_id) WHERE active;
CREATE UNIQUE INDEX uq_active_department_per_team_lead
    ON department_team_lead(team_lead_user_id) WHERE active;
CREATE UNIQUE INDEX uq_active_department_per_team_lead_employee
    ON department_team_lead(team_lead_employee_id) WHERE active;
CREATE INDEX ix_team_lead_assignment_history
    ON department_team_lead(department_id, assigned_at DESC);
