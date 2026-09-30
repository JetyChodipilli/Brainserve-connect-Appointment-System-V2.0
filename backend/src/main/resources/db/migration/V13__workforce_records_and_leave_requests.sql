CREATE TABLE employee_leave_request (
    id UUID PRIMARY KEY,
    employee_id UUID NOT NULL REFERENCES employee(id),
    requester_user_id UUID NOT NULL REFERENCES iam_user_account(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    decided_by_user_id UUID REFERENCES iam_user_account(id),
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(120) NOT NULL,
    CONSTRAINT ck_leave_date_range CHECK (end_date >= start_date)
);
CREATE INDEX idx_leave_employee_created ON employee_leave_request(employee_id, created_at DESC);
CREATE INDEX idx_leave_status_created ON employee_leave_request(status, created_at);
CREATE INDEX idx_appointment_slot_start ON appointment(slot_start);
