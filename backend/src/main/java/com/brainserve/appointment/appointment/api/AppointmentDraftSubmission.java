package com.brainserve.appointment.appointment.api;

import com.brainserve.appointment.appointment.application.AppointmentService;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AppointmentDraftSubmission {
    private final AppointmentService service; private final CurrentAccountAuthority authority; private final Validator validator;
    public AppointmentDraftSubmission(AppointmentService service,CurrentAccountAuthority authority,Validator validator){this.service=service;this.authority=authority;this.validator=validator;}
    public void requireEligible(UUID actor,String context){
        var current=authority.requireActive(actor);
        String permission=switch(context){case "security"->"SECURITY_VISITOR_INTAKE";case "reception"->"VISITOR_REGISTER";default->throw denied();};
        if(!current.permissions().contains(permission))throw denied();
    }
    public AppointmentController.AppointmentResponse submit(UUID actor,String context,UUID key,Map<String,String> fields){
        requireEligible(actor,context);
        var request=new AppointmentController.SecurityWalkInRequest(AppointmentType.valueOf(fields.getOrDefault("type","")),fields.get("visitorName"),fields.get("visitorEmail"),fields.get("visitorPhone"),fields.get("visitorCompany"),id(fields.get("hostEmployeeId")),id(fields.get("routingDepartmentId")),id(fields.get("requestedEmployeeId")),Instant.parse(fields.getOrDefault("slotStart","")),Instant.parse(fields.getOrDefault("slotEnd","")),fields.get("purpose"),fields.get("identityDocumentType"),fields.get("identityDocumentLastFour"),fields.get("notes"));
        if(!validator.validate(request).isEmpty())throw new BusinessException("DRAFT_SUBMISSION_INVALID","Review all required visit fields before submitting",HttpStatus.BAD_REQUEST);
        var command=new AppointmentService.CreateAppointment(request.type(),request.visitorName(),request.visitorEmail(),request.visitorPhone(),request.visitorCompany(),request.hostEmployeeId(),request.routingDepartmentId(),request.requestedEmployeeId(),request.slotStart(),request.slotEnd(),request.purpose());
        var created="security".equals(context)?service.registerAtSecurity(key.toString(),actor,command,new AppointmentService.SecurityIntake(request.visitorName(),request.purpose(),request.identityDocumentType(),request.identityDocumentLastFour(),request.notes())):service.registerAtReception(key.toString(),actor,command);
        return AppointmentController.AppointmentResponse.from(created);
    }
    private static UUID id(String value){return value==null||value.isBlank()?null:UUID.fromString(value);}
    private static BusinessException denied(){return new BusinessException("DRAFT_NOT_FOUND","The draft is unavailable in your current scope",HttpStatus.NOT_FOUND);}
}
