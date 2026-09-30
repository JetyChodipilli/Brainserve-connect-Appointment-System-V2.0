INSERT INTO org_department (id, code, name, active, version, created_at, created_by, updated_at, updated_by)
VALUES ('00000000-0000-0000-0000-000000000301', 'EXEC', 'Executive Office', true,
        0, now(), 'flyway', now(), 'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO system_setting (id, setting_key, setting_value, value_type, description,
                            version, created_at, created_by, updated_at, updated_by)
VALUES ('00000000-0000-0000-0000-000000000302', 'APPOINTMENT.MIN_LEAD_MINUTES', '10', 'INTEGER',
        'Minimum lead time before a same-day appointment slot can be selected',
        0, now(), 'flyway', now(), 'flyway')
ON CONFLICT (setting_key) DO NOTHING;
