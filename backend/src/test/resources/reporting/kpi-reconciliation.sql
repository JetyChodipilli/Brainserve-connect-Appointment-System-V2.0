-- Synthetic, non-PII fixtures. Dates cross midnight in the office zone.
INSERT INTO org_department(id, code, name, created_at, created_by, updated_at, updated_by)
VALUES ('10000000-0000-0000-0000-000000000001', 'KPI_A', 'KPI A', now(), 'fixture', now(), 'fixture'),
       ('10000000-0000-0000-0000-000000000002', 'KPI_B', 'KPI B', now(), 'fixture', now(), 'fixture');
INSERT INTO employee(id, employee_number, first_name, last_name, display_name, official_email,
                     department_id, designation, joining_date, status, created_at, created_by, updated_at, updated_by)
SELECT ('20000000-0000-0000-0000-00000000000' || n)::uuid, 'KPI-' || n, 'Fixture', 'Employee',
       'Fixture Employee ' || n, 'fixture' || n || '@brainserve.in',
       ('10000000-0000-0000-0000-00000000000' || n)::uuid, 'Fixture', '2020-01-01', 'ACTIVE',
       now(), 'fixture', now(), 'fixture' FROM generate_series(1, 2) n;
INSERT INTO iam_user_account(id, email, password_hash, full_name, account_status, created_at, created_by, updated_at, updated_by)
VALUES ('30000000-0000-0000-0000-000000000001', 'kpi-actor@brainserve.in', 'not-a-login', 'Fixture Actor', 'ACTIVE', now(), 'fixture', now(), 'fixture');

DO $$
DECLARE
    n integer;
    seconds integer[] := ARRAY[0, 60, 180, 900, -60, 120];
    checkin timestamptz;
    visit uuid;
    dept integer;
BEGIN
    FOR n IN 1..6 LOOP
        checkin := CASE WHEN n = 1 THEN '2026-05-01 00:00:00+05:30'::timestamptz
                        WHEN n = 6 THEN '2026-05-03 00:00:00+05:30'::timestamptz
                        ELSE '2026-05-02 12:00:00+05:30'::timestamptz END;
        visit := ('40000000-0000-0000-0000-00000000000' || n)::uuid;
        dept := CASE WHEN n = 4 THEN 2 ELSE 1 END;
        INSERT INTO appointment(id, reference_number, idempotency_key, type, status, visitor_name,
            visitor_email, visitor_phone, host_employee_id, routing_department_id, slot_start, slot_end,
            purpose, security_intake_at, security_intake_actor_id, arrival_visitor_name, arrival_purpose,
            created_at, created_by, updated_at, updated_by)
        VALUES (visit, 'KPI-V-' || n, 'kpi-fixture-' || n, 'EMPLOYEE_VISIT', 'COMPLETED', 'Fixture Visitor',
            'visitor@example.invalid', '0000000000', ('20000000-0000-0000-0000-00000000000' || dept)::uuid,
            ('10000000-0000-0000-0000-00000000000' || dept)::uuid, checkin, checkin + interval '1 hour',
            'Fixture', checkin - seconds[n] * interval '1 second', '30000000-0000-0000-0000-000000000001',
            'Fixture Visitor', 'Fixture', now(), 'fixture', now(), 'fixture');
        INSERT INTO visit_access_record(id, appointment_id, visitor_name, badge_number, checked_in_at,
            checked_out_at, processed_by, created_at, created_by, updated_at, updated_by)
        VALUES (gen_random_uuid(), visit, 'Fixture Visitor', 'KPI-B-' || n, checkin,
            CASE WHEN n IN (1, 4) THEN NULL ELSE checkin + interval '1 hour' END,
            'fixture', now(), 'fixture', now(), 'fixture');
    END LOOP;
END;
$$;
-- Arrivals without check-ins must never inflate the wait denominator.
INSERT INTO appointment(id, reference_number, idempotency_key, type, status, visitor_name,
    visitor_email, visitor_phone, host_employee_id, routing_department_id, slot_start, slot_end,
    purpose, security_intake_at, security_intake_actor_id, arrival_visitor_name, arrival_purpose,
    created_at, created_by, updated_at, updated_by)
SELECT gen_random_uuid(), 'KPI-P-' || n, 'kpi-pending-' || n, 'EMPLOYEE_VISIT', 'CANCELLED',
    'Fixture Visitor', 'visitor@example.invalid', '0000000000', '20000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000002', '2026-05-02 13:00+05:30'::timestamptz,
    '2026-05-02 14:00+05:30'::timestamptz, 'Fixture', '2026-05-02 13:00+05:30'::timestamptz,
    '30000000-0000-0000-0000-000000000001', 'Fixture Visitor', 'Fixture',
    now(), 'fixture', now(), 'fixture' FROM generate_series(1, 10) n;
