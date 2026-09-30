ALTER TABLE appointment ADD COLUMN team_lead_approval_actor_id uuid REFERENCES iam_user_account(id);
ALTER TABLE appointment ADD COLUMN team_lead_decision_at timestamptz;
ALTER TABLE appointment ADD COLUMN team_lead_decision_remarks varchar(500);

CREATE INDEX ix_appointment_team_lead_queue
    ON appointment(host_employee_id, slot_start)
    WHERE status = 'PENDING_TEAM_LEAD_APPROVAL';
