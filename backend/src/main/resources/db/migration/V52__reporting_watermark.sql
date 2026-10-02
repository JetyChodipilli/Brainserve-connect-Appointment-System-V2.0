-- Revision changes share the business transaction, so rollback cannot invalidate
-- a snapshot and a committed write can never reuse its previous cache namespace.
CREATE TABLE reporting_source_revision (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0)
);
INSERT INTO reporting_source_revision(singleton) VALUES (true);

ALTER TABLE daily_operational_summary ADD COLUMN source_generation bigint;
ALTER TABLE monthly_operational_summary ADD COLUMN source_generation bigint;

CREATE FUNCTION advance_reporting_source_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE reporting_source_revision SET generation = generation + 1 WHERE singleton;
    RETURN NULL;
END;
$$;

-- Statement triggers also cover maintenance and imports that do not publish an
-- application event. Taking the lock before source writes serializes them with
-- the existing summary refresh without locking source rows while reading them.
CREATE TRIGGER reporting_appointment_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON appointment
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_access_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON visit_access_record
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_employee_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON employee
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_work_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON department_work_task
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();
CREATE TRIGGER reporting_department_revision BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON org_department
    FOR EACH STATEMENT EXECUTE FUNCTION advance_reporting_source_revision();

ALTER FUNCTION refresh_daily_operational_summary(date) RENAME TO refresh_daily_operational_summary_v50;
CREATE FUNCTION refresh_daily_operational_summary(target_date date) RETURNS void LANGUAGE plpgsql AS $$
DECLARE source_version bigint;
BEGIN
    SELECT generation INTO source_version FROM reporting_source_revision WHERE singleton FOR UPDATE;
    PERFORM refresh_daily_operational_summary_v50(target_date);
    UPDATE daily_operational_summary SET source_generation = source_version, refreshed_at = clock_timestamp()
     WHERE summary_date = target_date;
END;
$$;

ALTER FUNCTION refresh_monthly_operational_summary(date) RENAME TO refresh_monthly_operational_summary_v50;
CREATE FUNCTION refresh_monthly_operational_summary(target_month date) RETURNS void LANGUAGE plpgsql AS $$
DECLARE source_version bigint;
BEGIN
    SELECT generation INTO source_version FROM reporting_source_revision WHERE singleton FOR UPDATE;
    PERFORM refresh_monthly_operational_summary_v50(target_month);
    UPDATE monthly_operational_summary SET source_generation = source_version, refreshed_at = clock_timestamp()
     WHERE summary_month = date_trunc('month', target_month)::date;
END;
$$;

-- Prior rows remain unknown until an actual refresh, never falsely "fresh"
-- merely because an application migration or a Redis cache write ran.
SELECT refresh_daily_operational_summary((now() AT TIME ZONE 'Asia/Kolkata')::date);
SELECT refresh_monthly_operational_summary((now() AT TIME ZONE 'Asia/Kolkata')::date);
