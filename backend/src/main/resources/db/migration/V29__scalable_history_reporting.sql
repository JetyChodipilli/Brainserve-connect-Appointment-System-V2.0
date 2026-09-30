-- Scalable read model for long-lived operational history.
-- Existing transactional tables remain the source of truth. Immutable changes are
-- mirrored into range-partitioned history tables so hot workflows never depend on
-- a multi-year table scan.

CREATE TABLE audit_event_history (
    id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    actor_id varchar(120) NOT NULL,
    event_type varchar(100) NOT NULL,
    target_type varchar(80) NOT NULL,
    target_id varchar(120) NOT NULL,
    outcome varchar(20) NOT NULL,
    correlation_id varchar(100),
    details_json jsonb,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE visitor_checkpoint_event (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL,
    appointment_id uuid NOT NULL,
    access_record_id uuid NOT NULL,
    department_id uuid,
    visitor_name varchar(170) NOT NULL,
    badge_number varchar(30) NOT NULL,
    event_type varchar(30) NOT NULL CHECK (event_type IN ('CHECKED_IN', 'CHECKED_OUT')),
    actor_id varchar(120) NOT NULL,
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE workboard_activity_event (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL,
    work_task_id uuid NOT NULL,
    department_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    team_lead_user_id uuid NOT NULL,
    event_type varchar(40) NOT NULL,
    previous_status varchar(30),
    current_status varchar(30) NOT NULL,
    actor_id varchar(120) NOT NULL,
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_audit_history_type_time
    ON audit_event_history(event_type, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_history_target_time
    ON audit_event_history(target_type, target_id, occurred_at DESC);
CREATE INDEX ix_checkpoint_department_time
    ON visitor_checkpoint_event(department_id, occurred_at DESC, id DESC);
CREATE INDEX ix_checkpoint_appointment_time
    ON visitor_checkpoint_event(appointment_id, occurred_at DESC);
CREATE INDEX ix_workboard_activity_department_time
    ON workboard_activity_event(department_id, occurred_at DESC, id DESC);
CREATE INDEX ix_workboard_activity_employee_time
    ON workboard_activity_event(employee_id, occurred_at DESC, id DESC);

CREATE OR REPLACE FUNCTION ensure_brainserve_history_partition(parent_table text, event_month date)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    month_start date := date_trunc('month', event_month)::date;
    month_end date := (date_trunc('month', event_month) + interval '1 month')::date;
    child_table text;
BEGIN
    IF parent_table NOT IN ('audit_event_history', 'visitor_checkpoint_event', 'workboard_activity_event') THEN
        RAISE EXCEPTION 'Unsupported history parent table: %', parent_table;
    END IF;
    child_table := parent_table || '_' || to_char(month_start, 'YYYY_MM');
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                   child_table, parent_table, month_start, month_end);
END;
$$;

DO $$
DECLARE
    month_cursor date := date_trunc('month', LEAST(
        COALESCE((SELECT min(occurred_at)::date FROM audit_event), current_date),
        COALESCE((SELECT min(checked_in_at)::date FROM visit_access_record), current_date),
        COALESCE((SELECT min(created_at)::date FROM department_work_task), current_date)
    ))::date;
    final_month date := (date_trunc('month', current_date) + interval '3 months')::date;
BEGIN
    WHILE month_cursor <= final_month LOOP
        PERFORM ensure_brainserve_history_partition('audit_event_history', month_cursor);
        PERFORM ensure_brainserve_history_partition('visitor_checkpoint_event', month_cursor);
        PERFORM ensure_brainserve_history_partition('workboard_activity_event', month_cursor);
        month_cursor := (month_cursor + interval '1 month')::date;
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION mirror_audit_event_to_history()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ensure_brainserve_history_partition('audit_event_history', NEW.occurred_at::date);
    INSERT INTO audit_event_history(id, occurred_at, actor_id, event_type, target_type, target_id,
                                    outcome, correlation_id, details_json)
    VALUES (NEW.id, NEW.occurred_at, NEW.actor_id, NEW.event_type, NEW.target_type, NEW.target_id,
            NEW.outcome, NEW.correlation_id, NEW.details_json)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_event_history
AFTER INSERT ON audit_event
FOR EACH ROW EXECUTE FUNCTION mirror_audit_event_to_history();

CREATE OR REPLACE FUNCTION mirror_visitor_checkpoint_to_history()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    routed_department uuid;
BEGIN
    SELECT routing_department_id INTO routed_department FROM appointment WHERE id = NEW.appointment_id;
    IF TG_OP = 'INSERT' THEN
        PERFORM ensure_brainserve_history_partition('visitor_checkpoint_event', NEW.checked_in_at::date);
        INSERT INTO visitor_checkpoint_event(occurred_at, appointment_id, access_record_id, department_id,
                                             visitor_name, badge_number, event_type, actor_id, details_json)
        VALUES (NEW.checked_in_at, NEW.appointment_id, NEW.id, routed_department, NEW.visitor_name,
                NEW.badge_number, 'CHECKED_IN', NEW.processed_by,
                jsonb_build_object('processedBy', NEW.processed_by));
    ELSIF OLD.checked_out_at IS NULL AND NEW.checked_out_at IS NOT NULL THEN
        PERFORM ensure_brainserve_history_partition('visitor_checkpoint_event', NEW.checked_out_at::date);
        INSERT INTO visitor_checkpoint_event(occurred_at, appointment_id, access_record_id, department_id,
                                             visitor_name, badge_number, event_type, actor_id, details_json)
        VALUES (NEW.checked_out_at, NEW.appointment_id, NEW.id, routed_department, NEW.visitor_name,
                NEW.badge_number, 'CHECKED_OUT', NEW.updated_by,
                jsonb_build_object('processedBy', NEW.processed_by));
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_visitor_checkpoint_history
AFTER INSERT OR UPDATE OF checked_out_at ON visit_access_record
FOR EACH ROW EXECUTE FUNCTION mirror_visitor_checkpoint_to_history();

CREATE OR REPLACE FUNCTION mirror_workboard_activity_to_history()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    activity_type varchar(40);
    event_time timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        activity_type := 'TASK_CREATED';
        event_time := NEW.created_at;
    ELSIF OLD.team_lead_user_id IS DISTINCT FROM NEW.team_lead_user_id THEN
        activity_type := 'RESPONSIBILITY_REASSIGNED';
        event_time := NEW.updated_at;
    ELSIF OLD.status IS DISTINCT FROM NEW.status THEN
        activity_type := 'STATUS_CHANGED';
        event_time := NEW.updated_at;
    ELSE
        activity_type := 'TASK_UPDATED';
        event_time := NEW.updated_at;
    END IF;
    PERFORM ensure_brainserve_history_partition('workboard_activity_event', event_time::date);
    INSERT INTO workboard_activity_event(occurred_at, work_task_id, department_id, employee_id,
                                         team_lead_user_id, event_type, previous_status, current_status,
                                         actor_id, details_json)
    VALUES (event_time, NEW.id, NEW.department_id, NEW.employee_id, NEW.team_lead_user_id,
            activity_type, CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.status END, NEW.status,
            CASE WHEN TG_OP = 'INSERT' THEN NEW.created_by ELSE NEW.updated_by END,
            jsonb_build_object('title', NEW.title, 'dueDate', NEW.due_date));
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_workboard_activity_history
AFTER INSERT OR UPDATE ON department_work_task
FOR EACH ROW EXECUTE FUNCTION mirror_workboard_activity_to_history();

INSERT INTO audit_event_history(id, occurred_at, actor_id, event_type, target_type, target_id,
                                outcome, correlation_id, details_json)
SELECT id, occurred_at, actor_id, event_type, target_type, target_id, outcome, correlation_id, details_json
FROM audit_event ON CONFLICT DO NOTHING;

INSERT INTO visitor_checkpoint_event(occurred_at, appointment_id, access_record_id, department_id,
                                     visitor_name, badge_number, event_type, actor_id, details_json)
SELECT access.checked_in_at, access.appointment_id, access.id, appointment.routing_department_id,
       access.visitor_name, access.badge_number, 'CHECKED_IN', access.processed_by,
       jsonb_build_object('backfilled', true)
FROM visit_access_record access JOIN appointment ON appointment.id = access.appointment_id;

INSERT INTO visitor_checkpoint_event(occurred_at, appointment_id, access_record_id, department_id,
                                     visitor_name, badge_number, event_type, actor_id, details_json)
SELECT access.checked_out_at, access.appointment_id, access.id, appointment.routing_department_id,
       access.visitor_name, access.badge_number, 'CHECKED_OUT', access.updated_by,
       jsonb_build_object('backfilled', true)
FROM visit_access_record access JOIN appointment ON appointment.id = access.appointment_id
WHERE access.checked_out_at IS NOT NULL;

INSERT INTO workboard_activity_event(occurred_at, work_task_id, department_id, employee_id,
                                     team_lead_user_id, event_type, previous_status, current_status,
                                     actor_id, details_json)
SELECT created_at, id, department_id, employee_id, team_lead_user_id, 'TASK_CREATED', NULL, status,
       created_by, jsonb_build_object('title', title, 'dueDate', due_date, 'backfilled', true)
FROM department_work_task;

CREATE TABLE daily_operational_summary (
    summary_date date NOT NULL,
    scope_type varchar(20) NOT NULL CHECK (scope_type IN ('COMPANY', 'DEPARTMENT')),
    scope_key varchar(80) NOT NULL,
    department_id uuid,
    scheduled_visits bigint NOT NULL DEFAULT 0,
    arrived_visits bigint NOT NULL DEFAULT 0,
    waiting_visits bigint NOT NULL DEFAULT 0,
    approved_visits bigint NOT NULL DEFAULT 0,
    completed_visits bigint NOT NULL DEFAULT 0,
    cancelled_visits bigint NOT NULL DEFAULT 0,
    rejected_visits bigint NOT NULL DEFAULT 0,
    average_wait_seconds bigint NOT NULL DEFAULT 0,
    total_employees bigint NOT NULL DEFAULT 0,
    active_employees bigint NOT NULL DEFAULT 0,
    assigned_work bigint NOT NULL DEFAULT 0,
    in_progress_work bigint NOT NULL DEFAULT 0,
    completed_work bigint NOT NULL DEFAULT 0,
    approved_work bigint NOT NULL DEFAULT 0,
    pending_approvals bigint NOT NULL DEFAULT 0,
    refreshed_at timestamptz NOT NULL,
    PRIMARY KEY (summary_date, scope_type, scope_key)
);

CREATE INDEX ix_daily_summary_department_date
    ON daily_operational_summary(department_id, summary_date DESC);

CREATE TABLE monthly_operational_summary (
    summary_month date NOT NULL CHECK (summary_month = date_trunc('month', summary_month)::date),
    scope_type varchar(20) NOT NULL CHECK (scope_type IN ('COMPANY', 'DEPARTMENT')),
    scope_key varchar(80) NOT NULL,
    department_id uuid,
    scheduled_visits bigint NOT NULL DEFAULT 0,
    arrived_visits bigint NOT NULL DEFAULT 0,
    waiting_visits bigint NOT NULL DEFAULT 0,
    approved_visits bigint NOT NULL DEFAULT 0,
    completed_visits bigint NOT NULL DEFAULT 0,
    cancelled_visits bigint NOT NULL DEFAULT 0,
    rejected_visits bigint NOT NULL DEFAULT 0,
    average_wait_seconds bigint NOT NULL DEFAULT 0,
    joined_employees bigint NOT NULL DEFAULT 0,
    relieved_employees bigint NOT NULL DEFAULT 0,
    assigned_work bigint NOT NULL DEFAULT 0,
    completed_work bigint NOT NULL DEFAULT 0,
    approved_work bigint NOT NULL DEFAULT 0,
    refreshed_at timestamptz NOT NULL,
    PRIMARY KEY (summary_month, scope_type, scope_key)
);

CREATE INDEX ix_monthly_summary_department_month
    ON monthly_operational_summary(department_id, summary_month DESC);

CREATE OR REPLACE FUNCTION refresh_daily_operational_summary(target_date date)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM daily_operational_summary WHERE summary_date = target_date;

    INSERT INTO daily_operational_summary(
        summary_date, scope_type, scope_key, department_id, scheduled_visits, arrived_visits,
        waiting_visits, approved_visits, completed_visits, cancelled_visits, rejected_visits,
        average_wait_seconds, total_employees, active_employees, assigned_work, in_progress_work,
        completed_work, approved_work, pending_approvals, refreshed_at)
    WITH appointment_counts AS (
        SELECT routing_department_id AS department_id,
               count(*) AS scheduled_visits,
               count(*) FILTER (WHERE security_intake_at IS NOT NULL) AS arrived_visits,
               count(*) FILTER (WHERE status LIKE 'PENDING_%') AS waiting_visits,
               count(*) FILTER (WHERE status IN ('APPROVED', 'CHECKED_IN')) AS approved_visits,
               count(*) FILTER (WHERE status = 'COMPLETED') AS completed_visits,
               count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_visits,
               count(*) FILTER (WHERE status = 'REJECTED') AS rejected_visits,
               count(*) FILTER (WHERE status LIKE 'PENDING_%') AS pending_approvals
          FROM appointment
         WHERE timezone('Asia/Kolkata', slot_start)::date = target_date
         GROUP BY routing_department_id
    ), wait_counts AS (
        SELECT appointment.routing_department_id AS department_id,
               COALESCE(avg(extract(epoch FROM (access.checked_in_at - appointment.security_intake_at))), 0)::bigint AS average_wait_seconds
          FROM visit_access_record access JOIN appointment ON appointment.id = access.appointment_id
         WHERE timezone('Asia/Kolkata', access.checked_in_at)::date = target_date
           AND appointment.security_intake_at IS NOT NULL
         GROUP BY appointment.routing_department_id
    ), employee_counts AS (
        SELECT department_id, count(*) AS total_employees,
               count(*) FILTER (WHERE status IN ('ACTIVE', 'ON_LEAVE', 'NOTICE_PERIOD')) AS active_employees
          FROM employee GROUP BY department_id
    ), work_counts AS (
        SELECT department_id,
               count(*) FILTER (WHERE timezone('Asia/Kolkata', created_at)::date = target_date) AS assigned_work,
               count(*) FILTER (WHERE started_at IS NOT NULL
                   AND timezone('Asia/Kolkata', started_at)::date = target_date) AS in_progress_work,
               count(*) FILTER (WHERE completed_at IS NOT NULL
                   AND timezone('Asia/Kolkata', completed_at)::date = target_date) AS completed_work,
               count(*) FILTER (WHERE approved_at IS NOT NULL
                   AND timezone('Asia/Kolkata', approved_at)::date = target_date) AS approved_work
          FROM department_work_task GROUP BY department_id
    ), departments AS (
        SELECT id FROM org_department
        UNION SELECT department_id FROM appointment_counts
        UNION SELECT department_id FROM employee_counts
        UNION SELECT department_id FROM work_counts
    ), department_rows AS (
        SELECT target_date AS summary_date, 'DEPARTMENT'::varchar AS scope_type, departments.id::text AS scope_key,
               departments.id AS department_id,
               COALESCE(a.scheduled_visits, 0) scheduled_visits, COALESCE(a.arrived_visits, 0) arrived_visits,
               COALESCE(a.waiting_visits, 0) waiting_visits, COALESCE(a.approved_visits, 0) approved_visits,
               COALESCE(a.completed_visits, 0) completed_visits, COALESCE(a.cancelled_visits, 0) cancelled_visits,
               COALESCE(a.rejected_visits, 0) rejected_visits, COALESCE(w.average_wait_seconds, 0) average_wait_seconds,
               COALESCE(e.total_employees, 0) total_employees, COALESCE(e.active_employees, 0) active_employees,
               COALESCE(t.assigned_work, 0) assigned_work, COALESCE(t.in_progress_work, 0) in_progress_work,
               COALESCE(t.completed_work, 0) completed_work, COALESCE(t.approved_work, 0) approved_work,
               COALESCE(a.pending_approvals, 0) pending_approvals, now() refreshed_at
          FROM departments
          LEFT JOIN appointment_counts a ON a.department_id = departments.id
          LEFT JOIN wait_counts w ON w.department_id = departments.id
          LEFT JOIN employee_counts e ON e.department_id = departments.id
          LEFT JOIN work_counts t ON t.department_id = departments.id
    )
    SELECT * FROM department_rows
    UNION ALL
    SELECT target_date, 'COMPANY', 'GLOBAL', NULL,
           COALESCE(sum(scheduled_visits), 0), COALESCE(sum(arrived_visits), 0),
           COALESCE(sum(waiting_visits), 0), COALESCE(sum(approved_visits), 0),
           COALESCE(sum(completed_visits), 0), COALESCE(sum(cancelled_visits), 0),
           COALESCE(sum(rejected_visits), 0),
           CASE WHEN COALESCE(sum(arrived_visits), 0) = 0 THEN 0
                ELSE sum(average_wait_seconds * arrived_visits) / sum(arrived_visits) END,
           COALESCE(sum(total_employees), 0), COALESCE(sum(active_employees), 0),
           COALESCE(sum(assigned_work), 0), COALESCE(sum(in_progress_work), 0),
           COALESCE(sum(completed_work), 0), COALESCE(sum(approved_work), 0),
           COALESCE(sum(pending_approvals), 0), now()
      FROM department_rows;
END;
$$;

CREATE OR REPLACE FUNCTION refresh_monthly_operational_summary(target_month date)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    month_start date := date_trunc('month', target_month)::date;
    month_end date := (date_trunc('month', target_month) + interval '1 month')::date;
BEGIN
    DELETE FROM monthly_operational_summary WHERE summary_month = month_start;
    INSERT INTO monthly_operational_summary(
        summary_month, scope_type, scope_key, department_id, scheduled_visits, arrived_visits,
        waiting_visits, approved_visits, completed_visits, cancelled_visits, rejected_visits,
        average_wait_seconds, joined_employees, relieved_employees, assigned_work, completed_work,
        approved_work, refreshed_at)
    WITH appointment_counts AS (
        SELECT routing_department_id AS department_id, count(*) AS scheduled_visits,
               count(*) FILTER (WHERE security_intake_at IS NOT NULL) AS arrived_visits,
               count(*) FILTER (WHERE status LIKE 'PENDING_%') AS waiting_visits,
               count(*) FILTER (WHERE status IN ('APPROVED', 'CHECKED_IN')) AS approved_visits,
               count(*) FILTER (WHERE status = 'COMPLETED') AS completed_visits,
               count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_visits,
               count(*) FILTER (WHERE status = 'REJECTED') AS rejected_visits
          FROM appointment
         WHERE slot_start >= month_start::timestamp AT TIME ZONE 'Asia/Kolkata'
           AND slot_start < month_end::timestamp AT TIME ZONE 'Asia/Kolkata'
         GROUP BY routing_department_id
    ), wait_counts AS (
        SELECT appointment.routing_department_id AS department_id,
               COALESCE(avg(extract(epoch FROM (access.checked_in_at - appointment.security_intake_at))), 0)::bigint AS average_wait_seconds
          FROM visit_access_record access JOIN appointment ON appointment.id = access.appointment_id
         WHERE access.checked_in_at >= month_start::timestamp AT TIME ZONE 'Asia/Kolkata'
           AND access.checked_in_at < month_end::timestamp AT TIME ZONE 'Asia/Kolkata'
           AND appointment.security_intake_at IS NOT NULL
         GROUP BY appointment.routing_department_id
    ), employee_counts AS (
        SELECT department_id,
               count(*) FILTER (WHERE joining_date >= month_start AND joining_date < month_end) AS joined_employees,
               count(*) FILTER (WHERE relieving_date >= month_start AND relieving_date < month_end) AS relieved_employees
          FROM employee GROUP BY department_id
    ), work_counts AS (
        SELECT department_id,
               count(*) FILTER (WHERE created_at >= month_start::timestamp AT TIME ZONE 'Asia/Kolkata'
                                  AND created_at < month_end::timestamp AT TIME ZONE 'Asia/Kolkata') AS assigned_work,
               count(*) FILTER (WHERE completed_at >= month_start::timestamp AT TIME ZONE 'Asia/Kolkata'
                                  AND completed_at < month_end::timestamp AT TIME ZONE 'Asia/Kolkata') AS completed_work,
               count(*) FILTER (WHERE approved_at >= month_start::timestamp AT TIME ZONE 'Asia/Kolkata'
                                  AND approved_at < month_end::timestamp AT TIME ZONE 'Asia/Kolkata') AS approved_work
          FROM department_work_task GROUP BY department_id
    ), departments AS (
        SELECT id FROM org_department
        UNION SELECT department_id FROM appointment_counts
        UNION SELECT department_id FROM employee_counts
        UNION SELECT department_id FROM work_counts
    ), department_rows AS (
        SELECT month_start AS summary_month, 'DEPARTMENT'::varchar AS scope_type,
               departments.id::text AS scope_key, departments.id AS department_id,
               COALESCE(a.scheduled_visits, 0) scheduled_visits, COALESCE(a.arrived_visits, 0) arrived_visits,
               COALESCE(a.waiting_visits, 0) waiting_visits, COALESCE(a.approved_visits, 0) approved_visits,
               COALESCE(a.completed_visits, 0) completed_visits, COALESCE(a.cancelled_visits, 0) cancelled_visits,
               COALESCE(a.rejected_visits, 0) rejected_visits, COALESCE(w.average_wait_seconds, 0) average_wait_seconds,
               COALESCE(e.joined_employees, 0) joined_employees,
               COALESCE(e.relieved_employees, 0) relieved_employees,
               COALESCE(t.assigned_work, 0) assigned_work, COALESCE(t.completed_work, 0) completed_work,
               COALESCE(t.approved_work, 0) approved_work, now() refreshed_at
          FROM departments
          LEFT JOIN appointment_counts a ON a.department_id = departments.id
          LEFT JOIN wait_counts w ON w.department_id = departments.id
          LEFT JOIN employee_counts e ON e.department_id = departments.id
          LEFT JOIN work_counts t ON t.department_id = departments.id
    )
    SELECT * FROM department_rows
    UNION ALL
    SELECT month_start, 'COMPANY', 'GLOBAL', NULL,
           COALESCE(sum(scheduled_visits), 0), COALESCE(sum(arrived_visits), 0),
           COALESCE(sum(waiting_visits), 0), COALESCE(sum(approved_visits), 0),
           COALESCE(sum(completed_visits), 0), COALESCE(sum(cancelled_visits), 0),
           COALESCE(sum(rejected_visits), 0),
           CASE WHEN COALESCE(sum(arrived_visits), 0) = 0 THEN 0
                ELSE sum(average_wait_seconds * arrived_visits) / sum(arrived_visits) END,
           COALESCE(sum(joined_employees), 0), COALESCE(sum(relieved_employees), 0),
           COALESCE(sum(assigned_work), 0), COALESCE(sum(completed_work), 0),
           COALESCE(sum(approved_work), 0), now()
      FROM department_rows;
END;
$$;

DO $$
DECLARE
    summary_day date := date_trunc('month', current_date)::date;
BEGIN
    WHILE summary_day <= current_date LOOP
        PERFORM refresh_daily_operational_summary(summary_day);
        summary_day := summary_day + 1;
    END LOOP;
END;
$$;
SELECT refresh_monthly_operational_summary(current_date);
SELECT refresh_monthly_operational_summary((current_date - interval '1 month')::date);

CREATE TABLE data_retention_policy (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset varchar(60) NOT NULL UNIQUE,
    hot_days integer NOT NULL CHECK (hot_days BETWEEN 1 AND 3650),
    warm_months integer NOT NULL CHECK (warm_months BETWEEN 1 AND 240),
    archive_years integer NOT NULL CHECK (archive_years BETWEEN 1 AND 25),
    enabled boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(120) NOT NULL DEFAULT 'system'
);

INSERT INTO data_retention_policy(dataset, hot_days, warm_months, archive_years) VALUES
    ('AUDIT', 90, 24, 7),
    ('VISITOR_CHECKPOINT', 90, 24, 7),
    ('WORKBOARD_ACTIVITY', 180, 24, 7),
    ('REPORT_EXPORT', 7, 1, 1)
ON CONFLICT (dataset) DO NOTHING;

CREATE TABLE data_archive_manifest (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset varchar(60) NOT NULL,
    partition_name varchar(180) NOT NULL UNIQUE,
    period_start date NOT NULL,
    period_end date NOT NULL,
    row_count bigint NOT NULL DEFAULT 0,
    status varchar(30) NOT NULL CHECK (status IN ('WARM', 'ARCHIVE_ELIGIBLE', 'ARCHIVED')),
    object_key varchar(400),
    checksum_sha256 varchar(64),
    discovered_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz
);

CREATE INDEX ix_archive_manifest_status_period
    ON data_archive_manifest(status, period_end);

CREATE TABLE report_export_job (
    id uuid PRIMARY KEY,
    requested_by_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    requested_role varchar(60) NOT NULL,
    dataset varchar(60) NOT NULL,
    export_format varchar(10) NOT NULL CHECK (export_format IN ('CSV', 'XLSX')),
    filter_from timestamptz NOT NULL,
    filter_to timestamptz NOT NULL,
    department_id uuid,
    status_filter varchar(60),
    query_filter varchar(180),
    status varchar(30) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    object_key varchar(400),
    filename varchar(220),
    row_count bigint NOT NULL DEFAULT 0,
    size_bytes bigint NOT NULL DEFAULT 0,
    error_message varchar(1000),
    expires_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);

CREATE INDEX ix_report_export_user_created
    ON report_export_job(requested_by_user_id, created_at DESC);
CREATE INDEX ix_report_export_queue
    ON report_export_job(status, created_at);
CREATE INDEX ix_report_export_expiry
    ON report_export_job(expires_at) WHERE status = 'COMPLETED';

CREATE INDEX IF NOT EXISTS ix_appointment_history_cursor
    ON appointment(slot_start DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_appointment_department_history_cursor
    ON appointment(routing_department_id, slot_start DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_employee_joining_cursor
    ON employee(joining_date DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_employee_department_joining_cursor
    ON employee(department_id, joining_date DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_termination_history_cursor
    ON employee_termination_request(requested_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_termination_department_history_cursor
    ON employee_termination_request(department_id, requested_at DESC, id DESC);
