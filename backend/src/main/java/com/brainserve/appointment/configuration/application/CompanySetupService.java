package com.brainserve.appointment.configuration.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@Service
public class CompanySetupService {
    private static final List<String> STEP_IDS = List.of("company", "departments", "roles", "policy", "notifications", "privacy", "review");
    private final JdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final AuditService audit;
    private final String officeZone;
    public CompanySetupService(JdbcTemplate jdbc, CurrentAccountAuthority authority, AuditService audit,
                               @Value("${brainserve.appointment.office-zone}") String officeZone) {
        this.jdbc = jdbc; this.authority = authority; this.audit = audit; this.officeZone = ZoneId.of(officeZone).getId();
    }

    @Transactional
    public SetupState state(UUID actor) { require(actor); return current(actor, null, null, false); }
    @Transactional
    public SetupState progress(UUID actor, long expected, String stepId) {
        require(actor);
        if (!STEP_IDS.contains(stepId)) throw error("SETUP_STEP_INVALID", "Unknown setup step", HttpStatus.BAD_REQUEST);
        return current(actor, expected, stepId, false);
    }
    @Transactional
    public SetupState complete(UUID actor, long expected) { require(actor); return current(actor, expected, "review", true); }

    private SetupState current(UUID actor, Long expected, String requestedStep, boolean complete) {
        var progress = jdbc.queryForMap("select * from company_setup_progress where singleton = true for update");
        var settings = settings();
        List<SetupStep> steps = checklist(settings);
        String fingerprint = fingerprint(settings);
        long revision = ((Number) progress.get("revision")).longValue();
        String previousFingerprint = (String) progress.get("readiness_fingerprint");
        boolean changed = !fingerprint.equals(previousFingerprint);
        Instant completedAt = progress.get("completed_at") == null ? null : ((java.sql.Timestamp) progress.get("completed_at")).toInstant();
        if (changed) {
            revision++;
            completedAt = null;
            jdbc.update("update company_setup_progress set revision = ?, completed_at = null, readiness_fingerprint = ?, updated_at = now() where singleton", revision, fingerprint);
        }
        // A drift detected during a write still rejects the caller's old revision. Rollback leaves
        // the old fingerprint, so the follow-up GET recomputes and exposes the new revision.
        if (expected != null && expected != revision) throw error("SETUP_REVISION_CONFLICT", "Setup or its prerequisites changed; reload before continuing", HttpStatus.CONFLICT);
        String currentStep = (String) progress.get("current_step");
        if (requestedStep != null) {
            if (complete && steps.stream().anyMatch(step -> !step.complete())) {
                throw error("SETUP_PREREQUISITES_MISSING", "Resolve every setup checklist issue before completing", HttpStatus.CONFLICT);
            }
            currentStep = requestedStep;
            revision++;
            if (complete) completedAt = Instant.now();
            jdbc.update("update company_setup_progress set revision = ?, current_step = ?, completed_at = ?, updated_at = now() where singleton",
                    revision, currentStep, completedAt == null ? null : java.sql.Timestamp.from(completedAt));
            audit.record(complete ? "COMPANY_SETUP_COMPLETED" : "COMPANY_SETUP_PROGRESS", "COMPANY_SETUP", "singleton", "{\"revision\":" + revision + "}");
        }
        if (requestedStep != null && !fingerprint.equals(fingerprint(settings()))) throw error("SETUP_PREREQUISITES_CHANGED", "Setup prerequisites changed during this request; reload before continuing", HttpStatus.CONFLICT);
        require(actor);
        return new SetupState("FC03.v1", revision, completedAt == null ? "IN_PROGRESS" : "COMPLETE", currentStep, completedAt, officeZone, steps);
    }

    private Map<String,String> settings() {
        Map<String,String> result = new TreeMap<>();
        jdbc.query("select setting_key, setting_value from system_setting order by setting_key", rs -> { result.put(rs.getString(1), rs.getString(2)); });
        return result;
    }
    private List<SetupStep> checklist(Map<String,String> s) {
        List<SetupStep> result = new ArrayList<>();
        List<String> company = new ArrayList<>();
        for (String key : List.of("COMPANY.NAME", "COMPANY.EMAIL_DOMAIN", "COMPANY.HQ_ADDRESS", "COMPANY.SUPPORT_EMAIL")) missing(s, key, company);
        if (!s.getOrDefault("COMPANY.EMAIL_DOMAIN", "").matches("^(?!-)[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+$")) company.add("Set a valid company email domain");
        if (!s.getOrDefault("COMPANY.SUPPORT_EMAIL", "").matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) company.add("Set a valid support email");
        result.add(step("company", "Company profile", company, List.of("COMPANY.NAME","COMPANY.EMAIL_DOMAIN","COMPANY.HQ_ADDRESS","COMPANY.SUPPORT_EMAIL")));
        List<String> departments = new ArrayList<>();
        long active = count("select count(*) from org_department where active");
        if (active == 0) departments.add("Create an active department");
        for (String code : List.of("EXEC","HR")) if (count("select count(*) from org_department where active and code = ?", code) != 1) departments.add("Keep the " + code + " routing department active");
        result.add(step("departments", "Departments", departments, List.of()));
        List<String> roles = new ArrayList<>();
        if (count("select count(*) from iam_user_account a join iam_user_role r on r.user_id = a.id join employee e on e.id = a.employee_id join org_department d on d.id = e.department_id where a.enabled and a.account_status = 'ACTIVE' and not a.archived and r.role_name = 'ROLE_CEO' and e.status = 'ACTIVE' and d.active and (select count(*) from iam_user_role rr where rr.user_id=a.id)=1 and not exists(select 1 from iam_user_permission_deny pd where pd.user_id=a.id and pd.permission_name='CEO_VISIT_APPROVE')") != 1) roles.add("Activate exactly one CEO with an active employee profile through governed invitations and role assignment");
        if (count("select count(*) from org_department d where d.active and not exists (select 1 from department_hr_assignment h join iam_user_account a on a.id=h.hr_user_id join employee e on e.id=h.hr_employee_id join iam_user_role r on r.user_id=a.id where h.department_id=d.id and h.active and a.enabled and a.account_status='ACTIVE' and not a.archived and e.status='ACTIVE' and e.department_id=d.id and a.employee_id=h.hr_employee_id and r.role_name='ROLE_HR_ADMIN' and (select count(*) from iam_user_role rr where rr.user_id=a.id)=1 and not exists(select 1 from iam_user_permission_deny pd where pd.user_id=a.id and pd.permission_name='HR_VISIT_APPROVE'))") > 0) roles.add("Ask the CEO to assign an active department HR to every active department");
        if (count("select count(*) from org_department d where d.active and not exists (select 1 from department_manager_assignment m join iam_user_account a on a.id=m.manager_user_id join employee e on e.id=m.manager_employee_id join iam_user_role r on r.user_id=a.id where m.department_id=d.id and m.active and a.enabled and a.account_status='ACTIVE' and not a.archived and e.status='ACTIVE' and e.department_id=d.id and a.employee_id=m.manager_employee_id and r.role_name='ROLE_MANAGER' and (select count(*) from iam_user_role rr where rr.user_id=a.id)=1 and not exists(select 1 from iam_user_permission_deny pd where pd.user_id=a.id and pd.permission_name='MANAGER_VISIT_APPROVE'))") > 0) roles.add("Ask the CEO to assign active department Managers for CEO visit routing");
        result.add(step("roles", "Roles and leadership", roles, List.of()));
        List<String> policy = new ArrayList<>();
        bounded(s,"APPOINTMENT.SLOT_MINUTES",10,240,policy); bounded(s,"APPOINTMENT.MAX_ADVANCE_DAYS",1,365,policy); bounded(s,"APPOINTMENT.MIN_LEAD_MINUTES",0,1440,policy);
        bounded(s,"APPOINTMENT.CHECK_IN_EARLY_MINUTES",0,240,policy); bounded(s,"APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END",0,2880,policy);
        if (!officeZone.equals(s.get("COMPANY.OFFICE_ZONE"))) policy.add("The office-zone preference must match the running deployment (" + officeZone + "); update deployment configuration and restart when changing zones");
        booleanSetting(s,"APPROVAL.INTERVIEW.REQUIRES_HR",policy);
        result.add(step("policy","Office and appointment policy",policy,List.of("COMPANY.OFFICE_ZONE","APPOINTMENT.SLOT_MINUTES","APPOINTMENT.MAX_ADVANCE_DAYS","APPOINTMENT.MIN_LEAD_MINUTES","APPOINTMENT.CHECK_IN_EARLY_MINUTES","APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END","APPROVAL.INTERVIEW.REQUIRES_HR")));
        List<String> notifications = new ArrayList<>();
        List<String> notificationKeys = List.of("NOTIFICATION.APPOINTMENT_EMAIL_ENABLED","NOTIFICATION.APPROVAL_EMAIL_ENABLED","NOTIFICATION.SECURITY_ALERT_EMAIL_ENABLED");
        notificationKeys.forEach(key -> booleanSetting(s,key,notifications));
        result.add(step("notifications","Notification preferences",notifications,notificationKeys));
        List<String> privacy = new ArrayList<>(); missing(s,"PRIVACY.CONSENT_VERSION",privacy);
        for (String dataset : List.of("EMPLOYEE","VISITOR","APPOINTMENT","ESSENTIAL_LOG")) {
            if (count("select count(*) from data_retention_policy where dataset=? and enabled and hot_days between 1 and 3650 and warm_months between 1 and 240 and archive_years between 1 and 25 and hot_days<=warm_months*31 and warm_months<=archive_years*12 and disposal_action in ('DELETE','ANONYMIZE')", dataset) != 1) privacy.add("Configure a valid enabled " + dataset + " retention policy in Data governance");
        }
        result.add(step("privacy","Privacy notice",privacy,List.of("PRIVACY.CONSENT_VERSION")));
        result.add(step("review","Readiness review",result.stream().allMatch(SetupStep::complete) ? List.of() : List.of("Resolve the preceding checklist issues"),List.of()));
        return List.copyOf(result);
    }
    private String fingerprint(Map<String,String> settings) {
        Map<String,String> relevant = new TreeMap<>();
        settings.forEach((key,value)->{if(List.of("COMPANY.","APPOINTMENT.","APPROVAL.","NOTIFICATION.","PRIVACY.").stream().anyMatch(key::startsWith)) relevant.put(key,value);});
        StringBuilder source = new StringBuilder(officeZone).append(relevant);
        // Only readiness inputs are read. Passwords, MFA material, names, emails and login
        // timestamps are irrelevant and never enter this digest.
        List<String> queries = List.of(
            "select setting_key||':'||version from system_setting where setting_key like 'COMPANY.%' or setting_key like 'APPOINTMENT.%' or setting_key like 'APPROVAL.%' or setting_key like 'NOTIFICATION.%' or setting_key like 'PRIVACY.%' order by setting_key",
            "select id::text||':'||code||':'||active from org_department order by id",
            "select a.id::text||':'||coalesce(a.employee_id::text,'')||':'||a.account_status||':'||a.enabled||':'||a.archived||':'||r.role_name from iam_user_account a join iam_user_role r on r.user_id=a.id where exists(select 1 from iam_user_role lead where lead.user_id=a.id and lead.role_name in ('ROLE_CEO','ROLE_HR_ADMIN','ROLE_MANAGER')) order by a.id,r.role_name",
            "select d.user_id::text||':'||d.permission_name from iam_user_permission_deny d where d.permission_name in ('CEO_VISIT_APPROVE','HR_VISIT_APPROVE','MANAGER_VISIT_APPROVE') order by d.user_id,d.permission_name",
            "select id::text||':'||department_id||':'||hr_user_id||':'||hr_employee_id||':'||active from department_hr_assignment order by id",
            "select id::text||':'||department_id||':'||manager_user_id||':'||manager_employee_id||':'||active from department_manager_assignment order by id",
            "select e.id::text||':'||e.department_id||':'||e.status from employee e where exists(select 1 from iam_user_account a join iam_user_role r on r.user_id=a.id where a.employee_id=e.id and r.role_name in ('ROLE_CEO','ROLE_HR_ADMIN','ROLE_MANAGER')) or exists(select 1 from department_hr_assignment h where h.hr_employee_id=e.id and h.active) or exists(select 1 from department_manager_assignment m where m.manager_employee_id=e.id and m.active) order by e.id",
            "select dataset||':'||hot_days||':'||warm_months||':'||archive_years||':'||enabled||':'||disposal_action from data_retention_policy order by dataset"
        );
        for(String query : queries) source.append(jdbc.queryForList(query, String.class));
        return hash(source.toString());
    }
    private void require(UUID actor) {
        var active = authority.requireActive(actor);
        if (!active.role().equals("ROLE_SYSTEM_ADMIN") || !active.permissions().contains("SYSTEM_CONFIGURE")) throw error("SETUP_SCOPE_DENIED", "Company setup requires the current System Admin configuration permission", HttpStatus.FORBIDDEN);
    }
    private long count(String sql,Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args)); }
    private static SetupStep step(String id,String title,List<String> issues,List<String> keys) { return new SetupStep(id,title,issues.isEmpty(),List.copyOf(issues),keys); }
    private static void missing(Map<String,String> s,String key,List<String> issues) { if (s.getOrDefault(key,"").isBlank()) issues.add("Configure " + label(key)); }
    private static void bounded(Map<String,String> s,String key,int min,int max,List<String> issues) { try { int v=Integer.parseInt(s.getOrDefault(key,"")); if(v<min||v>max) throw new IllegalArgumentException(); } catch(IllegalArgumentException e) { issues.add("Configure " + label(key) + " between " + min + " and " + max); } }
    private static void booleanSetting(Map<String,String> s,String key,List<String> issues) { if (!Set.of("true","false").contains(s.getOrDefault(key,"").toLowerCase(Locale.ROOT))) issues.add("Choose whether " + label(key) + " is enabled"); }
    private static String label(String key) {
        return switch(key) {
            case "COMPANY.NAME" -> "company name";
            case "COMPANY.EMAIL_DOMAIN" -> "company email domain";
            case "COMPANY.HQ_ADDRESS" -> "office arrival address";
            case "COMPANY.SUPPORT_EMAIL" -> "support email";
            case "APPOINTMENT.SLOT_MINUTES" -> "appointment duration in minutes";
            case "APPOINTMENT.MAX_ADVANCE_DAYS" -> "advance booking window in days";
            case "APPOINTMENT.MIN_LEAD_MINUTES" -> "minimum booking lead time in minutes";
            case "APPOINTMENT.CHECK_IN_EARLY_MINUTES" -> "early check-in window in minutes";
            case "APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END" -> "visitor-pass expiry in minutes";
            case "APPROVAL.INTERVIEW.REQUIRES_HR" -> "interview HR approval";
            case "NOTIFICATION.APPOINTMENT_EMAIL_ENABLED" -> "visitor booking email";
            case "NOTIFICATION.APPROVAL_EMAIL_ENABLED" -> "approval queue email";
            case "NOTIFICATION.SECURITY_ALERT_EMAIL_ENABLED" -> "security alert email";
            case "PRIVACY.CONSENT_VERSION" -> "privacy notice version";
            default -> "this preference";
        };
    }
    public static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static BusinessException error(String code,String message,HttpStatus status) { return new BusinessException(code,message,status); }
    @JsonInclude(JsonInclude.Include.ALWAYS) public record SetupState(String policyVersion,long revision,String status,String currentStep,Instant completedAt,String officeZone,List<SetupStep> steps) {}
    @JsonInclude(JsonInclude.Include.ALWAYS) public record SetupStep(String id,String title,boolean complete,List<String> issues,List<String> settingKeys) {}
}
