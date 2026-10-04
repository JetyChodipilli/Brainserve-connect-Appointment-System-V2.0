package com.brainserve.appointment.workinsight.api;

import com.brainserve.appointment.workinsight.application.WorkInsightService;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkInsightDraftSubmission {
    private final WorkInsightService service;
    public WorkInsightDraftSubmission(WorkInsightService service){this.service=service;}
    public Object submit(UUID actor,String context,Map<String,String> fields){
        String[] parts=context.split("~",-1);UUID id=UUID.fromString(parts[0]);long version=Long.parseLong(fields.getOrDefault("taskVersion",""));String note=fields.getOrDefault("note","").trim();
        if(version<0||note.length()<5||note.length()>1000)throw new BusinessException("DRAFT_SUBMISSION_INVALID","A valid observed worksheet revision and note are required",HttpStatus.BAD_REQUEST);
        return switch(parts[1]){case "insight-rework"->service.assignRework(actor,id,note,version);case "hr-rework"->service.requestHrRework(actor,id,note,version);case "revise-rework"->service.reviseReworkSubmission(actor,id,note,version);default->throw new BusinessException("DRAFT_SUBMISSION_INVALID","The draft action is invalid",HttpStatus.BAD_REQUEST);};
    }
}
