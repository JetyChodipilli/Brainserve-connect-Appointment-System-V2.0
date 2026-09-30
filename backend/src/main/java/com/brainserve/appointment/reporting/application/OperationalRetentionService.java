package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.audit.api.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OperationalRetentionService {
    private final JdbcTemplate jdbc;
    private final GovernanceLedgerService ledger;
    private final AuditService audit;

    public OperationalRetentionService(JdbcTemplate jdbc, GovernanceLedgerService ledger,
                                       AuditService audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Scheduled(cron = "${brainserve.reporting.operational-disposal-cron:0 50 5 * * *}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    @Transactional
    public void applyRetentionPolicies() {
        anonymizeFormerEmployees();
        anonymizeExpiredAppointments();
        deleteExpiredVisitorProfiles();
    }

    void anonymizeFormerEmployees() {
        List<UUID> ids = jdbc.query("""
                update employee source
                   set first_name = 'Former',
                       last_name = 'Employee ' || right(source.id::text, 8),
                       display_name = 'Former employee ' || right(source.id::text, 8),
                       official_email = 'retained+' || source.id::text || '@invalid.brainserve',
                       phone_number = null,
                       designation = 'Retained employment record',
                       retention_anonymized_at = now(),
                       updated_at = now(),
                       updated_by = 'system-retention-worker'
                  from data_retention_policy policy
                 where policy.dataset = 'EMPLOYEE' and policy.enabled
                   and policy.disposal_action = 'ANONYMIZE'
                   and source.retention_anonymized_at is null
                   and source.status in ('RESIGNED', 'TERMINATED', 'INACTIVE')
                   and source.relieving_date is not null
                   and source.relieving_date <= current_date - make_interval(years => policy.archive_years)
                   and exists (
                       select 1 from data_archive_manifest manifest
                        where manifest.dataset = 'EMPLOYEE'
                          and manifest.partition_name =
                              'employee_history_event_' || to_char(source.relieving_date, 'YYYY_MM')
                          and manifest.status in ('VERIFIED', 'DATABASE_REMOVED', 'DISPOSED')
                          and manifest.restore_tested_at is not null
                   )
                   and not exists (
                       select 1 from data_legal_hold hold
                        where hold.released_at is null and hold.dataset = 'EMPLOYEE'
                          and (hold.scope_type = 'DATASET'
                               or (hold.scope_type = 'SUBJECT' and hold.scope_ref = source.id::text)
                               or (hold.scope_type = 'PARTITION'
                                   and hold.scope_ref =
                                       'employee_history_event_' || to_char(source.relieving_date, 'YYYY_MM')))
                   )
                   and source.id in (
                       select candidate.id from employee candidate
                        where candidate.retention_anonymized_at is null
                          and candidate.status in ('RESIGNED', 'TERMINATED', 'INACTIVE')
                        order by candidate.relieving_date, candidate.id
                        limit 500
                   )
                returning source.id
                """, (result, row) -> result.getObject(1, UUID.class));
        for (UUID id : ids) {
            anonymizeArchivedIdentity(id);
            ledger.record("RETAINED_EMPLOYEE_ANONYMIZED", "EMPLOYEE", id.toString(),
                    "SUCCESS", Map.of(
                            "coldArchiveVerified", true,
                            "legalHoldChecked", true,
                            "directIdentifiersRemoved", true
                    ));
        }
        if (!ids.isEmpty()) {
            audit.record("EMPLOYEE_RETENTION_ANONYMIZATION", "DATASET", "EMPLOYEE",
                    "{\"anonymized\":" + ids.size() + ",\"archiveVerified\":true}");
        }
    }

    void anonymizeExpiredAppointments() {
        List<UUID> ids = jdbc.query("""
                update appointment source
                   set visitor_name = 'Archived visitor',
                       visitor_email = 'retained+' || source.id::text || '@invalid.brainserve',
                       visitor_phone = 'REDACTED',
                       visitor_company = null,
                       purpose = 'Retained appointment record',
                       arrival_visitor_name = null,
                       arrival_purpose = null,
                       identity_document_type = null,
                       identity_document_last_four = null,
                       security_notes = null,
                       reception_verification_remarks = null,
                       hr_decision_remarks = null,
                       team_lead_decision_remarks = null,
                       ceo_decision_remarks = null,
                       reception_forward_remarks = null,
                       decision_remarks = null,
                       retention_anonymized_at = now(),
                       updated_at = now(),
                       updated_by = 'system-retention-worker'
                  from data_retention_policy policy
                 where policy.dataset = 'APPOINTMENT' and policy.enabled
                   and policy.disposal_action = 'ANONYMIZE'
                   and source.retention_anonymized_at is null
                   and source.status in ('REJECTED', 'CANCELLED', 'COMPLETED', 'NO_SHOW', 'EXPIRED')
                   and source.slot_end::date <= current_date - make_interval(years => policy.archive_years)
                   and exists (
                       select 1 from data_archive_manifest manifest
                        where manifest.dataset = 'APPOINTMENT'
                          and manifest.partition_name =
                              'appointment_history_event_' || to_char(source.slot_end, 'YYYY_MM')
                          and manifest.status in ('VERIFIED', 'DATABASE_REMOVED', 'DISPOSED')
                          and manifest.restore_tested_at is not null
                   )
                   and not exists (
                       select 1 from data_legal_hold hold
                        where hold.released_at is null and hold.dataset = 'APPOINTMENT'
                          and (hold.scope_type = 'DATASET'
                               or (hold.scope_type = 'SUBJECT' and hold.scope_ref = source.id::text)
                               or (hold.scope_type = 'PARTITION'
                                   and hold.scope_ref =
                                       'appointment_history_event_' || to_char(source.slot_end, 'YYYY_MM')))
                   )
                   and source.id in (
                       select candidate.id from appointment candidate
                        where candidate.retention_anonymized_at is null
                          and candidate.status in ('REJECTED', 'CANCELLED', 'COMPLETED', 'NO_SHOW', 'EXPIRED')
                        order by candidate.slot_end, candidate.id
                        limit 1000
                   )
                returning source.id
                """, (result, row) -> result.getObject(1, UUID.class));
        for (UUID id : ids) {
            ledger.record("RETAINED_APPOINTMENT_ANONYMIZED", "APPOINTMENT", id.toString(),
                    "SUCCESS", Map.of(
                            "coldArchiveVerified", true,
                            "legalHoldChecked", true,
                            "visitorIdentifiersRemoved", true
                    ));
        }
        if (!ids.isEmpty()) {
            audit.record("APPOINTMENT_RETENTION_ANONYMIZATION", "DATASET", "APPOINTMENT",
                    "{\"anonymized\":" + ids.size() + ",\"archiveVerified\":true}");
        }
    }

    void deleteExpiredVisitorProfiles() {
        List<UUID> ids = jdbc.query("""
                delete from visitor source
                 using data_retention_policy policy
                 where policy.dataset = 'VISITOR' and policy.enabled
                   and policy.disposal_action = 'DELETE'
                   and source.restricted = false
                   and source.consented_at::date <= current_date - make_interval(years => policy.archive_years)
                   and exists (
                       select 1 from data_archive_manifest manifest
                        where manifest.dataset = 'VISITOR'
                          and manifest.partition_name =
                              'visitor_history_event_' || to_char(source.consented_at, 'YYYY_MM')
                          and manifest.status in ('VERIFIED', 'DATABASE_REMOVED', 'DISPOSED')
                          and manifest.restore_tested_at is not null
                   )
                   and not exists (
                       select 1 from data_legal_hold hold
                        where hold.released_at is null and hold.dataset = 'VISITOR'
                          and (hold.scope_type = 'DATASET'
                               or (hold.scope_type = 'SUBJECT' and hold.scope_ref = source.id::text)
                               or (hold.scope_type = 'PARTITION'
                                   and hold.scope_ref =
                                       'visitor_history_event_' || to_char(source.consented_at, 'YYYY_MM')))
                   )
                   and source.id in (
                       select candidate.id from visitor candidate
                        where candidate.restricted = false
                        order by candidate.consented_at, candidate.id
                        limit 1000
                   )
                returning source.id
                """, (result, row) -> result.getObject(1, UUID.class));
        for (UUID id : ids) {
            ledger.record("EXPIRED_VISITOR_PROFILE_DELETED", "VISITOR", id.toString(),
                    "SUCCESS", Map.of(
                            "coldArchiveVerified", true,
                            "legalHoldChecked", true,
                            "restrictedRecord", false
                    ));
        }
        if (!ids.isEmpty()) {
            audit.record("VISITOR_RETENTION_PURGE", "DATASET", "VISITOR",
                    "{\"deleted\":" + ids.size() + ",\"archiveVerified\":true}");
        }
    }

    private void anonymizeArchivedIdentity(UUID employeeId) {
        jdbc.update("""
                update iam_user_account
                   set full_name = 'Retained account ' || right(id::text, 8),
                       email = 'retained+' || id::text || '@invalid.brainserve',
                       updated_at = now(), updated_by = 'system-retention-worker'
                 where employee_id = ? and archived = true
                """, employeeId);
        jdbc.update("""
                update archived_account
                   set full_name_snapshot = 'Retained account ' || right(original_user_id::text, 8),
                       email_snapshot = 'retained+' || original_user_id::text || '@invalid.brainserve',
                       updated_at = now(), updated_by = 'system-retention-worker'
                 where employee_id_snapshot = ? and retention_until <= current_date
                """, employeeId);
    }
}
