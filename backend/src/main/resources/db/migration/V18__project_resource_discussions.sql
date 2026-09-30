CREATE TABLE project_resource_discussion (
    id uuid PRIMARY KEY,
    requested_by_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    hr_recipient_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    department_id uuid NOT NULL REFERENCES org_department(id),
    project_name varchar(160) NOT NULL,
    required_roles varchar(500) NOT NULL,
    requested_headcount integer NOT NULL CHECK (requested_headcount BETWEEN 1 AND 100),
    priority varchar(20) NOT NULL CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
    preferred_at timestamptz NOT NULL,
    justification varchar(1000) NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN ('REQUESTED', 'NEEDS_INFORMATION', 'SCHEDULED', 'DECLINED', 'COMPLETED')),
    hr_response varchar(1000),
    scheduled_at timestamptz,
    hr_decided_at timestamptz,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);

CREATE INDEX ix_resource_discussion_team_lead ON project_resource_discussion(requested_by_user_id, created_at DESC);
CREATE INDEX ix_resource_discussion_hr_queue ON project_resource_discussion(hr_recipient_user_id, status, created_at DESC);
CREATE INDEX ix_resource_discussion_department ON project_resource_discussion(department_id, created_at DESC);
