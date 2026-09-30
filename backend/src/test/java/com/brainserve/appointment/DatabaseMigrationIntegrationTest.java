package com.brainserve.appointment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "spring.task.scheduling.enabled=false",
        "aws.s3.access-key=test-access-key",
        "aws.s3.secret-key=test-secret-key",
        "brainserve.bootstrap.system-admin-enabled=false"
})
class DatabaseMigrationIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine")
            .withDatabaseName("brainserve_test")
            .withUsername("brainserve")
            .withPassword("brainserve_test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void applicationStartsWithFlywayValidatedSchema() {
        assertThat(POSTGRES.isRunning()).isTrue();
    }

    @Test
    void governanceLedgerRejectsMutationAndRetentionDatasetsExist() {
        jdbc.update("""
                insert into data_governance_log(action_type, dataset, target_ref, actor, outcome, details_json)
                values ('MIGRATION_TEST', 'AUDIT', 'test', 'integration-test', 'SUCCESS', '{}'::jsonb)
                """);
        String hash = jdbc.queryForObject("""
                select entry_hash from data_governance_log
                 where action_type = 'MIGRATION_TEST'
                """, String.class);
        assertThat(hash).hasSize(64);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("delete from data_governance_log where action_type = 'MIGRATION_TEST'"))
                .hasMessageContaining("append-only");
        Integer governedDatasets = jdbc.queryForObject("""
                select count(*) from data_retention_policy
                 where dataset in ('EMPLOYEE','VISITOR','APPOINTMENT','AUDIT','ESSENTIAL_LOG')
                """, Integer.class);
        assertThat(governedDatasets).isEqualTo(5);
    }
}
