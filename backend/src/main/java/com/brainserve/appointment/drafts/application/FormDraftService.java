package com.brainserve.appointment.drafts.application;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.worktask.api.WorkTaskDraftSubmission;
import com.brainserve.appointment.workinsight.api.WorkInsightDraftSubmission;
import com.brainserve.appointment.appointment.api.AppointmentDraftSubmission;
import com.brainserve.appointment.configuration.api.CompanyDraftSubmission;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** One bounded draft per account/form/context. PostgreSQL revisions serialize concurrent tabs and final business writes. */
@Service
public class FormDraftService {
    public enum Form { TASK_CREATE, TASK_UPDATE, VISIT_INTAKE, COMPANY_PROFILE }
    private static final Map<Form,Map<String,Integer>> FIELDS=Map.of(
        Form.TASK_CREATE,Map.of("employeeId",36,"title",160,"description",1000,"dueDate",10),
        Form.TASK_UPDATE,Map.of("note",1000,"taskVersion",20),
        Form.VISIT_INTAKE,Map.ofEntries(Map.entry("visitType",40),Map.entry("visitorName",170),Map.entry("visitorEmail",180),Map.entry("visitorPhone",30),Map.entry("visitorCompany",160),Map.entry("hostEmployeeId",36),Map.entry("routingDepartmentId",36),Map.entry("requestedEmployeeId",36),Map.entry("visitDate",10),Map.entry("purpose",1000)),
        Form.COMPANY_PROFILE,Map.of("COMPANY.NAME",2000,"COMPANY.EMAIL_DOMAIN",2000,"COMPANY.HQ_ADDRESS",2000,"COMPANY.SUPPORT_EMAIL",2000));
    private static final Set<String> VISIT_FINAL=Set.of("type","visitorName","visitorEmail","visitorPhone","visitorCompany","hostEmployeeId","routingDepartmentId","requestedEmployeeId","slotStart","slotEnd","purpose","identityDocumentType","identityDocumentLastFour","notes");
    private final JdbcTemplate jdbc;private final CurrentAccountAuthority authority;private final WorkTaskDraftSubmission tasks;
    private final WorkInsightDraftSubmission insights;private final AppointmentDraftSubmission visits;private final CompanyDraftSubmission company;
    private final ObjectMapper mapper;private final SensitiveStringConverter cipher;private final int expiryDays;
    public FormDraftService(JdbcTemplate jdbc,CurrentAccountAuthority authority,WorkTaskDraftSubmission tasks,WorkInsightDraftSubmission insights,AppointmentDraftSubmission visits,CompanyDraftSubmission company,ObjectMapper mapper,SensitiveStringConverter cipher,@Value("${brainserve.drafts.expiry-days:7}") int expiryDays){
        if(expiryDays<1||expiryDays>30)throw new IllegalArgumentException("brainserve.drafts.expiry-days must be between 1 and 30");
        this.jdbc=jdbc;this.authority=authority;this.tasks=tasks;this.insights=insights;this.visits=visits;this.company=company;this.mapper=mapper;this.cipher=cipher;this.expiryDays=expiryDays;
    }
    @Transactional(readOnly=true)
    public Draft read(UUID owner,Form form,String context){String stamp=scope(owner,form,context);var row=find(owner,form,context,false);if(row==null)return null;matchScope(row,stamp);var result=view(row);if(!stamp.equals(scope(owner,form,context)))throw missing();return result;}
    @Transactional
    public Draft save(UUID owner,Form form,String context,Save request){
        String stamp=scope(owner,form,context);check(request);var fields=allowlist(form,request.fields());
        // An expired row can be replaced, but submitted receipts are retained until their original expiry.
        jdbc.update("delete from owned_form_draft where owner_id=? and form_type=? and context_key=? and expires_at<=now()",owner,form.name(),context);
        Row row=find(owner,form,context,true);Instant now=Instant.now(),expiry=now.plus(expiryDays,ChronoUnit.DAYS);
        if(row==null){if(request.expectedRevision()!=0)throw conflict();int created=jdbc.update("insert into owned_form_draft(owner_id,form_type,context_key,schema_version,revision,scope_stamp,fields_ciphertext,submission_key,updated_at,expires_at) values(?,?,?,1,1,?,?,?,?,?) on conflict do nothing",owner,form.name(),context,stamp,encrypted(fields),UUID.randomUUID(),Timestamp.from(now),Timestamp.from(expiry));if(created!=1)throw conflict();}
        else{matchScope(row,stamp);if(row.submittedAt()!=null||request.expectedRevision()!=row.revision())throw conflict();jdbc.update("update owned_form_draft set fields_ciphertext=?,revision=revision+1,updated_at=?,expires_at=? where owner_id=? and form_type=? and context_key=?",encrypted(fields),Timestamp.from(now),Timestamp.from(expiry),owner,form.name(),context);}
        if(!stamp.equals(scope(owner,form,context)))throw missing();return view(Objects.requireNonNull(find(owner,form,context,false)));
    }
    @Transactional
    public void discard(UUID owner,Form form,String context,Long revision){
        // Discard needs current ownership and form permission, but it never exposes former fields.
        String stamp=scope(owner,form,context);if(revision==null||revision<1)throw invalid();Row row=find(owner,form,context,true);if(row==null)return;matchScope(row,stamp);if(row.revision()!=revision)throw conflict();
        jdbc.update("delete from owned_form_draft where owner_id=? and form_type=? and context_key=?",owner,form.name(),context);
    }
    @Transactional
    public Receipt submit(UUID owner,Form form,String context,Submit request){
        String stamp=scope(owner,form,context);if(request==null||request.expectedRevision()==null||request.expectedRevision()<1||request.submissionKey()==null)throw invalid();
        Row row=find(owner,form,context,true);if(row==null)throw missing();matchScope(row,stamp);
        if(!request.submissionKey().equals(row.submissionKey()))throw conflict();
        if(row.submittedAt()!=null)return new Receipt(row.submissionKey(),row.submittedAt(),row.receipt());
        if(request.expectedRevision()!=row.revision())throw conflict();
        Map<String,String> fields=row.fields();Object result;
        try{result=switch(form){
            case TASK_CREATE->tasks.create(owner,fields);
            case TASK_UPDATE->Set.of("insight-rework","hr-rework").contains(context.split("~",-1)[1])||context.endsWith("~revise-rework")&&!tasks.employeeDelivery(owner,context)?insights.submit(owner,context,fields):tasks.update(owner,context,fields);
            case COMPANY_PROFILE->company.submit(owner,fields);
            case VISIT_INTAKE->{validateVisitFinal(request.finalFields());yield visits.submit(owner,context,row.submissionKey(),request.finalFields());}
        };}catch(IllegalArgumentException exception){throw new BusinessException("DRAFT_SUBMISSION_INVALID","Review the required fields and current selections before submitting",HttpStatus.BAD_REQUEST);}
        if(!stamp.equals(scope(owner,form,context)))throw missing();
        JsonNode value=mapper.valueToTree(result);var receipt=mapper.createObjectNode().put("formType",form.name());
        if(value.hasNonNull("id"))receipt.set("recordId",value.get("id"));if(value.hasNonNull("referenceNumber"))receipt.set("referenceNumber",value.get("referenceNumber"));
        if(form==Form.COMPANY_PROFILE)receipt.put("saved",true);
        Instant now=Instant.now();jdbc.update("update owned_form_draft set submitted_at=?,receipt=?::jsonb,fields_ciphertext=? where owner_id=? and form_type=? and context_key=?",Timestamp.from(now),json(receipt),encrypted(Map.of()),owner,form.name(),context);
        return new Receipt(row.submissionKey(),now,receipt);
    }
    private void validateVisitFinal(Map<String,String> fields){if(fields==null||!VISIT_FINAL.containsAll(fields.keySet())||fields.values().stream().anyMatch(v->v==null||v.length()>1000)||json(fields).getBytes(StandardCharsets.UTF_8).length>8000)throw invalid();}
    private String scope(UUID owner,Form form,String context){
        if(context==null||!context.matches("[a-zA-Z0-9~_-]{1,100}"))throw invalid();var current=authority.requireActive(owner);
        switch(form){case TASK_CREATE->{if(!"new".equals(context))throw invalid();tasks.requireEligible(owner,context);}case TASK_UPDATE->tasks.requireEligible(owner,context);case VISIT_INTAKE->visits.requireEligible(owner,context);case COMPANY_PROFILE->{if(!"company".equals(context))throw invalid();company.requireEligible(owner);}}
        var fingerprint=new TreeMap<String,Object>();fingerprint.put("role",current.role());fingerprint.put("employeeId",String.valueOf(current.employeeId()));fingerprint.put("permissions",new TreeSet<>(current.permissions()));
        if(form==Form.TASK_CREATE||form==Form.TASK_UPDATE)fingerprint.put("workScope",authority.requireWorkScope(owner));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(fingerprint).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void check(Save request){if(request==null||!Objects.equals(request.schemaVersion(),1)||request.expectedRevision()==null||request.expectedRevision()<0)throw invalid();}
    private Map<String,String> allowlist(Form form,Map<String,String> fields){if(fields==null||!FIELDS.get(form).keySet().containsAll(fields.keySet()))throw invalid();for(var entry:fields.entrySet())if(entry.getValue()==null||entry.getValue().length()>FIELDS.get(form).get(entry.getKey())||entry.getValue().indexOf('\u0000')>=0)throw invalid();if(json(fields).getBytes(StandardCharsets.UTF_8).length>16000)throw invalid();return Map.copyOf(fields);}
    private Row find(UUID owner,Form form,String context,boolean lock){var rows=jdbc.query("select * from owned_form_draft where owner_id=? and form_type=? and context_key=? and expires_at>now()"+(lock?" for update":""),(rs,n)->new Row(form,context,rs.getLong("revision"),rs.getString("scope_stamp"),decoded(rs.getString("fields_ciphertext")),rs.getObject("submission_key",UUID.class),rs.getTimestamp("updated_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("submitted_at")==null?null:rs.getTimestamp("submitted_at").toInstant(),rs.getString("receipt")==null?null:tree(rs.getString("receipt"))),owner,form.name(),context);return rows.isEmpty()?null:rows.getFirst();}
    private String encrypted(Map<String,String> fields){return cipher.convertToDatabaseColumn(json(fields));}
    private Map<String,String> decoded(String value){try{return mapper.readValue(cipher.convertToEntityAttribute(value),new TypeReference<Map<String,String>>(){});}catch(Exception exception){throw new IllegalStateException("Draft fields could not be decoded",exception);}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException("Draft could not be encoded",exception);}}
    private JsonNode tree(String value){try{return mapper.readTree(value);}catch(Exception exception){throw new IllegalStateException("Draft receipt could not be decoded",exception);}}
    private static void matchScope(Row row,String stamp){if(!row.stamp().equals(stamp))throw missing();}
    private Draft view(Row row){return new Draft(row.form().name(),row.context(),1,row.revision(),row.fields(),row.submissionKey(),row.updatedAt(),row.expiresAt(),row.submittedAt()==null?null:new Receipt(row.submissionKey(),row.submittedAt(),row.receipt()));}
    @Scheduled(fixedDelayString="${brainserve.drafts.cleanup-ms:3600000}")
    public int cleanupExpired(){return jdbc.update("delete from owned_form_draft where (owner_id,form_type,context_key) in (select owner_id,form_type,context_key from owned_form_draft where expires_at<=now() order by expires_at limit 5000)");}
    private static BusinessException conflict(){return new BusinessException("DRAFT_REVISION_CONFLICT","This draft changed in another tab. Reload and explicitly restore or discard it before continuing",HttpStatus.CONFLICT);}
    private static BusinessException missing(){return new BusinessException("DRAFT_NOT_FOUND","The draft is unavailable in your current scope",HttpStatus.NOT_FOUND);}
    private static BusinessException invalid(){return new BusinessException("DRAFT_FIELDS_INVALID","A supported form schema, revision and allowlisted text fields are required",HttpStatus.BAD_REQUEST);}
    private record Row(Form form,String context,long revision,String stamp,Map<String,String> fields,UUID submissionKey,Instant updatedAt,Instant expiresAt,Instant submittedAt,JsonNode receipt){}
    public record Save(Integer schemaVersion,Long expectedRevision,Map<String,String> fields){}
    public record Submit(Long expectedRevision,UUID submissionKey,Map<String,String> finalFields){}
    public record Draft(String formType,String contextKey,int schemaVersion,long revision,Map<String,String> fields,UUID submissionKey,Instant updatedAt,Instant expiresAt,Receipt receipt){}
    public record Receipt(UUID submissionKey,Instant submittedAt,JsonNode result){}
}
