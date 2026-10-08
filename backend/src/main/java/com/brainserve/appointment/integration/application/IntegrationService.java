package com.brainserve.appointment.integration.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.integration.google.GoogleCalendarAdapter;
import com.brainserve.appointment.integration.slack.SlackAdapter;
import com.brainserve.appointment.integration.slack.SlackModels;
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

/** Committed business outbox and bounded independent worker transactions; source capture never calls a provider. */
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
    private final GoogleCalendarAdapter google;
    private final SlackAdapter slack;
    private final RowMapper<IntegrationModels.Connection> connections = this::connection;
    private final RowMapper<IntegrationModels.Delivery> deliveries = this::delivery;

    public IntegrationService(JdbcTemplate jdbc, CurrentAccountAuthority authority, SensitiveStringConverter secrets,
                              AuditService audit, ObjectMapper json, GoogleCalendarAdapter google, SlackAdapter slack) {
        this.jdbc = jdbc; this.authority = authority; this.secrets = secrets; this.audit = audit; this.json = json; this.google = google; this.slack = slack;
    }

    @Transactional(readOnly = true)
    public List<IntegrationModels.Connection> connections(UUID actor) {
        requireAdmin(actor, false);
        return jdbc.query("select * from integration_connection where owner_id=? order by created_at desc,id limit 100", connections, actor);
    }

    @Transactional
    public IntegrationModels.Connection create(UUID actor, IntegrationModels.Create command) {
        if (command == null) throw invalid();
        simulatorOnly(command.provider());
        return createConnection(actor,command);
    }

    private IntegrationModels.Connection createConnection(UUID actor, IntegrationModels.Create command) {
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

    @Transactional(timeout=20)
    public IntegrationModels.Connection createSlack(UUID actor, SlackModels.Create command) {
        requireAdmin(actor,true);
        if (command==null || command.requestId()==null || !SlackAdapter.channel(command.channelId())) throw invalid();
        credential(command.credential(),command.credentialExpiresAt());
        requestLock(command.requestId());
        var retained=jdbc.query("select * from integration_connection where request_id=?",connections,command.requestId());
        SlackAdapter.Identity identity=null;
        if (retained.isEmpty()) identity=slack.authenticate(command.credential());
        else {
            var existing=retained.getFirst();
            if (!existing.ownerId().equals(actor) || existing.provider()!=IntegrationModels.Provider.SLACK_MESSAGING
                    || !slack.metadata(existing.id()).channelId().equals(command.channelId())) throw conflict();
        }
        var result=createConnection(actor,new IntegrationModels.Create(command.requestId(),IntegrationModels.Provider.SLACK_MESSAGING,command.label(),command.credential(),command.credentialExpiresAt()));
        if (identity!=null) slack.install(result.id(),identity,command.channelId());
        return result;
    }

    @Transactional(readOnly=true)
    public SlackModels.Config slackConfig(UUID actor) {
        requireAdmin(actor,false); return new SlackModels.Config(slack.configured(),"chat:write",true);
    }
    @Transactional(readOnly=true)
    public SlackAdapter.Metadata slackMetadata(UUID actor,UUID id) {
        requireAdmin(actor,false); slackOnly(owned(actor,id,false)); return slack.metadata(id);
    }
    @Transactional(timeout=20)
    public IntegrationModels.Connection renewSlack(UUID actor,UUID id,IntegrationModels.Reconnect command) {
        requireAdmin(actor,true); var current=owned(actor,id,true); slackOnly(current);
        if (command==null) throw invalid(); expected(command.expectedVersion(),current.version());
        credential(command.credential(),command.credentialExpiresAt());
        slack.renew(id,secret(id),command.credential());
        finishClaims(id,"UNKNOWN","DELIVERY_UNKNOWN",Instant.now());
        jdbc.update("update integration_connection set credential_ciphertext=?,credential_expires_at=?,credential_version=credential_version+1,status='ACTIVE',version=version+1,last_result_code='CREDENTIAL_RENEWED',last_checked_at=null,updated_at=now() where id=?",secrets.convertToDatabaseColumn(command.credential()),time(command.credentialExpiresAt()),id);
        audit.record("SLACK_CREDENTIAL_RENEWED","INTEGRATION_CONNECTION",id.toString(),"{}");
        return owned(actor,id,false);
    }
    @Transactional
    public SlackAdapter.Metadata retrySlackRevocation(UUID actor,UUID id,Long version) {
        requireAdmin(actor,true); var current=owned(actor,id,true); slackOnly(current); expected(version,current.version());
        if (!current.status().equals("REVOKED")) throw conflict(); slack.retryRevocation(id);
        jdbc.update("update integration_connection set version=version+1,updated_at=now() where id=?",id);
        audit.record("SLACK_REVOCATION_RETRIED","INTEGRATION_CONNECTION",id.toString(),"{}");
        return slack.metadata(id);
    }
    private static void slackOnly(IntegrationModels.Connection current) { if (current.provider()!=IntegrationModels.Provider.SLACK_MESSAGING) throw invalid(); }

    @Transactional
    public IntegrationModels.Connection reconnect(UUID actor, UUID id, IntegrationModels.Reconnect command) {
        requireAdmin(actor, true);
        var current = owned(actor, id, true);
        simulatorOnly(current.provider());
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
        if (current.provider() == IntegrationModels.Provider.GOOGLE_CALENDAR) google.disconnect(id, current.credentialVersion());
        if (current.provider() == IntegrationModels.Provider.SLACK_MESSAGING) slack.disconnect(id,current.credentialVersion());
        jdbc.update("update integration_calendar_reconciliation set status='CANCELLED',completed_at=now() where connection_id=? and status in ('QUEUED','RUNNING')", id);
        finishClaims(id, "CANCELLED", "CONNECTION_REVOKED", Instant.now());
        jdbc.update("""
                update integration_delivery set status='CANCELLED',last_result_code='CONNECTION_REVOKED',version=version+1
                where connection_id=? and status in ('PENDING','FAILED','NEEDS_RECONNECT','UNKNOWN')
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
        if (current.provider()==IntegrationModels.Provider.SLACK_MESSAGING) {
            if (command.scenario()!=IntegrationModels.Scenario.SUCCESS) throw invalid();
        } else simulatorOnly(current.provider());
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
        String acknowledgement=command.acceptDuplicateRisk()?"ACCEPT_DUPLICATE_RISK":null;
        UUID replay = receipt(actor, command.requestId(), "RETRY", id, command.expectedVersion(), acknowledgement);
        if (replay != null) return d;
        expected(command.expectedVersion(), d.version()); usable(current, Instant.now());
        if (!List.of("FAILED","NEEDS_RECONNECT","UNKNOWN").contains(d.status()) || d.manualRetries() >= 3 || d.totalAttempts() >= 20) throw conflict();
        if (d.status().equals("UNKNOWN") && (current.provider()!=IntegrationModels.Provider.SLACK_MESSAGING || !command.acceptDuplicateRisk()))
            throw new BusinessException("SLACK_DUPLICATE_RISK_ACK_REQUIRED","Slack may already have accepted this notice; acknowledge duplicate risk before retry",HttpStatus.CONFLICT);
        if (current.provider()==IntegrationModels.Provider.SLACK_MESSAGING && !slackDeadline(d).isAfter(Instant.now()))
            throw new BusinessException("SLACK_DELIVERY_EXPIRED","Arrival notices expire after 24 hours",HttpStatus.CONFLICT);
        if (superseded(d, current.provider())) throw new BusinessException("INTEGRATION_DELIVERY_SUPERSEDED", "A newer revision is already queued; reload deliveries", HttpStatus.CONFLICT);
        jdbc.update("""
                update integration_delivery set status='PENDING',attempts=0,manual_retries=manual_retries+1,retry_request_id=?,
                next_attempt_at=now(),last_result_code='RETRY_QUEUED',version=version+1 where id=?
                """, command.requestId(), id);
        receipt(command.requestId(), actor, "RETRY", id, command.expectedVersion(), acknowledgement, id);
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
            String outcome=c.provider()==IntegrationModels.Provider.SLACK_MESSAGING?"DELIVERY_UNKNOWN":"LEASE_EXPIRED";
            recordAttempt(d.id(), outcome, now);
            clearLease(id, c.provider()==IntegrationModels.Provider.SLACK_MESSAGING?"UNKNOWN":"PENDING", outcome, now);
            if (c.provider()==IntegrationModels.Provider.SLACK_MESSAGING) return Optional.empty();
            d = delivery(id, false);
        }
        if (!eligible || !c.status().equals("ACTIVE") || !c.credentialExpiresAt().isAfter(now)) {
            expireConnection(c,now);
            String status = c.status().equals("REVOKED") || !eligible ? "CANCELLED" : "NEEDS_RECONNECT";
            clearLease(id, status, !eligible ? "OWNER_INELIGIBLE" : c.status().equals("REVOKED") ? "CONNECTION_REVOKED" : "REAUTH_REQUIRED", now);
            return Optional.empty();
        }
        if (cancelledRepair(d, c)) { clearLease(id,"CANCELLED","RECONCILIATION_CANCELLED",now); return Optional.empty(); }
        if (superseded(d, c.provider())) { clearLease(id,"SUPERSEDED","NEWER_REVISION",now); return Optional.empty(); }
        if (d.attempts() >= 5 || d.totalAttempts() >= 20) { clearLease(id,"FAILED","RETRIES_EXHAUSTED",now); return Optional.empty(); }
        if (d.nextAttemptAt().isAfter(now)) return Optional.empty();
        if (c.provider()==IntegrationModels.Provider.SLACK_MESSAGING && !slackDeadline(d).isAfter(now)) { clearLease(id,"FAILED","DELIVERY_EXPIRED",now); return Optional.empty(); }
        UUID token = UUID.randomUUID();
        jdbc.update("""
                update integration_delivery set status='RUNNING',attempts=attempts+1,total_attempts=total_attempts+1,
                lease_token=?,lease_until=?,lease_started_at=?,claimed_credential_version=?,version=version+1 where id=?
                """, token, time(now.plusSeconds(60)), time(now), c.credentialVersion(), id);
        return Optional.of(new Claim(id, token, c.credentialVersion()));
    }

    /**
     * Separate worker transaction: account -> connection -> delivery locks serialize revoke and generation changes.
     * Google HTTP (including refresh) has a 24-second total ceiling; require 35 lease seconds before starting.
     * A 45-second transaction timeout bounds the worker. Remote effects and this DB commit are not atomic:
     * unknown outcomes recover through the deterministic event ID, revision and provider ETag checks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 45)
    public void complete(Claim claim, Instant now) {
        var observed = delivery(claim.deliveryId(), false);
        boolean eligible = eligibleOwner(connectionOwner(observed.connectionId()));
        var c = connectionLocked(observed.connectionId());
        var d = delivery(claim.deliveryId(), true);
        if (!d.status().equals("RUNNING")) return;
        Map<String,Object> lease = jdbc.queryForMap("select lease_token,lease_until,claimed_credential_version from integration_delivery where id=?", d.id());
        if (!claim.token().equals(lease.get("lease_token")) || claim.credentialVersion() != ((Number)lease.get("claimed_credential_version")).longValue()) return;
        if (!((Timestamp)lease.get("lease_until")).toInstant().isAfter(now)) {
            boolean unknown=c.provider()==IntegrationModels.Provider.SLACK_MESSAGING;
            recordAttempt(d.id(),unknown?"DELIVERY_UNKNOWN":"LEASE_EXPIRED",now); clearLease(d.id(),unknown?"UNKNOWN":"PENDING",unknown?"DELIVERY_UNKNOWN":"LEASE_EXPIRED",now); return;
        }
        if (!eligible || c.status().equals("REVOKED")) {
            String outcome = eligible ? "CONNECTION_REVOKED" : "OWNER_INELIGIBLE";
            recordAttempt(d.id(),outcome,now); clearLease(d.id(),"CANCELLED",outcome,now); return;
        }
        if (!c.status().equals("ACTIVE") || !c.credentialExpiresAt().isAfter(now) || c.credentialVersion() != claim.credentialVersion()) {
            expireConnection(c,now);
            recordAttempt(d.id(),"REAUTH_REQUIRED",now); clearLease(d.id(),"NEEDS_RECONNECT","REAUTH_REQUIRED",now); return;
        }
        if (cancelledRepair(d, c)) { recordAttempt(d.id(),"RECONCILIATION_CANCELLED",now); clearLease(d.id(),"CANCELLED","RECONCILIATION_CANCELLED",now); return; }
        if (superseded(d, c.provider())) { recordAttempt(d.id(),"NEWER_REVISION",now); clearLease(d.id(),"SUPERSEDED","NEWER_REVISION",now); return; }
        if (c.provider() == IntegrationModels.Provider.GOOGLE_CALENDAR) {
            completeGoogle(c, d, now, ((Timestamp) lease.get("lease_until")).toInstant());
            return;
        }
        if (c.provider()==IntegrationModels.Provider.SLACK_MESSAGING) {
            completeSlack(c,d,now,((Timestamp)lease.get("lease_until")).toInstant()); return;
        }
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

    private Instant slackDeadline(IntegrationModels.Delivery d) {
        return jdbc.queryForObject("select occurred_at from integration_delivery where id=?",Timestamp.class,d.id()).toInstant().plus(Duration.ofHours(24));
    }
    private void completeSlack(IntegrationModels.Connection c, IntegrationModels.Delivery d, Instant now, Instant leaseUntil) {
        Instant deadline=slackDeadline(d);
        Instant started=Instant.now().isAfter(now)?Instant.now():now;
        if (!List.of("VISITOR_ARRIVED","CONNECTION_TEST").contains(d.eventType()) || !deadline.isAfter(started.plusSeconds(5))) { finishGoogle(c,d,"FAILED","DELIVERY_EXPIRED",now,0); return; }
        if (leaseUntil.isBefore(Instant.now().plusSeconds(10))) { finishGoogle(c,d,"PENDING","LEASE_EXPIRING",now,30); return; }
        SlackAdapter.Result result;
        try { result=slack.deliver(c.id(),d.eventType().equals("CONNECTION_TEST"),now,deadline,leaseUntil); }
        catch (RuntimeException uncertain) { result=new SlackAdapter.Result("DELIVERY_UNKNOWN",now,null); }
        String code=result.code();
        Instant finished=Instant.now().isAfter(now)?Instant.now():now;
        if (code.equals("RATE_WAIT") || code.equals("LEASE_EXPIRING")) {
            // No HTTP occurred: refund this lease's send reservation, without inventing a provider attempt.
            jdbc.update("update integration_delivery set attempts=attempts-1,total_attempts=total_attempts-1 where id=?",d.id());
            clearLease(d.id(),result.nextAttemptAt().isBefore(deadline)?"PENDING":"FAILED",code,result.nextAttemptAt());
            return;
        }
        String status=switch(code) {
            case "SUCCESS" -> "DELIVERED";
            case "DELIVERY_UNKNOWN" -> "UNKNOWN";
            case "REAUTH_REQUIRED" -> "NEEDS_RECONNECT";
            case "RATE_LIMITED" -> d.attempts()<5 && d.totalAttempts()<20 && result.nextAttemptAt().isBefore(deadline)?"PENDING":"FAILED";
            default -> "FAILED";
        };
        if (status.equals("NEEDS_RECONNECT")) jdbc.update("update integration_connection set status='NEEDS_RECONNECT' where id=?",c.id());
        if (status.equals("DELIVERED") && !d.eventType().equals("CONNECTION_TEST")) jdbc.update("""
                insert into integration_external_mapping(connection_id,resource_id,external_id,business_revision,business_event_id,last_event_type)
                values(?,?,?,?,?,?) on conflict(connection_id,resource_id) do update set external_id=excluded.external_id,business_revision=excluded.business_revision,business_event_id=excluded.business_event_id,last_event_type=excluded.last_event_type,updated_at=now()
                where integration_external_mapping.business_revision<excluded.business_revision
                """,c.id(),d.resourceId(),result.externalId(),d.businessRevision(),d.businessEventId(),d.eventType());
        long delay=status.equals("PENDING")?Math.max(1,Duration.between(finished,result.nextAttemptAt()).toSeconds()+1):0;
        finishGoogle(c,d,status,code,finished,delay);
    }

    private void completeGoogle(IntegrationModels.Connection c, IntegrationModels.Delivery d, Instant now, Instant leaseUntil) {
        var source = latestSource(d.resourceId());
        if (source == null || CalendarProjection.action(source.status()).equals("SKIP")) {
            finishGoogle(c, d, "DELIVERED", "SKIPPED_STATE", now, 0);
            return;
        }
        if (!d.eventType().equals("CALENDAR_RECONCILE") && source.revision() != d.businessRevision()) {
            finishGoogle(c, d, "SUPERSEDED", "NEWER_REVISION", now, 0);
            return;
        }
        // Never start bounded network work close to the lease fence. A crash after HTTP before DB commit is unknown,
        // so the same deterministic provider ID and current revision are used again after lease recovery.
        Instant started = Instant.now();
        if (leaseUntil.isBefore(started.plusSeconds(35))) {
            finishGoogle(c, d, "PENDING", "LEASE_EXPIRING", now, retryDelay(d.attempts()));
            return;
        }
        String eventId = CalendarProjection.eventId(c.id(), d.resourceId());
        GoogleCalendarAdapter.Result result;
        try {
            result = google.deliver(c.id(), c.credentialVersion(), eventId, source.revision(),
                    CalendarProjection.action(source.status()), source.start(), source.end());
        } catch (RuntimeException exception) {
            // The provider may have committed: no fabricated success or mapping, and no raw exception/body logging.
            result = new GoogleCalendarAdapter.Result("OUTAGE", 0, null);
        }
        String code = result == null ? "OUTAGE" : result.code();
        if (code == null || !Set.of("SUCCESS", "OUTAGE", "RATE_LIMITED", "REAUTH_REQUIRED", "PERMANENT_FAILURE", "NEWER_REVISION").contains(code)) code = "OUTAGE";
        Instant finished = Instant.now().isAfter(now) ? Instant.now() : now;
        String status;
        if (code.equals("SUCCESS")) {
            if (!eventId.equals(result.externalId())) code = "OUTAGE";
            else {
                jdbc.update("""
                        insert into integration_external_mapping(connection_id,resource_id,external_id,business_revision,business_event_id,last_event_type)
                        values(?,?,?,?,?,?) on conflict(connection_id,resource_id) do update set
                        external_id=excluded.external_id,business_revision=excluded.business_revision,business_event_id=excluded.business_event_id,
                        last_event_type=excluded.last_event_type,updated_at=now()
                        where integration_external_mapping.business_revision<=excluded.business_revision
                        """, c.id(), d.resourceId(), eventId, source.revision(), source.event(), source.eventType());
                finishGoogle(c, d, "DELIVERED", code, finished, 0);
                return;
            }
        }
        if (code.equals("REAUTH_REQUIRED")) {
            status = "NEEDS_RECONNECT";
            jdbc.update("update integration_connection set status='NEEDS_RECONNECT',version=version+1,updated_at=now() where id=?", c.id());
        } else if (code.equals("NEWER_REVISION")) status = "SUPERSEDED";
        else if (code.equals("PERMANENT_FAILURE") || d.attempts() >= 5 || d.totalAttempts() >= 20) status = "FAILED";
        else status = "PENDING";
        int retryAfter = result == null ? 0 : Math.max(0, Math.min(3600, result.retryAfterSeconds()));
        long delay = code.equals("RATE_LIMITED") ? Math.max(60, Math.max(retryDelay(d.attempts()), retryAfter)) : Math.max(retryDelay(d.attempts()), retryAfter);
        finishGoogle(c, d, status, code, finished, status.equals("PENDING") ? delay : 0);
    }

    private void finishGoogle(IntegrationModels.Connection c, IntegrationModels.Delivery d, String status, String result, Instant now, long delay) {
        recordAttempt(d.id(), result, now);
        clearLease(d.id(), status, result, now.plusSeconds(delay));
        if (status.equals("DELIVERED")) jdbc.update("update integration_delivery set delivered_at=? where id=?", time(now), d.id());
        jdbc.update("update integration_connection set last_checked_at=?,last_result_code=?,version=version+1,updated_at=now() where id=?", time(now), result, c.id());
    }

    @Transactional(timeout = 10)
    public IntegrationModels.Reconciliation reconcile(UUID actor, UUID id, IntegrationModels.Reconcile command) {
        requireAdmin(actor, true);
        if (command == null || command.requestId() == null || command.expectedVersion() == null || command.expectedVersion() < 0) throw invalid();
        requestLock(command.requestId());
        var current = owned(actor, id, true);
        googleOnly(current);
        var retained = jdbc.queryForList("select * from integration_calendar_reconciliation where request_id=?", command.requestId());
        if (!retained.isEmpty()) {
            var job = retained.getFirst();
            if (!actor.equals(job.get("actor_id")) || !id.equals(job.get("connection_id"))
                    || command.expectedVersion() != ((Number) job.get("expected_version")).longValue()) throw conflict();
            return reconciliation((UUID) job.get("id"));
        }
        expected(command.expectedVersion(), current.version());
        usable(current, Instant.now());
        // Active-one, five-minute cooldown, three jobs per day and <=500 resources bound repair floods.
        if (count("select count(*) from integration_calendar_reconciliation where connection_id=? and (status in ('QUEUED','RUNNING') or created_at>now()-interval '5 minutes')", id) > 0
                || count("select count(*) from integration_calendar_reconciliation where connection_id=? and created_at>now()-interval '24 hours'", id) >= 3
                || count("select count(*) from integration_delivery where connection_id=? and status in ('PENDING','RUNNING')", id) > 1500) throw reconcileLimit();
        if (calendarCandidates(null, 501).size() > 500) throw reconcileLimit();
        UUID job = UUID.randomUUID();
        jdbc.update("insert into integration_calendar_reconciliation(id,request_id,connection_id,actor_id,expected_version,credential_version) values(?,?,?,?,?,?)",
                job, command.requestId(), id, actor, command.expectedVersion(), current.credentialVersion());
        jdbc.update("update integration_connection set version=version+1,updated_at=now() where id=?", id);
        audit.record("CALENDAR_RECONCILIATION_QUEUED", "INTEGRATION_CONNECTION", id.toString(), "{}");
        return reconciliation(job);
    }

    @Transactional(readOnly = true)
    public IntegrationModels.Reconciliation latestReconciliation(UUID actor, UUID id) {
        requireAdmin(actor, false);
        googleOnly(owned(actor, id, false));
        var ids = jdbc.query("select id from integration_calendar_reconciliation where connection_id=? order by created_at desc,id desc limit 1", (rs,n) -> rs.getObject(1, UUID.class), id);
        return ids.isEmpty() ? null : reconciliation(ids.getFirst());
    }

    @Transactional(readOnly = true)
    public List<UUID> dueReconciliations() {
        return jdbc.query("select id from integration_calendar_reconciliation where status in ('QUEUED','RUNNING') and next_batch_at<=now() order by next_batch_at,id limit 5", (rs,n) -> rs.getObject(1, UUID.class));
    }

    /** Each durable scan transaction inserts at most 25 idempotent deliveries; no provider call occurs here. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void reconcileBatch(UUID jobId) {
        UUID connection = jdbc.queryForObject("select connection_id from integration_calendar_reconciliation where id=?", UUID.class, jobId);
        boolean eligible = eligibleOwner(connectionOwner(connection));
        var c = connectionLocked(connection);
        var job = jdbc.queryForMap("select * from integration_calendar_reconciliation where id=? for update", jobId);
        if (!List.of("QUEUED", "RUNNING").contains(job.get("status"))) return;
        if (!eligible || c.status().equals("REVOKED") || c.credentialVersion() != ((Number) job.get("credential_version")).longValue()) {
            closeReconciliation(jobId, "CANCELLED"); return;
        }
        if (!c.status().equals("ACTIVE") || !c.credentialExpiresAt().isAfter(Instant.now())) {
            closeReconciliation(jobId, "FAILED"); return;
        }
        if ((Boolean) job.get("scan_complete")) {
            if (count("select count(*) from integration_delivery where reconciliation_id=? and status in ('PENDING','RUNNING')", jobId) > 0) {
                jdbc.update("update integration_calendar_reconciliation set next_batch_at=now()+interval '30 seconds' where id=?", jobId);
                return;
            }
            boolean failed = count("select count(*) from integration_delivery where reconciliation_id=? and status in ('FAILED','NEEDS_RECONNECT','CANCELLED')", jobId) > 0;
            closeReconciliation(jobId, failed ? "FAILED" : "COMPLETED"); return;
        }
        int processed = ((Number) job.get("processed")).intValue();
        var sources = calendarCandidates((UUID) job.get("cursor_resource_id"), 25);
        if (processed + sources.size() > 500) { closeReconciliation(jobId, "FAILED"); return; }
        UUID cursor = (UUID) job.get("cursor_resource_id");
        for (Source source : sources) {
            UUID deliveryId = UUID.nameUUIDFromBytes((jobId.toString() + ":" + source.resource()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            insertDelivery(deliveryId, connection, deliveryId, "CALENDAR_RECONCILE", source.resource(), source.revision(), source.occurred(), source.payload(), IntegrationModels.Scenario.SUCCESS);
            jdbc.update("update integration_delivery set reconciliation_id=? where id=?", jobId, deliveryId);
            cursor = source.resource();
        }
        jdbc.update("update integration_calendar_reconciliation set status='RUNNING',processed=?,cursor_resource_id=?,scan_complete=?,next_batch_at=now() where id=?",
                processed + sources.size(), cursor, sources.size() < 25, jobId);
    }

    /** Authenticated administrative export, approved future appointments only, 30 days and 500 events; overflow fails. */
    @Transactional
    public byte[] calendarFile(UUID actor, Instant now) {
        requireAdmin(actor, true);
        List<CalendarFile.Event> events = jdbc.query("""
                select id,version,slot_start,slot_end from appointment
                where status in ('APPROVED','CHECKED_IN','IN_MEETING') and slot_start>=? and slot_start<?
                order by slot_start,id limit 501
                """, (rs,n) -> new CalendarFile.Event(rs.getObject("id", UUID.class), rs.getLong("version"), instant(rs,"slot_start"), instant(rs,"slot_end")), time(now), time(now.plus(Duration.ofDays(30))));
        if (events.size() > 500) throw new BusinessException("CALENDAR_EXPORT_LIMIT", "More than 500 approved appointments fall within the 30-day export window", HttpStatus.CONFLICT);
        byte[] file = CalendarFile.render(events, now);
        audit.record("CALENDAR_FILE_EXPORTED", "CALENDAR_EXPORT", actor.toString(), "{\"events\":" + events.size() + "}");
        return file;
    }

    private List<Source> calendarCandidates(UUID cursor, int limit) {
        // Indexed newest committed appointment snapshot, not a historical replay or the visitor-arrival revision.
        return jdbc.query("""
                select * from (select distinct on (resource_id) * from integration_business_event
                where event_type<>'VISITOR_ARRIVED' and (?::uuid is null or resource_id>?::uuid)
                order by resource_id,business_revision desc) latest
                where payload_json->>'status' in ('APPROVED','CHECKED_IN','IN_MEETING','CANCELLED','REJECTED','NO_SHOW','EXPIRED')
                order by resource_id limit ?
                """, this::source, cursor, cursor, limit);
    }
    private Source latestSource(UUID resource) {
        var rows = jdbc.query("select * from integration_business_event where resource_id=? and event_type<>'VISITOR_ARRIVED' order by business_revision desc limit 1", this::source, resource);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Source source(ResultSet rs, int row) throws SQLException {
        String payload = rs.getString("payload_json");
        try {
            var fields = json.readTree(payload);
            return new Source(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class), rs.getLong("business_revision"), rs.getString("event_type"),
                    instant(rs,"occurred_at"), payload, fields.path("status").asText(), Instant.parse(fields.path("slotStart").asText()), Instant.parse(fields.path("slotEnd").asText()));
        } catch (JsonProcessingException | java.time.format.DateTimeParseException exception) { throw new IllegalStateException("Invalid retained calendar snapshot"); }
    }
    private record Source(UUID event, UUID resource, long revision, String eventType, Instant occurred, String payload, String status, Instant start, Instant end) {}
    private IntegrationModels.Reconciliation reconciliation(UUID id) {
        return jdbc.queryForObject("select * from integration_calendar_reconciliation where id=?", (rs,n) -> new IntegrationModels.Reconciliation(rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("processed"), instant(rs,"created_at"), instant(rs,"completed_at")), id);
    }
    private void closeReconciliation(UUID id, String status) {
        jdbc.update("update integration_calendar_reconciliation set status=?,completed_at=now() where id=?", status, id);
    }
    private static void googleOnly(IntegrationModels.Connection c) {
        if (c.provider() != IntegrationModels.Provider.GOOGLE_CALENDAR) throw new BusinessException("GOOGLE_CALENDAR_REQUIRED", "Reconciliation requires a consented Google Calendar connection", HttpStatus.CONFLICT);
    }
    private static BusinessException reconcileLimit() {
        return new BusinessException("CALENDAR_RECONCILE_LIMIT", "Use one repair at a time, wait five minutes, and keep within three daily repairs and 500 resources", HttpStatus.CONFLICT);
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
    private boolean cancelledRepair(IntegrationModels.Delivery d, IntegrationModels.Connection c) {
        if (!d.eventType().equals("CALENDAR_RECONCILE")) return false;
        return count("""
                select count(*) from integration_calendar_reconciliation j join integration_delivery d on d.reconciliation_id=j.id
                where d.id=? and (j.status='CANCELLED' or j.credential_version<>?)
                """, d.id(), c.credentialVersion()) > 0;
    }
    private boolean superseded(IntegrationModels.Delivery d, IntegrationModels.Provider provider) {
        if (provider==IntegrationModels.Provider.SLACK_MESSAGING) return false;
        if (provider == IntegrationModels.Provider.GOOGLE_CALENDAR) {
            // Reconciliation resolves current source again under worker locks; a stale queued snapshot is not replayed.
            if (d.eventType().equals("CALENDAR_RECONCILE")) return false;
            if (count("select count(*) from integration_business_event where resource_id=? and event_type<>'VISITOR_ARRIVED' and business_revision>?", d.resourceId(), d.businessRevision()) > 0) return true;
        }
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
    private static void simulatorOnly(IntegrationModels.Provider provider) {
        if (provider == IntegrationModels.Provider.GOOGLE_CALENDAR) throw new BusinessException("GOOGLE_CALENDAR_CONSENT_REQUIRED", "Use Google Calendar consent to connect or reconnect", HttpStatus.CONFLICT);
        if (provider == IntegrationModels.Provider.SLACK_MESSAGING) throw new BusinessException("SLACK_CONNECTION_REQUIRED","Use dedicated Slack connection and renewal controls",HttpStatus.CONFLICT);
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
