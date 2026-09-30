INSERT INTO org_department (id, code, name, active, version, created_at, created_by, updated_at, updated_by)
VALUES ('00000000-0000-0000-0000-000000000401', 'HR', 'Human Resources', true,
        0, now(), 'flyway', now(), 'flyway')
ON CONFLICT (code) DO UPDATE SET active = true, updated_at = now(), updated_by = 'flyway';
