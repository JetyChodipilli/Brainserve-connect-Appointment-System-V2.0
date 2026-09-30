ALTER TABLE appointment
    ADD COLUMN reception_forward_actor_id uuid REFERENCES iam_user_account(id),
    ADD COLUMN reception_forwarded_at timestamptz,
    ADD COLUMN reception_forward_remarks varchar(500),
    ADD CONSTRAINT ck_appointment_reception_forward CHECK (
        (reception_forwarded_at IS NULL AND reception_forward_actor_id IS NULL)
        OR (reception_forwarded_at IS NOT NULL AND reception_forward_actor_id IS NOT NULL)
    );

CREATE INDEX ix_appointment_reception_forward_queue ON appointment(slot_start)
    WHERE status = 'APPROVED' AND reception_forwarded_at IS NULL;
