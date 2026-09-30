package com.brainserve.appointment.reporting;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_LARGE_DATA_TESTS", matches = "true")
class LargeDataHistoryPerformanceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine")
            .withDatabaseName("brainserve_performance")
            .withUsername("brainserve")
            .withPassword("performance-only");

    @BeforeAll
    static void prepareMillionsOfRows() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $$ DECLARE month_cursor date := (date_trunc('month', current_date) - interval '24 months')::date;
                    BEGIN WHILE month_cursor <= current_date LOOP
                      PERFORM ensure_brainserve_history_partition('audit_event_history', month_cursor);
                      PERFORM ensure_brainserve_history_partition('visitor_checkpoint_event', month_cursor);
                      PERFORM ensure_brainserve_history_partition('workboard_activity_event', month_cursor);
                      PERFORM ensure_brainserve_history_partition('employee_history_event', month_cursor);
                      PERFORM ensure_brainserve_history_partition('visitor_history_event', month_cursor);
                      PERFORM ensure_brainserve_history_partition('appointment_history_event', month_cursor);
                      PERFORM ensure_brainserve_history_partition('essential_log_history', month_cursor);
                      month_cursor := (month_cursor + interval '1 month')::date;
                    END LOOP; END $$
                    """);
            statement.execute("""
                    insert into audit_event_history(id, occurred_at, actor_id, event_type, target_type, target_id,
                                                    outcome, correlation_id, details_json)
                    select gen_random_uuid(), now() - ((value % 730) || ' days')::interval
                           - ((value % 86400) || ' seconds')::interval,
                           'load-user-' || (value % 1000), 'LOAD_EVENT_' || (value % 20), 'APPOINTMENT',
                           gen_random_uuid()::text, 'SUCCESS', gen_random_uuid()::text,
                           jsonb_build_object('departmentId', gen_random_uuid())
                      from generate_series(1, 1000000) value
                    """);
            statement.execute("""
                    insert into visitor_checkpoint_event(occurred_at, appointment_id, access_record_id, department_id,
                                                         visitor_name, badge_number, event_type, actor_id)
                    select now() - ((value % 730) || ' days')::interval - ((value % 86400) || ' seconds')::interval,
                           gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'Visitor ' || value,
                           'B-' || value, case when value % 2 = 0 then 'CHECKED_IN' else 'CHECKED_OUT' end,
                           'security-' || (value % 100)
                      from generate_series(1, 1000000) value
                    """);
            statement.execute("""
                    insert into workboard_activity_event(occurred_at, work_task_id, department_id, employee_id,
                                                         team_lead_user_id, event_type, previous_status,
                                                         current_status, actor_id)
                    select now() - ((value % 730) || ' days')::interval - ((value % 86400) || ' seconds')::interval,
                           gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
                           'STATUS_CHANGED', 'ASSIGNED', 'IN_PROGRESS', 'employee-' || (value % 1000)
                      from generate_series(1, 1000000) value
                    """);
            statement.execute("""
                    insert into essential_log_history(occurred_at, subject_id, event_type, row_data)
                    select now() - ((value % 730) || ' days')::interval
                           - ((value % 86400) || ' seconds')::interval,
                           gen_random_uuid(), 'LOAD_TEST', jsonb_build_object('sequence', value)
                      from generate_series(1, 500000) value
                    """);
            statement.execute("analyze audit_event_history");
            statement.execute("analyze visitor_checkpoint_event");
            statement.execute("analyze workboard_activity_event");
            statement.execute("analyze essential_log_history");
        }
    }

    @Test
    void keysetQueriesPrunePartitionsAcrossThreeMillionHistoryRows() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                String plan = plan(statement, """
                        select id, occurred_at, event_type from audit_event_history
                         where occurred_at >= date_trunc('month', now())
                           and occurred_at < date_trunc('month', now()) + interval '1 month'
                         order by occurred_at desc, id desc limit 50
                        """);
                assertTrue(plan.contains("audit_event_history_"), plan);
                assertTrue(plan.contains("Limit"), plan);
                assertTrue(plan.contains("Index") || plan.contains("Bitmap"), plan);

                assertTrue(plan(statement, """
                        select id, occurred_at from visitor_checkpoint_event
                         where occurred_at >= date_trunc('month', now())
                         order by occurred_at desc, id desc limit 50
                        """).contains("visitor_checkpoint_event_"));
                assertTrue(plan(statement, """
                        select id, occurred_at from workboard_activity_event
                         where occurred_at >= date_trunc('month', now())
                         order by occurred_at desc, id desc limit 50
                        """).contains("workboard_activity_event_"));
                assertTrue(plan(statement, """
                        select id, occurred_at from essential_log_history
                         where occurred_at >= date_trunc('month', now())
                         order by occurred_at desc, id desc limit 50
                        """).contains("essential_log_history_"));
            }
        });
    }

    private static String plan(Statement statement, String query) throws Exception {
        StringBuilder output = new StringBuilder();
        try (ResultSet result = statement.executeQuery("explain (analyze, buffers, format text) " + query)) {
            while (result.next()) output.append(result.getString(1)).append('\n');
        }
        return output.toString();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
