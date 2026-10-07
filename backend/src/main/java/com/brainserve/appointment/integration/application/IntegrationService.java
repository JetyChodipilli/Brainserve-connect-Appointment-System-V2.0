package com.brainserve.appointment.integration.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Fixed simulator adapters only. Business writers capture events; this service never performs network I/O. */
@Service
public class IntegrationService {
    private static final int PAGE_SIZE = 20;
    private static final Set<String> APPOINTMENT_STATUSES = Set.of("DRAFT","PENDING_VERIFICATION","PENDING_SECURITY_INTAKE",
            "PENDING_RECEPTION_VERIFICATION","PENDING_APPROVAL","PENDING_HR_APPROVAL","PENDING_TEAM_LEAD_APPROVAL",
            "PENDING_MANAGER_APPROVAL","PENDING_CEO_APPROVAL","APPROVED","REJECTED","RESCHEDULE_REQUESTED","RESCHEDULED",
            "CANCELLED","CHECKED_IN","IN_MEETING","CHECKED_OUT","COMPLETED","NO_SHOW","EXPIRED");
    private static final Set<String> APPOINTMENT_TYPES = Set.of("EMPLOYEE_VISIT","HR_VISIT","CEO_VISIT","INTERVIEW","EMERGENCY",
            "VENDOR_VISIT","CLIENT_MEETING","SERVICE_VISIT","DELIVERY","OTHER");
    private final JdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final SensitiveStringConverter secrets;
    private final AuditService audit;
    private final ObjectMapper json;
    private final RowMapper<IntegrationModels.Connection> connections = this::connection;
    private final RowMapper<IntegrationModels.Delivery> deliveries = this::delivery;

    public IntegrationService(JdbcTemplate jdbc, CurrentAccountAuthority authority, SensitiveStringConverter secrets,
                              AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc; this.authority = authority; this.secrets = secrets; this.audit = audit; this.json = json;
    }

    @Transactional(readOnly = true)
    public List<IntegrationModels.Connection> connections(UUID actor) {
        requireAdmin(actor, false);
        return jdbc.query("select * from integration_connection where owner_id=? order by created_at desc,id limit 100", connections, actor);
    }

    @Transactional
    public IntegrationModels.Connection create(UUID actor, IntegrationModels.Create command) {
        requireAdmin(actor, true);
        if (command == null || command.requestId() == null || command.provider() == null) throw invalid();
        credential(command.credential(), command.credentialExpiresAt());
        String label = label(command.label());
        requestLock(command.requestId());
        List<IntegrationModels.Connection> existing = jdbc.query("select * from integration_connection where request_id=?", connections, command.requestId());
        if (!existing.isEmpty()) {
            var current = existing.getFirst();
            if (!current.ownerId().equals(actor) || current.provider() != command.provider() || !current.label().equals(label)
                    || !current.credentialExpiresAt().equals(command.credentialExpiresAt().truncatedTo(ChronoUnit.MICROS)) || !sameCredential(command.credential(),current.id())) throw conflict();
            return current;
        }
        jdbc.queryForObject("select pg_advisory_xact_lock(1106511000)",Object.class);
        if (count("select count(*) from integration_connection where owner_id=?", actor) >= 100
                || count("select count(*) from integration_connection") >= 1000) {
            throw new BusinessException("INTEGRATION_CONNECTION_LIMIT", "At most 100 connections may be retained per owner", HttpStatus.CONFLICT);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into integration_connection(id,request_id,provider,kind,label,owner_id,minimum_scopes,status,credential_ciphertext,credential_expires_at)
                values(?,?,?,?,?,?,?::jsonb,'ACTIVE',?,?)
                """, id, command.requestId(), command.provider().name(), command.provider().kind(), label, actor,
                encode(command.provider().scopes()), secrets.convertToDatabaseColumn(command.credential()), time(command.credentialExpiresAt().truncatedTo(ChronoUnit.MICROS)));
        audit.record("INTEGRATION_CONNECTED", "INTEGRATION_CONNECTION", id.toString(), "{}");
        return owned(actor, id, false);
    }

    @Transactional
    public IntegrationModels.Connection reconnect(UUID actor, UUID id, IntegrationModels.Reconnect command) {
        requireAdmin(actor, true);
        var current = owned(actor, id, true);
        if (command == null) throw invalid();
        expected(command.expectedVersion(), current.version());
        credential(command.credential(), command.credentialExpiresAt());
        finishClaims(id, "NEEDS_RECONNECT", "CREDENTIAL_CHANGED", Instant.now());
        jdbc.update("""
                update integration_connection set credential_ciphertext=?,credential_expires_at=?,credential_version=credential_version+1,
                status='ACTIVE',version=version+1,last_result_code='NOT_CHECKED',last_checked_at=null,updated_at=now() where id=?
                """, secrets.convertToDatabaseColumn(command.credential()), time(command.credentialExpiresAt().truncatedTo(ChronoUnit.MICROS)), id);
        audit.record("INTEGRATION_RECONNECTED", "INTEGRATION_CONNECTION", id.toString(), "{}");
        return owned(actor, id, false);
    }

    @Transactional
    public IntegrationModels.Connection revoke(UUID actor, UUID id, Long version) {
        requireAdmin(actor, true);
        var current = owned(actor, id, true);
        expected(version, current.version());
        finishClaims(id, "CANCELLED", "CONNECTION_REVOKED", Instant.now());
        jdbc.update("""
                update integration_delivery set status='CANCELLED',last_result_code='CONNECTION_REVOKED',version=version+1
                where connection_id=? and status in ('PENDING','FAILED','NEEDS_RECONNECT')
                """, id);
        jdbc.update("""
                update integration_connection set status='REVOKED',credential_ciphertext=null,credential_version=credential_version+1,
                version=version+1,last_result_code='CONNECTION_REVOKED',updated_at=now() where id=?
                """, id);
        audit.record("INTEGRATION_REVOKED", "INTEGRATION_CONNECTION", id.toString(), "{}");
        return owned(actor, id, false);
    }

    @Transactional
    public IntegrationModels.Delivery test(UUID actor, UUID id, IntegrationModels.Test command) {
        requireAdmin(actor, true);
        if (command == null || command.requestId() == null || command.scenario() == null) throw invalid();
        requestLock(command.requestId());
        var current = owned(actor, id, true);
        UUID replay = receipt(actor, command.requestId(), "TEST", id, command.expectedVersion(), command.scenario().name());
        if (replay != null) return delivery(replay, false);
        expected(command.expectedVersion(), current.version());
        usable(current, Instant.now());
        UUID delivery = UUID.randomUUID();
        insertDelivery(delivery, id, command.requestId(), "CONNECTION_TEST", delivery, 0, Instant.now(), "{}", command.scenario());
        receipt(command.requestId(), actor, "TEST", id, command.expectedVersion(), command.scenario().name(), delivery);
        audit.record("INTEGRATION_TEST_QUEUED", "INTEGRATION_DELIVERY", delivery.toString(), "{}");
        return delivery(delivery, false);
    }

    @Transactional(readOnly = true)
    public Page<IntegrationModels.Delivery> deliveries(UUID actor, UUID id, int page) {
        requireAdmin(actor, false); owned(actor, id, false);
        if (page < 0 || page > 100000) throw invalid();
        long total = count("select count(*) from integration_delivery where connection_id=?", id);
        return new PageImpl<>(jdbc.query("select * from integration_delivery where connection_id=? order by created_at desc,id limit ? offset ?",
                deliveries, id, PAGE_SIZE, (long) page * PAGE_SIZE), PageRequest.of(page, PAGE_SIZE), total);
    }

    @Transactional(readOnly = true)
    public List<IntegrationModels.Attempt> attempts(UUID actor, UUID id) {
        requireAdmin(actor, false);
        var d = delivery(id, false); owned(actor, d.connectionId(), false);
        return jdbc.query("select * from integration_delivery_attempt where delivery_id=? order by attempt_number,id limit 20",
                (rs,n) -> new IntegrationModels.Attempt(rs.getObject("id",UUID.class),id,rs.getInt("attempt_number"),
                        rs.getLong("credential_version"),rs.getString("outcome"),instant(rs,"started_at"),instant(rs,"completed_at")), id);
    }

    @Transactional
    public IntegrationModels.Delivery retry(UUID actor, UUID id, IntegrationModels.Retry command) {
        requireAdmin(actor, true);
        if (command == null || command.requestId() == null) throw invalid();
        requestLock(command.requestId());
        var observed = delivery(id, false);
        var current = owned(actor, observed.connectionId(), true);
        var d = delivery(id, true);
        UUID replay = receipt(actor, command.requestId(), "RETRY", id, command.expectedVersion(), null);
        if (replay != null) return d;
        expected(command.expectedVersion(), d.version()); usable(current, Instant.now());
        if (!List.of("FAILED","NEEDS_RECONNECT").contains(d.status()) || d.manualRetries() >= 3 || d.totalAttempts() >= 20) throw conflict();
        if (superseded(d)) throw new BusinessException("INTEGRATION_DELIVERY_SUPERSEDED", "A newer revision is already queued; reload deliveries", HttpStatus.CONFLICT);
        jdbc.update("""
                update integration_delivery set status='PENDING',attempts=0,manual_retries=manual_retries+1,retry_request_id=?,
                next_attempt_at=now(),last_result_code='RETRY_QUEUED',version=version+1 where id=?
                """, command.requestId(), id);
        receipt(command.requestId(), actor, "RETRY", id, command.expectedVersion(), null, id);
        audit.record("INTEGRATION_DELIVERY_RETRIED", "INTEGRATION_DELIVERY", id.toString(), "{}");
        return delivery(id, false);
    }

    /** REQUIRED joins the source transaction, including when no connection currently exists. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(UUID resource, String eventType, Instant occurredAt, Map<String,Object> payload) {
        capture(UUID.randomUUID(),resource,eventType,occurredAt,payload);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(UUID event, UUID resource, String eventType, Instant occurredAt, Map<String,Object> payload) {
        validateSnapshot(event,resource,eventType,occurredAt,payload);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,11066))",Object.class,event.toString());
        String snapshot = encode(payload);
        var existing = jdbc.queryForList("select resource_id,event_type,payload_json::text payload from integration_business_event where id=?",event);
        if (!existing.isEmpty()) {
            var retained = existing.getFirst();
            if (!resource.equals(retained.get("resource_id")) || !eventType.equals(retained.get("event_type"))) throw conflict();
            try { if (!json.readTree(snapshot).equals(json.readTree((String)retained.get("payload")))) throw conflict(); }
            catch (JsonProcessingException exception) { throw invalid(); }
            return;
        }
        long revision = Objects.requireNonNull(jdbc.queryForObject("""
                insert into integration_resource_revision(resource_id,revision) values(?,1)
                on conflict(resource_id) do update set revision=integration_resource_revision.revision+1 returning revision
                """, Long.class, resource));
        jdbc.update("insert into integration_business_event(id,resource_id,business_revision,event_type,occurred_at,payload_json) values(?,?,?,?,?,?::jsonb)",
                event, resource, revision, eventType, time(occurredAt), snapshot);
        String kind = eventType.equals("VISITOR_ARRIVED") ? "MESSAGING" : "CALENDAR";
        jdbc.query("""
                select c.* from integration_connection c join iam_user_account a on a.id=c.owner_id
                where c.kind=? and c.status='ACTIVE' and c.credential_expires_at>now() and a.enabled and a.account_status='ACTIVE' and not a.archived
                and (select count(*) from iam_user_role r where r.user_id=a.id)=1
                and exists(select 1 from iam_user_role r where r.user_id=a.id and r.role_name='ROLE_SYSTEM_ADMIN')
                and not exists(select 1 from iam_user_permission_deny p where p.user_id=a.id and p.permission_name='SYSTEM_CONFIGURE')
                order by c.id
                """, connections, kind).forEach(c -> insertDelivery(UUID.randomUUID(), c.id(), event, eventType, resource, revision, occurredAt, snapshot, IntegrationModels.Scenario.SUCCESS));
    }

    @Transactional(readOnly = true)
    public List<UUID> due() {
        return jdbc.query("""
                select id from integration_delivery where (status='PENDING' and next_attempt_at<=now())
                or (status='RUNNING' and lease_until<=now()) order by next_attempt_at,id limit 20
                """, (rs,n) -> rs.getObject("id",UUID.class));
    }

    /** Connection and current-account locks precede delivery locks everywhere. */
    @Transactional
    public Optional<Claim> claim(UUID id, Instant now) {
        var observed = delivery(id, false);
        var owner = connectionOwner(observed.connectionId());
        boolean eligible = eligibleOwner(owner);
        var c = connectionLocked(observed.connectionId());
        var d = delivery(id, true);
        if (!List.of("PENDING","RUNNING").contains(d.status())) return Optional.empty();
        if (d.status().equals("RUNNING")) {
            Instant lease = jdbc.queryForObject("select lease_until from integration_delivery where id=?",Timestamp.class,id).toInstant();
            if (lease.isAfter(now)) return Optional.empty();
            recordAttempt(d.id(), "LEASE_EXPIRED", now);
            clearLease(id, "PENDING", "LEASE_EXPIRED", now);
            d = delivery(id, false);
        }
        if (!eligible || !c.status().equals("ACTIVE") || !c.credentialExpiresAt().isAfter(now)) {
            expireConnection(c,now);
            String status = c.status().equals("REVOKED") || !eligible ? "CANCELLED" : "NEEDS_RECONNECT";
            clearLease(id, status, !eligible ? "OWNER_INELIGIBLE" : c.status().equals("REVOKED") ? "CONNECTION_REVOKED" : "REAUTH_REQUIRED", now);
            return Optional.empty();
        }
        if (superseded(d)) { clearLease(id,"SUPERSEDED","NEWER_REVISION",now); return Optional.empty(); }
        if (d.attempts() >= 5 || d.totalAttempts() >= 20) { clearLease(id,"FAILED","RETRIES_EXHAUSTED",now); return Optional.empty(); }
        if (d.nextAttemptAt().isAfter(now)) return Optional.empty();
        UUID token = UUID.randomUUID();
        jdbc.update("""
                update integration_delivery set status='RUNNING',attempts=attempts+1,total_attempts=total_attempts+1,
                lease_token=?,lease_until=?,lease_started_at=?,claimed_credential_version=?,version=version+1 where id=?
                """, token, time(now.plusSeconds(60)), time(now), c.credentialVersion(), id);
        return Optional.of(new Claim(id, token, c.credentialVersion()));
    }

    /** Provider receipt, mapping and completion share one transaction for the fixed local simulator. */
    @Transactional
    public void complete(Claim claim, Instant now) {
        var observed = delivery(claim.deliveryId(), false);
        boolean eligible = eligibleOwner(connectionOwner(observed.connectionId()));
        var c = connectionLocked(observed.connectionId());
        var d = delivery(claim.deliveryId(), true);
        if (!d.status().equals("RUNNING")) return;
        Map<String,Object> lease = jdbc.queryForMap("select lease_token,lease_until,claimed_credential_version from integration_delivery where id=?", d.id());
        if (!claim.token().equals(lease.get("lease_token")) || claim.credentialVersion() != ((Number)lease.get("claimed_credential_version")).longValue()) return;
        if (!((Timestamp)lease.get("lease_until")).toInstant().isAfter(now)) {
            recordAttempt(d.id(),"LEASE_EXPIRED",now); clearLease(d.id(),"PENDING","LEASE_EXPIRED",now); return;
        }
        if (!eligible || c.status().equals("REVOKED")) {
            String outcome = eligible ? "CONNECTION_REVOKED" : "OWNER_INELIGIBLE";
            recordAttempt(d.id(),outcome,now); clearLease(d.id(),"CANCELLED",outcome,now); return;
        }
        if (!c.status().equals("ACTIVE") || !c.credentialExpiresAt().isAfter(now) || c.credentialVersion() != claim.credentialVersion()) {
            expireConnection(c,now);
            recordAttempt(d.id(),"REAUTH_REQUIRED",now); clearLease(d.id(),"NEEDS_RECONNECT","REAUTH_REQUIRED",now); return;
        }
        if (superseded(d)) { recordAttempt(d.id(),"NEWER_REVISION",now); clearLease(d.id(),"SUPERSEDED","NEWER_REVISION",now); return; }
        // Decryption validates the configured key; the secret never enters a result, request URL or log.
        String credential;
        try { credential = secret(c.id()); }
        catch (RuntimeException exception) { credential = null; }
        if (credential == null || credential.isBlank()) {
            jdbc.update("update integration_connection set status='NEEDS_RECONNECT',last_result_code='REAUTH_REQUIRED',last_checked_at=?,version=version+1,updated_at=now() where id=?",time(now),c.id());
            recordAttempt(d.id(),"REAUTH_REQUIRED",now); clearLease(d.id(),"NEEDS_RECONNECT","REAUTH_REQUIRED",now); return;
        }
        IntegrationModels.Scenario scenario = IntegrationModels.Scenario.valueOf(jdbc.queryForObject("select scenario from integration_delivery where id=?",String.class,d.id()));
        String result = scenario.name();
        String status;
        if (scenario == IntegrationModels.Scenario.SUCCESS) {
            String external = "sim-" + c.id() + "-" + d.resourceId();
            jdbc.update("insert into integration_simulator_receipt(connection_id,business_event_id,external_id) values(?,?,?) on conflict do nothing", c.id(),d.businessEventId(),external);
            if (!d.eventType().equals("CONNECTION_TEST")) jdbc.update("""
                    insert into integration_external_mapping(connection_id,resource_id,external_id,business_revision,business_event_id,last_event_type)
                    values(?,?,?,?,?,?) on conflict(connection_id,resource_id) do update set
                    external_id=excluded.external_id,business_revision=excluded.business_revision,business_event_id=excluded.business_event_id,
                    last_event_type=excluded.last_event_type,updated_at=now()
                    where integration_external_mapping.business_revision<excluded.business_revision
                    """, c.id(),d.resourceId(),external,d.businessRevision(),d.businessEventId(),d.eventType());
            status = "DELIVERED";
        } else if (scenario == IntegrationModels.Scenario.REAUTH_REQUIRED) {
            status = "NEEDS_RECONNECT";
            jdbc.update("update integration_connection set status='NEEDS_RECONNECT',version=version+1,updated_at=now() where id=?", c.id());
        } else if (scenario == IntegrationModels.Scenario.PERMANENT_FAILURE || d.attempts() >= 5 || d.totalAttempts() >= 20) status = "FAILED";
        else status = "PENDING";
        recordAttempt(d.id(),result,now);
        clearLease(d.id(),status,result, status.equals("PENDING") ? now.plusSeconds(scenario == IntegrationModels.Scenario.RATE_LIMITED ? Math.max(60,retryDelay(d.attempts())) : retryDelay(d.attempts())) : now);
        if (status.equals("DELIVERED")) jdbc.update("update integration_delivery set delivered_at=? where id=?",time(now),d.id());
        jdbc.update("update integration_connection set last_checked_at=?,last_result_code=?,version=version+1,updated_at=now() where id=?",time(now),result,c.id());
    }

    public static long retryDelay(int attempt) { return Math.min(480L, 30L << Math.min(4, Math.max(0, attempt - 1))); }
    public record Claim(UUID deliveryId, UUID token, long credentialVersion) {}

    private void expireConnection(IntegrationModels.Connection c,Instant now) {
        if (!c.status().equals("REVOKED") && !c.credentialExpiresAt().isAfter(now)) {
            jdbc.update("update integration_connection set status='NEEDS_RECONNECT',last_result_code='REAUTH_REQUIRED',version=version+1,updated_at=now() where id=? and status='ACTIVE'",c.id());
        }
    }
    private static void validateSnapshot(UUID event,UUID resource,String eventType,Instant occurredAt,Map<String,Object> payload) {
        if (event == null || resource == null || eventType == null || occurredAt == null || payload == null
                || payload.values().stream().anyMatch(Objects::isNull)) throw invalid();
        if ("VISITOR_ARRIVED".equals(eventType)) {
            if (!payload.keySet().equals(Set.of("arrived")) || !Boolean.TRUE.equals(payload.get("arrived"))) throw invalid();
            return;
        }
        if (!Set.of("APPOINTMENT_CREATED","APPOINTMENT_UPDATED","APPOINTMENT_CANCELLED").contains(eventType)
                || !payload.keySet().equals(Set.of("status","appointmentType","slotStart","slotEnd"))
                || !APPOINTMENT_STATUSES.contains(payload.get("status")) || !APPOINTMENT_TYPES.contains(payload.get("appointmentType"))) throw invalid();
        try {
            Instant start=Instant.parse((String)payload.get("slotStart")),end=Instant.parse((String)payload.get("slotEnd"));
            if (!end.isAfter(start)) throw invalid();
        } catch (ClassCastException | java.time.format.DateTimeParseException exception) { throw invalid(); }
    }
    private boolean superseded(IntegrationModels.Delivery d) {
        if (d.eventType().equals("CONNECTION_TEST")) return false;
        return count("select count(*) from integration_delivery where connection_id=? and resource_id=? and business_revision>?",d.connectionId(),d.resourceId(),d.businessRevision()) > 0
                || count("select count(*) from integration_external_mapping where connection_id=? and resource_id=? and business_revision>?",d.connectionId(),d.resourceId(),d.businessRevision()) > 0;
    }
    private void finishClaims(UUID connection, String status, String result, Instant now) {
        List<UUID> ids = jdbc.query("select id from integration_delivery where connection_id=? and status='RUNNING' order by id for update",(rs,n)->rs.getObject(1,UUID.class),connection);
        for (UUID id : ids) { recordAttempt(id,result,now); clearLease(id,status,result,now); }
    }
    private void recordAttempt(UUID id, String outcome, Instant now) {
        jdbc.update("""
                insert into integration_delivery_attempt(id,delivery_id,lease_token,attempt_number,credential_version,outcome,started_at,completed_at)
                select ?,id,lease_token,total_attempts,claimed_credential_version,?,lease_started_at,? from integration_delivery
                where id=? and status='RUNNING' on conflict(lease_token) do nothing
                """, UUID.randomUUID(),outcome,time(now),id);
    }
    private void clearLease(UUID id, String status, String result, Instant next) {
        jdbc.update("""
                update integration_delivery set status=?,last_result_code=?,next_attempt_at=?,lease_token=null,lease_until=null,
                lease_started_at=null,claimed_credential_version=null,version=version+1 where id=?
                """,status,result,time(next),id);
    }
    private void insertDelivery(UUID id, UUID connection, UUID event, String type, UUID resource, long revision, Instant occurred, String payload, IntegrationModels.Scenario scenario) {
        jdbc.update("""
                insert into integration_delivery(id,connection_id,business_event_id,event_type,resource_id,business_revision,occurred_at,payload_json,scenario)
                values(?,?,?,?,?,?,?,?::jsonb,?) on conflict(connection_id,business_event_id) do nothing
                """,id,connection,event,type,resource,revision,time(occurred),payload,scenario.name());
    }
    private UUID receipt(UUID actor, UUID request, String operation, UUID target, Long version, String scenario) {
        if (version == null || version < 0) throw invalid();
        var rows = jdbc.queryForList("select * from integration_command_receipt where request_id=?",request);
        if (rows.isEmpty()) return null;
        var r = rows.getFirst();
        if (!actor.equals(r.get("actor_id")) || !operation.equals(r.get("operation")) || !target.equals(r.get("target_id"))
                || version.longValue() != ((Number)r.get("expected_version")).longValue() || !Objects.equals(scenario,r.get("scenario"))) throw conflict();
        return (UUID)r.get("delivery_id");
    }
    private void receipt(UUID request, UUID actor, String operation, UUID target, long version, String scenario, UUID delivery) {
        jdbc.update("insert into integration_command_receipt(request_id,actor_id,operation,target_id,expected_version,scenario,delivery_id) values(?,?,?,?,?,?,?)",request,actor,operation,target,version,scenario,delivery);
    }
    private void requestLock(UUID request) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,11065))",Object.class,request.toString());
    }
    private void requireAdmin(UUID actor, boolean lock) {
        if (lock) {
            jdbc.query("select id from iam_user_account where id=? for update",(rs,n)->rs.getObject(1,UUID.class),actor);
            jdbc.query("select role_name from iam_user_role where user_id=? for share",(rs,n)->rs.getString(1),actor);
            jdbc.query("select permission_name from iam_user_permission_deny where user_id=? for share",(rs,n)->rs.getString(1),actor);
        }
        var current = authority.requireActive(actor);
        if (!current.role().equals("ROLE_SYSTEM_ADMIN") || !current.permissions().contains("SYSTEM_CONFIGURE")) throw forbidden();
    }
    private boolean eligibleOwner(UUID owner) {
        try { requireAdmin(owner,true); return true; }
        catch (BusinessException exception) { return false; }
    }
    private UUID connectionOwner(UUID id) {
        var rows = jdbc.query("select owner_id from integration_connection where id=?",(rs,n)->rs.getObject(1,UUID.class),id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }
    private IntegrationModels.Connection owned(UUID actor, UUID id, boolean lock) {
        List<IntegrationModels.Connection> rows = jdbc.query("select * from integration_connection where id=? and owner_id=?" + (lock ? " for update" : ""),connections,id,actor);
        if (rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private IntegrationModels.Connection connectionLocked(UUID id) { return jdbc.queryForObject("select * from integration_connection where id=? for update",connections,id); }
    private IntegrationModels.Delivery delivery(UUID id, boolean lock) {
        var rows = jdbc.query("select * from integration_delivery where id=?"+(lock?" for update":""),deliveries,id);
        if (rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private boolean sameCredential(String submitted,UUID id) {
        try { return Objects.equals(submitted,secret(id)); }
        catch (RuntimeException exception) { return false; }
    }
    private String secret(UUID id) { return secrets.convertToEntityAttribute(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,id)); }
    private void usable(IntegrationModels.Connection c, Instant now) {
        if (!c.status().equals("ACTIVE") || !c.credentialExpiresAt().isAfter(now)) throw new BusinessException("INTEGRATION_RECONNECT_REQUIRED","Reconnect with an unexpired credential before delivery",HttpStatus.CONFLICT);
    }
    private static void credential(String value, Instant expiry) {
        Instant now = Instant.now();
        if (value == null || value.isBlank() || value.length() < 16 || value.length() > 4096 || expiry == null
                || !expiry.isAfter(now) || expiry.isAfter(now.plus(Duration.ofDays(90)))) throw invalid();
    }
    private static String label(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 80 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value.strip();
    }
    private static void expected(Long expected, long actual) { if (expected == null || expected < 0) throw invalid(); if (expected != actual) throw conflict(); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException exception) { throw new IllegalStateException("Integration snapshot serialization failed"); } }
    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args)); }
    private IntegrationModels.Connection connection(ResultSet rs, int row) throws SQLException {
        var p = IntegrationModels.Provider.valueOf(rs.getString("provider"));
        Instant expiry=instant(rs,"credential_expires_at");
        boolean expired=!rs.getString("status").equals("REVOKED") && !expiry.isAfter(Instant.now());
        return new IntegrationModels.Connection(rs.getObject("id",UUID.class),p,p.kind(),rs.getString("label"),rs.getObject("owner_id",UUID.class),p.scopes(),expired?"NEEDS_RECONNECT":rs.getString("status"),rs.getLong("credential_version"),expiry,rs.getLong("version"),instant(rs,"last_checked_at"),expired?"REAUTH_REQUIRED":rs.getString("last_result_code"),instant(rs,"created_at"),instant(rs,"updated_at"));
    }
    private IntegrationModels.Delivery delivery(ResultSet rs, int row) throws SQLException {
        return new IntegrationModels.Delivery(rs.getObject("id",UUID.class),rs.getObject("connection_id",UUID.class),rs.getObject("business_event_id",UUID.class),rs.getString("event_type"),rs.getObject("resource_id",UUID.class),rs.getLong("business_revision"),rs.getString("status"),rs.getInt("attempts"),rs.getInt("total_attempts"),rs.getInt("manual_retries"),instant(rs,"next_attempt_at"),rs.getString("last_result_code"),rs.getLong("version"),instant(rs,"created_at"),instant(rs,"delivered_at"));
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant(); }
    private static Timestamp time(Instant instant) { return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS)); }
    private static BusinessException invalid() { return new BusinessException("INVALID_INTEGRATION_REQUEST","Supply a valid request ID, observed version, label and a credential expiring within 90 days",HttpStatus.BAD_REQUEST); }
    private static BusinessException conflict() { return new BusinessException("INTEGRATION_VERSION_CONFLICT","This integration changed or the request ID was already used; reload before continuing",HttpStatus.CONFLICT); }
    private static BusinessException forbidden() { return new BusinessException("INTEGRATION_ADMIN_REQUIRED","An active System Admin with configuration authority is required",HttpStatus.FORBIDDEN); }
    private static BusinessException missing() { return new BusinessException("INTEGRATION_NOT_FOUND","Integration was not found in your account",HttpStatus.NOT_FOUND); }
}
