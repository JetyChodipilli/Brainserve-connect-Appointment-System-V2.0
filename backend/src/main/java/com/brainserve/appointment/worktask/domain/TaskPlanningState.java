package com.brainserve.appointment.worktask.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Retained immutable submission values, independently of the editable next-delivery draft. */
public class TaskPlanningState {
    public long revision;
    public List<ChecklistItem> checklist = new ArrayList<>();
    public List<Evidence> evidence = new ArrayList<>();
    public List<Blocker> blockers = new ArrayList<>();
    public List<Submission> submissions = new ArrayList<>();
    public List<DraftSnapshot> retainedDrafts = new ArrayList<>();
    public UUID draftAuthorEmployeeId;
    public UUID draftAuthorUserId;
    public String draftAuthorName;
    public void touch() { revision++; }
    // Hibernate compares the JSON value against a deserialized snapshot at each flush.
    // Structural equality prevents unchanged snapshots from becoming dirty again at commit.
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TaskPlanningState state)) return false;
        return revision == state.revision && java.util.Objects.equals(checklist,state.checklist)
                && java.util.Objects.equals(evidence,state.evidence) && java.util.Objects.equals(blockers,state.blockers)
                && java.util.Objects.equals(submissions,state.submissions) && java.util.Objects.equals(retainedDrafts,state.retainedDrafts)
                && java.util.Objects.equals(draftAuthorEmployeeId,state.draftAuthorEmployeeId) && java.util.Objects.equals(draftAuthorUserId,state.draftAuthorUserId)
                && java.util.Objects.equals(draftAuthorName,state.draftAuthorName);
    }
    @Override public int hashCode() { return java.util.Objects.hash(revision,checklist,evidence,blockers,submissions,retainedDrafts,draftAuthorEmployeeId,draftAuthorUserId,draftAuthorName); }
    public void requireSubmission(boolean evidenceRequired) {
        if (checklist.stream().anyMatch(i -> i.required() && !i.completed()))
            throw new BusinessException("WORK_TASK_CHECKLIST_REQUIRED", "Complete every required checklist item before submission", HttpStatus.UNPROCESSABLE_ENTITY);
        if (evidenceRequired && evidence.isEmpty())
            throw new BusinessException("WORK_TASK_EVIDENCE_REQUIRED", "Attach the required delivery evidence before submission", HttpStatus.UNPROCESSABLE_ENTITY);
    }
    public void capture(long version, Instant submittedAt) {
        submissions.add(new Submission(version, submittedAt, null, null, List.copyOf(checklist), List.copyOf(evidence)));
    }
    public void capture(long version, Instant submittedAt, UUID employeeId, UUID userId, String name, long assignmentRevision, String update) {
        markDraftAuthor(employeeId,userId,name);
        submissions.add(new Submission(version,submittedAt,null,null,List.copyOf(checklist),List.copyOf(evidence),
                employeeId,userId,name,assignmentRevision,update,userId!=null,false));
    }
    public void retainDraft(UUID employeeId, long assignmentRevision, String update, Instant capturedAt) {
        retainedDrafts.add(new DraftSnapshot(draftAuthorEmployeeId,draftAuthorUserId,draftAuthorName,assignmentRevision,capturedAt,update,List.copyOf(checklist),List.copyOf(evidence),employeeId,draftAuthorUserId!=null));
        checklist=checklist.stream().map(i->new ChecklistItem(i.id(),i.title(),i.position(),i.required(),false)).toList();
        evidence=new ArrayList<>();
        draftAuthorEmployeeId=null;draftAuthorUserId=null;draftAuthorName=null;
        touch();
    }
    public void markDraftAuthor(UUID employeeId,UUID userId,String name) {draftAuthorEmployeeId=employeeId;draftAuthorUserId=userId;draftAuthorName=name;}
    public void accept(Long version, String role) {
        if (version == null) return; // Unknown legacy submissions are never fabricated.
        for (int i = 0; i < submissions.size(); i++) {
            Submission s = submissions.get(i);
            if (s.version() == version && s.acceptedAt() == null)
                submissions.set(i, new Submission(s.version(), s.submittedAt(), Instant.now(), role, s.checklist(), s.evidence(),
                        s.authorEmployeeId(),s.authorUserId(),s.authorName(),s.assignmentRevision(),s.employeeUpdate(),s.authorshipKnown(),false));
        }
    }
    public record ChecklistItem(UUID id, String title, int position, boolean required, boolean completed) {}
    public record Evidence(UUID id, UUID documentId, String filename, String contentType, long sizeBytes, String sha256, Instant createdAt) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Blocker(UUID id, String reason, UUID contactUserId, Instant raisedAt, Instant resolvedAt, UUID raisedBy, UUID resolvedBy, String resolutionReason) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Submission(long version, Instant submittedAt, Instant acceptedAt, String acceptedByRole, List<ChecklistItem> checklist, List<Evidence> evidence,
            UUID authorEmployeeId, UUID authorUserId, String authorName, Long assignmentRevision, String employeeUpdate, boolean authorshipKnown, boolean currentlyAccepted) {
        public Submission(long version,Instant submittedAt,Instant acceptedAt,String acceptedByRole,List<ChecklistItem> checklist,List<Evidence> evidence) {
            this(version,submittedAt,acceptedAt,acceptedByRole,checklist,evidence,null,null,null,null,null,false,false);
        }
        public Submission { checklist = List.copyOf(checklist); evidence = List.copyOf(evidence); }
        public Submission withCurrentAcceptance(boolean value) { return new Submission(version,submittedAt,acceptedAt,acceptedByRole,checklist,evidence,authorEmployeeId,authorUserId,authorName,assignmentRevision,employeeUpdate,authorshipKnown,value); }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record DraftSnapshot(UUID authorEmployeeId,UUID authorUserId,String authorName,long assignmentRevision,Instant capturedAt,String employeeUpdate,List<ChecklistItem> checklist,List<Evidence> evidence,UUID ownerEmployeeId,boolean authorshipKnown) {
        public DraftSnapshot { checklist=List.copyOf(checklist);evidence=List.copyOf(evidence); }
    }
}
