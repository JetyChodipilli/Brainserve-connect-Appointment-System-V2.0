-- Nullable: old submission cycles are unknown and must never be reconstructed as evidence.
ALTER TABLE department_work_task ADD COLUMN submission_version bigint CHECK (submission_version IS NULL OR submission_version > 0);

CREATE TABLE workboard_preference (
    owner_id uuid PRIMARY KEY REFERENCES iam_user_account(id) ON DELETE CASCADE,
    revision bigint NOT NULL CHECK (revision > 0),
    layout varchar(10) NOT NULL CHECK (layout IN ('LIST','BOARD')),
    density varchar(15) NOT NULL CHECK (density IN ('COMPACT','COMFORTABLE')),
    saved_filters jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(saved_filters)='array' AND jsonb_array_length(saved_filters)<=10),
    updated_at timestamptz NOT NULL
);
CREATE INDEX ix_workboard_department_created ON department_work_task(department_id, created_at, id);
CREATE INDEX ix_workboard_employee_created ON department_work_task(employee_id, created_at, id) WHERE assignee_role='EMPLOYEE';
