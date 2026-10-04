package com.brainserve.appointment.configuration.api;

import com.brainserve.appointment.configuration.application.WorkspaceSettingsService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CompanyDraftSubmission {
    public static final List<String> KEYS=List.of("COMPANY.NAME","COMPANY.EMAIL_DOMAIN","COMPANY.HQ_ADDRESS","COMPANY.SUPPORT_EMAIL");
    private final WorkspaceSettingsService service;private final CurrentAccountAuthority authority;
    public CompanyDraftSubmission(WorkspaceSettingsService service,CurrentAccountAuthority authority){this.service=service;this.authority=authority;}
    public void requireEligible(UUID actor){var a=authority.requireActive(actor);if(!java.util.Set.of("ROLE_SYSTEM_ADMIN","ROLE_CEO","ROLE_HR_ADMIN").contains(a.role())||!a.permissions().contains("COMPANY_PROFILE_MANAGE")&&!a.permissions().contains("SYSTEM_CONFIGURE"))throw new BusinessException("DRAFT_NOT_FOUND","The draft is unavailable in your current scope",HttpStatus.NOT_FOUND);}
    public List<WorkspaceSettingsController.SettingResponse> submit(UUID actor,Map<String,String> fields){
        requireEligible(actor);
        if(!fields.keySet().equals(java.util.Set.copyOf(KEYS)))throw new BusinessException("DRAFT_SUBMISSION_INVALID","Complete all four company profile fields before submitting",HttpStatus.BAD_REQUEST);
        return KEYS.stream().map(key->WorkspaceSettingsController.SettingResponse.from(service.update(key,fields.get(key)))).toList();
    }
}
