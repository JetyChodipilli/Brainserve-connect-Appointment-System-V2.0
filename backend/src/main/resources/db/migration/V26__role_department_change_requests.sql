CREATE TABLE role_department_change_request (
    id uuid PRIMARY KEY,
    requester_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    requester_employee_id uuid REFERENCES employee(id),
    requester_role varchar(30) NOT NULL,
    from_department_id uuid REFERENCES org_department(id),
    target_department_id uuid NOT NULL REFERENCES org_department(id),
    target_occupant_user_id uuid REFERENCES iam_user_account(id),
    target_occupant_employee_id uuid REFERENCES employee(id),
    reason varchar(500) NOT NULL,
    profile_phone_number varchar(30),
    profile_designation varchar(120),
    profile_joining_date date,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    requested_at timestamptz NOT NULL,
    decided_by_user_id uuid REFERENCES iam_user_account(id),
    decided_at timestamptz,
    resolution varchar(20),
    decision_note varchar(500),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_role_department_change_role CHECK (requester_role IN ('HR_ADMIN', 'TEAM_LEAD')),
    CONSTRAINT ck_role_department_change_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_role_department_change_resolution CHECK (resolution IS NULL OR resolution IN ('MOVE', 'REPLACE', 'SWAP')),
    CONSTRAINT ck_role_department_change_decision CHECK (
        (status = 'PENDING' AND decided_by_user_id IS NULL AND decided_at IS NULL AND resolution IS NULL)
        OR (status = 'CANCELLED' AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL)
        OR (status = 'REJECTED' AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL AND decision_note IS NOT NULL)
        OR (status = 'APPROVED' AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL AND resolution IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_pending_role_department_change_requester
    ON role_department_change_request(requester_user_id) WHERE status = 'PENDING';
CREATE INDEX ix_role_department_change_approvals
    ON role_department_change_request(requester_role, status, target_department_id, requested_at);
CREATE INDEX ix_role_department_change_history
    ON role_department_change_request(requester_user_id, requested_at DESC);
