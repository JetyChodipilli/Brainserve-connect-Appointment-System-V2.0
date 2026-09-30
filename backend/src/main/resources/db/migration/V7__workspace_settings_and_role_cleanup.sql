-- BrainServe has exactly six supported roles. Convert the retired HR Executive authority
-- before the Java enum is loaded so existing installations upgrade safely.
UPDATE iam_user_role
SET role_name = 'ROLE_HR_ADMIN'
WHERE role_name = 'ROLE_HR_EXECUTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM iam_user_role existing
      WHERE existing.user_id = iam_user_role.user_id
        AND existing.role_name = 'ROLE_HR_ADMIN'
  );
DELETE FROM iam_user_role WHERE role_name = 'ROLE_HR_EXECUTIVE';

INSERT INTO system_setting (id, setting_key, setting_value, value_type, description,
                            version, created_at, created_by, updated_at, updated_by)
VALUES
    ('00000000-0000-0000-0000-000000000201', 'COMPANY.NAME', 'Brain Serve Pvt. Ltd.', 'STRING',
     'Public company display name', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000202', 'COMPANY.EMAIL_DOMAIN', 'brainserve.in', 'STRING',
     'Official email domain used by staff accounts', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000203', 'COMPANY.HQ_ADDRESS', 'Hyderabad, Telangana, India', 'STRING',
     'Primary visitor arrival address', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000204', 'COMPANY.SUPPORT_EMAIL', 'support@brainserve.in', 'STRING',
     'Support contact displayed in visitor and security workflows', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000205', 'APPOINTMENT.MAX_ADVANCE_DAYS', '90', 'INTEGER',
     'Maximum number of days an appointment may be booked in advance', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000206', 'APPOINTMENT.CHECK_IN_EARLY_MINUTES', '30', 'INTEGER',
     'How many minutes before the slot a signed QR pass becomes valid', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000207', 'APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END', '120', 'INTEGER',
     'How long an approved QR visitor pass remains valid after the appointment ends', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000208', 'NOTIFICATION.APPOINTMENT_EMAIL_ENABLED', 'true', 'BOOLEAN',
     'Send booking verification and visitor status email', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000209', 'NOTIFICATION.APPROVAL_EMAIL_ENABLED', 'true', 'BOOLEAN',
     'Send HR and CEO approval queue email', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000210', 'NOTIFICATION.SECURITY_ALERT_EMAIL_ENABLED', 'true', 'BOOLEAN',
     'Send security alerts for rejected or invalid access attempts', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000211', 'PRIVACY.CONSENT_VERSION', '2026.1', 'STRING',
     'Visitor privacy notice version recorded with consent', 0, now(), 'flyway', now(), 'flyway')
ON CONFLICT (setting_key) DO NOTHING;
