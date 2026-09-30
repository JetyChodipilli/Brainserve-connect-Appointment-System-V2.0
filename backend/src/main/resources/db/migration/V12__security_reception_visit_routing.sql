ALTER TABLE appointment
    ADD COLUMN security_intake_actor_id uuid REFERENCES iam_user_account(id),
    ADD COLUMN security_intake_at timestamptz,
    ADD COLUMN arrival_visitor_name varchar(170),
    ADD COLUMN arrival_purpose varchar(1000),
    ADD COLUMN identity_document_type varchar(40),
    ADD COLUMN identity_document_last_four varchar(4),
    ADD COLUMN security_notes varchar(500),
    ADD COLUMN reception_verification_actor_id uuid REFERENCES iam_user_account(id),
    ADD COLUMN reception_verified_at timestamptz,
    ADD COLUMN reception_verification_remarks varchar(500),
    ADD CONSTRAINT ck_appointment_security_intake CHECK (
        (security_intake_at IS NULL AND security_intake_actor_id IS NULL
            AND arrival_visitor_name IS NULL AND arrival_purpose IS NULL)
        OR (security_intake_at IS NOT NULL AND security_intake_actor_id IS NOT NULL
            AND arrival_visitor_name IS NOT NULL AND arrival_purpose IS NOT NULL)
    ),
    ADD CONSTRAINT ck_appointment_reception_verification CHECK (
        (reception_verified_at IS NULL AND reception_verification_actor_id IS NULL)
        OR (reception_verified_at IS NOT NULL AND reception_verification_actor_id IS NOT NULL)
    );

-- Preserve already-reviewed CEO visits while applying the corrected direct CEO route.
UPDATE appointment
SET status = 'PENDING_CEO_APPROVAL', updated_at = now(), updated_by = 'flyway-v12'
WHERE type = 'CEO_VISIT' AND status = 'PENDING_HR_APPROVAL';

ALTER TABLE appointment DROP CONSTRAINT IF EXISTS ex_appointment_host_slot;
ALTER TABLE appointment ADD CONSTRAINT ex_appointment_host_slot
    EXCLUDE USING gist (host_employee_id WITH =, tstzrange(slot_start, slot_end, '[)') WITH &&)
    WHERE (status IN ('PENDING_VERIFICATION','PENDING_SECURITY_INTAKE','PENDING_RECEPTION_VERIFICATION',
                      'PENDING_APPROVAL','PENDING_HR_APPROVAL','PENDING_CEO_APPROVAL','APPROVED',
                      'RESCHEDULED','CHECKED_IN','IN_MEETING'));

CREATE INDEX ix_appointment_security_queue ON appointment(slot_start)
    WHERE status = 'PENDING_SECURITY_INTAKE';
CREATE INDEX ix_appointment_reception_queue ON appointment(slot_start)
    WHERE status = 'PENDING_RECEPTION_VERIFICATION';

UPDATE system_setting
SET setting_value = 'false',
    description = 'CEO visits are routed directly from Reception to CEO approval',
    updated_at = now(), updated_by = 'flyway-v12', version = version + 1
WHERE setting_key = 'APPROVAL.CEO_VISIT.REQUIRES_CEO_AFTER_HR';
