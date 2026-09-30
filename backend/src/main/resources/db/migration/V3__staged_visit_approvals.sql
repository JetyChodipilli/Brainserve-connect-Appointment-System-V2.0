ALTER TABLE appointment
    ADD COLUMN registered_by_user_id uuid,
    ADD COLUMN hr_approval_actor_id uuid,
    ADD COLUMN hr_decision_at timestamptz,
    ADD COLUMN hr_decision_remarks varchar(500),
    ADD COLUMN ceo_approval_actor_id uuid,
    ADD COLUMN ceo_decision_at timestamptz,
    ADD COLUMN ceo_decision_remarks varchar(500);

ALTER TABLE appointment DROP CONSTRAINT IF EXISTS ex_appointment_host_slot;
ALTER TABLE appointment ADD CONSTRAINT ex_appointment_host_slot
    EXCLUDE USING gist (host_employee_id WITH =, tstzrange(slot_start, slot_end, '[)') WITH &&)
    WHERE (status IN ('PENDING_VERIFICATION','PENDING_APPROVAL','PENDING_HR_APPROVAL',
                      'PENDING_CEO_APPROVAL','APPROVED','RESCHEDULED','CHECKED_IN','IN_MEETING'));

CREATE INDEX ix_appointment_hr_queue ON appointment(slot_start)
    WHERE status = 'PENDING_HR_APPROVAL';
CREATE INDEX ix_appointment_ceo_queue ON appointment(slot_start)
    WHERE status = 'PENDING_CEO_APPROVAL';

INSERT INTO system_setting (id, setting_key, setting_value, value_type, description,
                            version, created_at, created_by, updated_at, updated_by)
VALUES
    ('00000000-0000-0000-0000-000000000104', 'APPROVAL.INTERVIEW.REQUIRES_HR', 'true', 'BOOLEAN',
     'Interview requests require HR approval', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000105', 'APPROVAL.CEO_VISIT.REQUIRES_CEO_AFTER_HR', 'true', 'BOOLEAN',
     'CEO visits require CEO approval after HR approval', 0, now(), 'flyway', now(), 'flyway');
