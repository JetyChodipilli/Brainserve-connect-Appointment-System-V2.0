package com.brainserve.appointment.bulkimport.application;

import com.brainserve.appointment.appointment.api.VisitorImport;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.employee.api.EmployeeProfileImport;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.organization.api.DepartmentCommands;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class BulkImportService {
    private static final Map<ImportKind,List<String>> COLUMNS=Map.of(
            ImportKind.DEPARTMENTS,List.of("code","name"),
            ImportKind.EMPLOYEES,List.of("firstName","lastName","officialEmail","phoneNumber","departmentCode","designation","joiningDate"),
            ImportKind.VISITORS,List.of("type","visitorName","visitorEmail","visitorPhone","visitorCompany","hostEmployeeId","departmentCode","requestedEmployeeId","slotStart","slotEnd","purpose"));
    private static final Map<ImportKind,Set<String>> OPTIONAL=Map.of(ImportKind.DEPARTMENTS,Set.of(),ImportKind.EMPLOYEES,Set.of("lastName","phoneNumber"),ImportKind.VISITORS,Set.of("visitorCompany","requestedEmployeeId"));
    public static final String ROLLBACK_NOTICE="Each applied row is committed independently. Employee profiles have no login credentials; invitations remain separate. Visitor registrations retain their normal approval stages and may send downstream notifications. Applied records are not automatically deleted or undone; use the existing governed correction/cancellation workflows.";
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final CurrentAccountAuthority authority;
    private final DepartmentCommands departments; private final EmployeeProfileImport employees; private final VisitorImport visitors;
    private final AuditService audit; private final TransactionTemplate transactions;
    public BulkImportService(JdbcTemplate jdbc,ObjectMapper json,CurrentAccountAuthority authority,DepartmentCommands departments,
                             EmployeeProfileImport employees,VisitorImport visitors,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.json=json;this.authority=authority;this.departments=departments;this.employees=employees;this.visitors=visitors;this.audit=audit;this.transactions=new TransactionTemplate(manager);
        this.transactions.setTimeout(30);
    }
    public Options options(UUID actor) {var a=authority.requireActive(actor);return new Options(Arrays.stream(ImportKind.values()).filter(k->allowed(a,k)).toList(),ImportCsv.MAX_ROWS,ImportCsv.MAX_BYTES,List.of("SKIP","FAIL"));}
    public Template template(UUID actor,ImportKind kind) {require(actor,kind);List<String> c=COLUMNS.get(kind);return new Template(kind,kind.name().toLowerCase(Locale.ROOT)+"-template.csv",String.join(",",c)+"\r\n",c);}

    public ImportJob preview(UUID actor,ImportKind kind,DuplicatePolicy policy,String csv) {
        require(actor,kind);
        List<Map<String,String>> parsed=ImportCsv.parse(csv,COLUMNS.get(kind),OPTIONAL.get(kind));
        UUID id=UUID.randomUUID();Instant now=Instant.now();String checksum=hash(kind.name()+"\n"+policy.name()+"\n"+csv);
        return transactions.execute(tx->{
            String auth=authorityFingerprint(actor);Set<String> seen=new HashSet<>();
            jdbc.update("insert into bulk_import_job(id,creator_id,kind,duplicate_policy,status,checksum,authority_fingerprint,scope_fingerprint,created_at,expires_at,total_rows) values(?,?,?,?,'PREVIEW',?,?,?,?, ?,?)",id,actor,kind.name(),policy.name(),checksum,auth,scopeFingerprint(actor),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now.plus(Duration.ofHours(24))),parsed.size());
            int number=1;
            for(Map<String,String> values:parsed) {
                number++;List<String> errors=new ArrayList<>();Map<String,String> refs=new TreeMap<>();String status="VALID";
                try { validate(actor,kind,values);refs=references(kind,values,false);
                    String identity=identity(kind,values);
                    if(!seen.add(identity)||duplicate(kind,values)) {status=policy==DuplicatePolicy.SKIP?"SKIPPED":"FAILED";errors.add("Matching identity already exists; create-only import will not overwrite it");}
                } catch(BusinessException e) {status="FAILED";errors.add(e.getMessage());}
                  catch(IllegalArgumentException e) {status="FAILED";errors.add("Row contains an invalid date, identifier, type or value");}
                jdbc.update("insert into bulk_import_row(job_id,row_number,status,values_json,references_json,errors_json) values(?,?,?,?::jsonb,?::jsonb,?::jsonb)",id,number,status,write(values),write(refs),write(errors));
            }
            require(actor,kind);audit.record("BULK_IMPORT_PREVIEWED","BULK_IMPORT",id.toString(),"{\"rows\":"+parsed.size()+"}");
            return load(actor,id,false);
        });
    }

    public ImportJob get(UUID actor,UUID id) {return transactions.execute(tx->load(actor,id,false));}
    public ImportJob execute(UUID actor,UUID id,String checksum,String key) {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,100}")) throw error("IMPORT_KEY_INVALID","Use an idempotency key containing 8-100 letters, numbers or ._:-",HttpStatus.BAD_REQUEST);
        ImportKind kind=transactions.execute(tx->{
            Map<String,Object> job=job(actor,id,true);ImportKind k=ImportKind.valueOf((String)job.get("kind"));require(actor,k);
            if(!Objects.equals(checksum,job.get("checksum"))) throw error("IMPORT_CHECKSUM_MISMATCH","The confirmation must match the exact preview checksum",HttpStatus.CONFLICT);
            expire(job);
            if(job.get("status").equals("EXPIRED")) throw error("IMPORT_EXPIRED","This preview expired; upload a new CSV",HttpStatus.CONFLICT);
            jdbc.update("insert into bulk_import_execution_key(creator_id,idempotency_key,job_id) values(?,?,?) on conflict do nothing",actor,key,id);
            UUID bound=jdbc.queryForObject("select job_id from bulk_import_execution_key where creator_id=? and idempotency_key=?",UUID.class,actor,key);
            if(!id.equals(bound)) throw error("IMPORT_KEY_CONFLICT","This idempotency key belongs to a different import job",HttpStatus.CONFLICT);
            if(!job.get("status").equals("COMPLETED")) jdbc.update("update bulk_import_job set status='RUNNING' where id=?",id);
            return k;
        });
        List<Integer> numbers=jdbc.query("select row_number from bulk_import_row where job_id=? and status='VALID' order by row_number",(rs,n)->rs.getInt(1),id);
        for(int number:numbers) {
            try {transactions.executeWithoutResult(tx->applyRow(actor,id,kind,number));}
            catch(RuntimeException ex) {
                // Failed business transactions roll back both the attempted effect and row state.
                // A separate short transaction persists a sanitized terminal failure; a concurrent
                // successful executor's terminal outcome is never overwritten.
                transactions.executeWithoutResult(tx->{jdbc.queryForList("select row_number from bulk_import_row where job_id=? and row_number=? for update",id,number);
                    String message=ex instanceof BusinessException b?b.getMessage():"Row could not be applied; its transaction was rolled back";
                    jdbc.update("update bulk_import_row set status='FAILED',errors_json=?::jsonb where job_id=? and row_number=? and status='VALID'",write(List.of(message)),id,number);
                });
            }
        }
        return transactions.execute(tx->{
            job(actor,id,true);long remaining=Objects.requireNonNull(jdbc.queryForObject("select count(*) from bulk_import_row where job_id=? and status='VALID'",Long.class,id));
            if(remaining==0) jdbc.update("update bulk_import_job set status='COMPLETED' where id=? and status<>'EXPIRED'",id);
            audit.record("BULK_IMPORT_EXECUTED","BULK_IMPORT",id.toString(),"{\"remaining\":"+remaining+"}");
            return load(actor,id,false);
        });
    }
    private void applyRow(UUID actor,UUID id,ImportKind kind,int number) {
        Map<String,Object> row=jdbc.queryForMap("select * from bulk_import_row where job_id=? and row_number=? for update",id,number);
        if(!row.get("status").equals("VALID")) return;
        Map<String,Object> job=job(actor,id,false);
        if(job.get("status").equals("EXPIRED")) throw error("IMPORT_EXPIRED","This import job expired",HttpStatus.CONFLICT);
        // ponytail: Short global SHARE locks on authority/assignment writes serialize privileged changes during one row; replace with coordinated per-account/reference version locks if import volume causes contention.
        // These short transaction locks close permission/assignment insert-delete races. They
        // do not survive a row commit or act as in-memory/global job locks.
        jdbc.execute("lock table iam_user_role, iam_user_permission_grant, iam_user_permission_deny, department_hr_assignment, department_manager_assignment, department_team_lead in share mode");
        jdbc.queryForList("select id from iam_user_account where id=? for share",actor);
        require(actor,kind);
        if(!authorityFingerprint(actor).equals(job.get("authority_fingerprint"))) throw error("IMPORT_AUTHORITY_CHANGED","Account role, permissions or department assignment changed after preview",HttpStatus.CONFLICT);
        if(Instant.now().isAfter(timestamp(job,"expires_at"))) throw error("IMPORT_EXPIRED","Import preview expired before this row was applied",HttpStatus.CONFLICT);
        Map<String,String> values=map(row.get("values_json"));Map<String,String> expected=map(row.get("references_json"));
        if(!references(kind,values,true).equals(expected)) throw error("IMPORT_REFERENCE_CHANGED","A department, host, policy or leadership reference changed after preview; create a fresh preview",HttpStatus.CONFLICT);
        validate(actor,kind,values);
        if(duplicate(kind,values)) {
            String status=job.get("duplicate_policy").equals("SKIP")?"SKIPPED":"FAILED";
            jdbc.update("update bulk_import_row set status=?,errors_json=?::jsonb where job_id=? and row_number=?",status,write(List.of("Matching identity already exists; create-only import will not overwrite it")),id,number);return;
        }
        UUID recordId=switch(kind) {
            case DEPARTMENTS -> departments.createId(values.get("code").toUpperCase(Locale.ROOT),values.get("name"));
            case EMPLOYEES -> employees.importProfile(actor,new EmployeeProfileImport.Profile(values.get("firstName"),values.get("lastName"),values.get("officialEmail"),values.get("phoneNumber"),department(values.get("departmentCode")),values.get("designation"),LocalDate.parse(values.get("joiningDate"))));
            case VISITORS -> visitors.importPendingVisit(actor,"bulk-"+id+"-"+number,visit(values));
        };
        require(actor,kind);
        jdbc.update("update bulk_import_row set status='APPLIED',record_id=?,errors_json='[]'::jsonb where job_id=? and row_number=?",recordId,id,number);
    }
    public ErrorExport errors(UUID actor,UUID id) {
        return transactions.execute(tx->{ImportJob job=load(actor,id,false);List<String> columns=COLUMNS.get(job.kind());StringBuilder out=new StringBuilder("rowNumber,status,errors,").append(String.join(",",columns)).append("\r\n");
            for(ImportRow row:job.rows()) if(!row.errors().isEmpty()) {out.append(ImportCsv.exportCell(Integer.toString(row.rowNumber()))).append(',').append(ImportCsv.exportCell(row.status())).append(',').append(ImportCsv.exportCell(String.join("; ",row.errors())));for(String col:columns) out.append(',').append(ImportCsv.exportCell(row.values().get(col)));out.append("\r\n");}
            require(actor,job.kind());audit.record("BULK_IMPORT_ERRORS_DOWNLOADED","BULK_IMPORT",id.toString(),"{\"rows\":"+job.failed()+"}");return new ErrorExport("import-"+id+"-errors.csv",out.toString());});
    }
    private ImportJob load(UUID actor,UUID id,boolean lock) {
        Map<String,Object> job=job(actor,id,lock);ImportKind kind=ImportKind.valueOf((String)job.get("kind"));require(actor,kind);expire(job);
        if (!scopeFingerprint(actor).equals(job.get("scope_fingerprint"))) throw error("IMPORT_SCOPE_CHANGED", "The current department scope no longer permits access to this job's uploaded data", HttpStatus.FORBIDDEN);
        List<ImportRow> rows=jdbc.query("select * from bulk_import_row where job_id=? order by row_number",(rs,n)->new ImportRow(rs.getInt("row_number"),rs.getString("status"),map(rs.getString("values_json")),list(rs.getString("errors_json")),rs.getObject("record_id",UUID.class)),id);
        require(actor,kind);
        if (!scopeFingerprint(actor).equals(job.get("scope_fingerprint"))) throw error("IMPORT_SCOPE_CHANGED", "The current department scope no longer permits access to this job's uploaded data", HttpStatus.FORBIDDEN);
        return new ImportJob(id,kind,DuplicatePolicy.valueOf((String)job.get("duplicate_policy")),(String)job.get("status"),(String)job.get("checksum"),timestamp(job,"created_at"),timestamp(job,"expires_at"),((Number)job.get("total_rows")).intValue(),count(rows,"APPLIED"),count(rows,"SKIPPED"),count(rows,"FAILED"),rows,ROLLBACK_NOTICE);
    }
    private Map<String,Object> job(UUID actor,UUID id,boolean lock) {
        List<Map<String,Object>> result=jdbc.queryForList("select * from bulk_import_job where id=? and creator_id=?"+(lock?" for update":""),id,actor);
        if(result.isEmpty()) throw error("IMPORT_NOT_FOUND","Import job was not found",HttpStatus.NOT_FOUND);return result.getFirst();
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${brainserve.imports.cleanup-delay-ms:3600000}")
    public void clearExpiredUploadData() {
        // A bounded sweep prevents abandoned previews from retaining uploaded PII indefinitely.
        List<UUID> ids=jdbc.query("select j.id from bulk_import_job j where j.expires_at<=now() and exists(select 1 from bulk_import_row r where r.job_id=j.id and r.values_json<>'{}'::jsonb) order by j.expires_at limit 100",(rs,n)->rs.getObject(1,UUID.class));
        for(UUID id:ids) transactions.executeWithoutResult(tx->{
            List<Map<String,Object>> jobs=jdbc.queryForList("select * from bulk_import_job where id=? for update skip locked",id);
            if(!jobs.isEmpty()) expire(jobs.getFirst());
        });
    }
    private void expire(Map<String,Object> job) {
        if(Instant.now().isBefore(timestamp(job,"expires_at"))) return;
        UUID id=(UUID)job.get("id");
        // Erase uploaded PII on expired-job access or the next bounded hourly cleanup batch, while preserving outcomes and execution
        // keys. Existing business records are governed by their own retention workflows.
        jdbc.update("update bulk_import_row set values_json='{}'::jsonb,references_json='{}'::jsonb where job_id=? and values_json<>'{}'::jsonb",id);
        if(!job.get("status").equals("COMPLETED")) {jdbc.update("update bulk_import_job set status='EXPIRED' where id=? and status<>'COMPLETED'",id);job.put("status","EXPIRED");}
    }
    private void validate(UUID actor,ImportKind kind,Map<String,String> v) {
        switch(kind) {
            case DEPARTMENTS -> {required(v,"code",20);required(v,"name",120);if(!v.get("code").toUpperCase(Locale.ROOT).matches("[A-Z0-9_-]{2,20}")) throw invalid("Department code must contain 2-20 letters, numbers, underscores or hyphens");}
            case EMPLOYEES -> {required(v,"firstName",80);bounded(v,"lastName",80);required(v,"officialEmail",180);email(v.get("officialEmail"));bounded(v,"phoneNumber",30);required(v,"designation",120);LocalDate date=LocalDate.parse(v.get("joiningDate"));if(date.isAfter(LocalDate.now().plusYears(1))) throw invalid("Joining date cannot be more than one year ahead");employees.requireImportDepartment(actor,department(v.get("departmentCode")));}
            case VISITORS -> {required(v,"visitorName",170);required(v,"visitorEmail",180);email(v.get("visitorEmail"));required(v,"visitorPhone",30);bounded(v,"visitorCompany",160);required(v,"purpose",1000);VisitorImport.Visit visit=visit(v);if(!visit.slotStart().isAfter(Instant.now())||!visit.slotEnd().isAfter(visit.slotStart())) throw invalid("Visitor slots must be future instants with end after start");visitors.validateImport(visit);}
        }
    }
    private Map<String,String> references(ImportKind kind,Map<String,String> v,boolean lock) {
        Map<String,String> refs=new TreeMap<>();if(kind==ImportKind.DEPARTMENTS) return refs;
        UUID dep=department(v.get("departmentCode"));String tail=lock?" for share":"";
        refs.put("department",jdbc.queryForObject("select id::text||':'||version||':'||active from org_department where id=?"+(lock?" for update":""),String.class,dep));
        if(kind==ImportKind.VISITORS) {
            UUID host=UUID.fromString(v.get("hostEmployeeId"));
            refs.put("host",jdbc.queryForObject("select id::text||':'||version||':'||status||':'||department_id from employee where id=?"+tail,String.class,host));
            if(!v.get("requestedEmployeeId").isBlank()) {UUID employee=UUID.fromString(v.get("requestedEmployeeId"));refs.put("requested",jdbc.queryForObject("select id::text||':'||version||':'||status||':'||department_id from employee where id=?"+tail,String.class,employee));}
            for(String table:List.of("department_hr_assignment","department_manager_assignment","department_team_lead")) {
                refs.put(table,hash(jdbc.queryForObject("select coalesce(string_agg(id::text||':'||version||':'||active,',' order by id),'') from "+table+" where department_id=?",String.class,dep)));
                String userColumn=table.equals("department_hr_assignment")?"hr_user_id":table.equals("department_manager_assignment")?"manager_user_id":"team_lead_user_id";
                List<UUID> reviewers=jdbc.query("select "+userColumn+" from "+table+" where department_id=? and active order by "+userColumn,(rs,n)->rs.getObject(1,UUID.class),dep);
                if(lock) for(UUID reviewer:reviewers) jdbc.queryForList("select id from iam_user_account where id=? for share",reviewer);
                refs.put(table+"Authority",hash(reviewers.stream().map(account->{try{return account+":"+authorityFingerprint(account);}catch(BusinessException ex){return account+":INACTIVE";}}).toList().toString()));
            }
            List<Map<String,Object>> accounts=jdbc.queryForList("select id from iam_user_account where employee_id=? order by id"+tail,host);
            refs.put("hostAuthority",hash(accounts.stream().map(a->{UUID account=(UUID)a.get("id");try{return account+":"+authorityFingerprint(account);}catch(BusinessException ex){return account+":INACTIVE";}}).toList().toString()));
            if (lock) jdbc.queryForList("select id from system_setting where setting_key like 'APPOINTMENT.%' or setting_key like 'APPROVAL.%' order by setting_key for share");
            refs.put("policy",hash(jdbc.queryForObject("select coalesce(string_agg(setting_key||':'||version||':'||setting_value,',' order by setting_key),'') from system_setting where setting_key like 'APPOINTMENT.%' or setting_key like 'APPROVAL.%'",String.class)));
        }
        return refs;
    }
    private String authorityFingerprint(UUID actor) {
        var a=authority.requireActive(actor);List<String> perms=new ArrayList<>(a.permissions());Collections.sort(perms);
        String scope=switch(a.role()) {
            case "ROLE_HR_ADMIN" -> assignment("department_hr_assignment","hr_user_id",actor);
            case "ROLE_TEAM_LEAD" -> assignment("department_team_lead","team_lead_user_id",actor);
            case "ROLE_MANAGER" -> assignment("department_manager_assignment","manager_user_id",actor);
            case "ROLE_EMPLOYEE" -> a.employeeId()==null?"MISSING":jdbc.queryForObject("select department_id::text||':'||version||':'||status from employee where id=?",String.class,a.employeeId());
            default -> "COMPANY";
        };return hash(a.role()+":"+a.employeeId()+":"+perms+":"+scope);
    }
    private String scopeFingerprint(UUID actor) {
        var a=authority.requireActive(actor);
        String scope=switch(a.role()) {
            case "ROLE_HR_ADMIN" -> scopeDepartment("department_hr_assignment","hr_user_id",actor);
            case "ROLE_TEAM_LEAD" -> scopeDepartment("department_team_lead","team_lead_user_id",actor);
            case "ROLE_MANAGER" -> scopeDepartment("department_manager_assignment","manager_user_id",actor);
            case "ROLE_EMPLOYEE" -> a.employeeId()==null?"MISSING":jdbc.queryForObject("select department_id::text from employee where id=?",String.class,a.employeeId());
            default -> "COMPANY";
        };
        return hash(scope);
    }
    private String scopeDepartment(String table,String userColumn,UUID actor) {
        return jdbc.queryForObject("select coalesce(string_agg(department_id::text,',' order by department_id),'MISSING') from "+table+" where "+userColumn+"=? and active",String.class,actor);
    }
    private String assignment(String table,String userColumn,UUID actor) {return jdbc.queryForObject("select coalesce(string_agg(id::text||':'||department_id||':'||version||':'||active,',' order by id),'MISSING') from "+table+" where "+userColumn+"=? and active",String.class,actor);}
    private UUID department(String code) {List<UUID> ids=jdbc.query("select id from org_department where lower(code)=lower(?) and active",(rs,n)->rs.getObject(1,UUID.class),code);if(ids.size()!=1) throw invalid("Select an existing active department code");return ids.getFirst();}
    private VisitorImport.Visit visit(Map<String,String> v) {return new VisitorImport.Visit(v.get("type"),v.get("visitorName"),v.get("visitorEmail"),v.get("visitorPhone"),v.get("visitorCompany"),UUID.fromString(v.get("hostEmployeeId")),department(v.get("departmentCode")),v.get("requestedEmployeeId").isBlank()?null:UUID.fromString(v.get("requestedEmployeeId")),Instant.parse(v.get("slotStart")),Instant.parse(v.get("slotEnd")),v.get("purpose"));}
    private boolean duplicate(ImportKind kind,Map<String,String> v) {return switch(kind) {
        case DEPARTMENTS -> count("select count(*) from org_department where lower(code)=lower(?)",v.get("code"))>0;
        case EMPLOYEES -> count("select count(*) from employee where lower(official_email)=lower(?)",v.get("officialEmail"))>0;
        case VISITORS -> count("select count(*) from appointment where lower(visitor_email)=lower(?) and host_employee_id=? and slot_start=?",v.get("visitorEmail"),UUID.fromString(v.get("hostEmployeeId")),java.sql.Timestamp.from(Instant.parse(v.get("slotStart"))))>0;
    };}
    private String identity(ImportKind kind,Map<String,String> v) {return switch(kind) {case DEPARTMENTS -> v.get("code").toLowerCase(Locale.ROOT);case EMPLOYEES -> v.get("officialEmail").toLowerCase(Locale.ROOT);case VISITORS -> v.get("visitorEmail").toLowerCase(Locale.ROOT)+":"+v.get("hostEmployeeId")+":"+Instant.parse(v.get("slotStart"));};}
    private void require(UUID actor,ImportKind kind) {if(!allowed(authority.requireActive(actor),kind)) throw error("IMPORT_SCOPE_DENIED","Your current role or permissions do not permit this import type",HttpStatus.FORBIDDEN);}
    private static boolean allowed(CurrentAccountAuthority.Authority a,ImportKind kind) {return switch(kind) {
        case DEPARTMENTS -> (a.role().equals("ROLE_SYSTEM_ADMIN")&&a.permissions().contains("SYSTEM_CONFIGURE"))||(a.role().equals("ROLE_CEO")&&a.permissions().contains("DEPARTMENT_MANAGE"))||a.permissions().contains("DEPARTMENT_MANAGE");
        case EMPLOYEES -> a.permissions().contains("EMPLOYEE_CREATE");
        case VISITORS -> a.permissions().contains("VISITOR_REGISTER");
    };}
    private long count(String sql,Object... args) {return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
    private static int count(List<ImportRow> rows,String state) {return (int)rows.stream().filter(r->r.status().equals(state)).count();}
    private static Instant timestamp(Map<String,Object> map,String key) {return ((java.sql.Timestamp)map.get(key)).toInstant();}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Import state serialization failed",ex);}}
    private Map<String,String> map(Object value) {try{return json.readValue(value.toString(),new TypeReference<LinkedHashMap<String,String>>(){});}catch(Exception ex){throw new IllegalStateException("Import state deserialization failed",ex);}}
    private List<String> list(String value) {try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception ex){throw new IllegalStateException("Import errors deserialization failed",ex);}}
    private static void required(Map<String,String> v,String key,int max) {bounded(v,key,max);if(v.getOrDefault(key,"").isBlank()) throw invalid(key+" is required");}
    private static void bounded(Map<String,String> v,String key,int max) {if(v.getOrDefault(key,"").length()>max) throw invalid(key+" exceeds "+max+" characters");}
    private static void email(String value) {if(!value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw invalid("Email must be valid");}
    private static BusinessException invalid(String message) {return error("IMPORT_ROW_INVALID",message,HttpStatus.BAD_REQUEST);}
    private static BusinessException error(String code,String message,HttpStatus status) {return new BusinessException(code,message,status);}
    private static String hash(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    public enum ImportKind {DEPARTMENTS,EMPLOYEES,VISITORS}
    public enum DuplicatePolicy {SKIP,FAIL}
    @JsonInclude(JsonInclude.Include.ALWAYS) public record Options(List<ImportKind> allowedKinds,int maxRows,int maxBytes,List<String> duplicatePolicies) {}
    @JsonInclude(JsonInclude.Include.ALWAYS) public record Template(ImportKind kind,String filename,String csv,List<String> columns) {}
    @JsonInclude(JsonInclude.Include.ALWAYS) public record ImportJob(UUID id,ImportKind kind,DuplicatePolicy duplicatePolicy,String status,String checksum,Instant createdAt,Instant expiresAt,int totalRows,int applied,int skipped,int failed,List<ImportRow> rows,String rollbackNotice) {}
    @JsonInclude(JsonInclude.Include.ALWAYS) public record ImportRow(int rowNumber,String status,Map<String,String> values,List<String> errors,UUID recordId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS) public record ErrorExport(String filename,String csv) {}
}
