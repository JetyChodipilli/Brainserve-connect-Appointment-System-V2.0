-- Keep existing Flyway checksums and metric columns compatible with the prior image.
-- NULL means a historical denominator is unavailable; it must not become a zero.
ALTER TABLE daily_operational_summary
    ADD COLUMN wait_seconds_total numeric,
    ADD COLUMN wait_sample_count bigint CHECK (wait_sample_count >= 0);
ALTER TABLE monthly_operational_summary
    ADD COLUMN wait_seconds_total numeric,
    ADD COLUMN wait_sample_count bigint CHECK (wait_sample_count >= 0);

ALTER FUNCTION refresh_daily_operational_summary(date) RENAME TO refresh_daily_operational_summary_v29;
CREATE FUNCTION refresh_daily_operational_summary(target_date date)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM refresh_daily_operational_summary_v29(target_date);
    UPDATE daily_operational_summary SET wait_seconds_total = 0, wait_sample_count = 0
     WHERE summary_date = target_date;
    WITH samples AS (
        SELECT a.routing_department_id AS department_id,
               extract(epoch FROM (v.checked_in_at - a.security_intake_at)) AS seconds
          FROM visit_access_record v JOIN appointment a ON a.id = v.appointment_id
         WHERE v.checked_in_at >= target_date::timestamp AT TIME ZONE 'Asia/Kolkata'
           AND v.checked_in_at < (target_date + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'
           AND a.security_intake_at IS NOT NULL AND v.checked_in_at >= a.security_intake_at
    ), statistics AS (
        SELECT CASE WHEN grouping(department_id) = 1 THEN 'COMPANY' ELSE 'DEPARTMENT' END AS scope_type,
               CASE WHEN grouping(department_id) = 1 THEN 'GLOBAL' ELSE department_id::text END AS scope_key,
               sum(seconds) AS seconds, count(*) AS samples
          FROM samples GROUP BY GROUPING SETS ((department_id), ())
    )
    UPDATE daily_operational_summary d
       SET wait_seconds_total = coalesce(s.seconds, 0), wait_sample_count = s.samples,
           average_wait_seconds = coalesce(round(s.seconds / NULLIF(s.samples, 0)), 0)::bigint
      FROM statistics s
     WHERE d.summary_date = target_date AND d.scope_type = s.scope_type AND d.scope_key = s.scope_key;
END;
$$;

ALTER FUNCTION refresh_monthly_operational_summary(date) RENAME TO refresh_monthly_operational_summary_v29;
CREATE FUNCTION refresh_monthly_operational_summary(target_month date)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    month_start date := date_trunc('month', target_month)::date;
    month_end date := (month_start + interval '1 month')::date;
BEGIN
    PERFORM refresh_monthly_operational_summary_v29(target_month);
    UPDATE monthly_operational_summary SET wait_seconds_total = 0, wait_sample_count = 0
     WHERE summary_month = month_start;
    WITH samples AS (
        SELECT a.routing_department_id AS department_id,
               extract(epoch FROM (v.checked_in_at - a.security_intake_at)) AS seconds
          FROM visit_access_record v JOIN appointment a ON a.id = v.appointment_id
         WHERE v.checked_in_at >= month_start::timestamp AT TIME ZONE 'Asia/Kolkata'
           AND v.checked_in_at < month_end::timestamp AT TIME ZONE 'Asia/Kolkata'
           AND a.security_intake_at IS NOT NULL AND v.checked_in_at >= a.security_intake_at
    ), statistics AS (
        SELECT CASE WHEN grouping(department_id) = 1 THEN 'COMPANY' ELSE 'DEPARTMENT' END AS scope_type,
               CASE WHEN grouping(department_id) = 1 THEN 'GLOBAL' ELSE department_id::text END AS scope_key,
               sum(seconds) AS seconds, count(*) AS samples
          FROM samples GROUP BY GROUPING SETS ((department_id), ())
    )
    UPDATE monthly_operational_summary d
       SET wait_seconds_total = coalesce(s.seconds, 0), wait_sample_count = s.samples,
           average_wait_seconds = coalesce(round(s.seconds / NULLIF(s.samples, 0)), 0)::bigint
      FROM statistics s
     WHERE d.summary_month = month_start AND d.scope_type = s.scope_type AND d.scope_key = s.scope_key;
END;
$$;

-- Only refresh the live day and month. Older retained aggregates stay explicitly
-- unknown until a reconciled backfill proves the complete source cohort exists.
SELECT refresh_daily_operational_summary((now() AT TIME ZONE 'Asia/Kolkata')::date);
SELECT refresh_monthly_operational_summary((now() AT TIME ZONE 'Asia/Kolkata')::date);
