package com.brainserve.appointment.worktask.api;

import com.brainserve.appointment.iam.config.*;
import com.brainserve.appointment.iam.application.PrivilegedSecurityPolicy;
import com.brainserve.appointment.iam.infrastructure.*;
import com.brainserve.appointment.worktask.application.TaskPlanningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;

@WebMvcTest(controllers=TaskPlanningController.class,properties={"brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes","brainserve.security.allowed-origins=http://localhost:3000"})
@Import({SecurityConfiguration.class,RateLimitFilter.class,ClientAddressResolver.class,ActiveAccountFilter.class,AuthenticatedRequestLimitFilter.class,PrivilegedSecurityPolicy.class})
class TaskPlanningSecuritySprint6Test {
    @Autowired MockMvc mvc;
    @MockitoBean TaskPlanningService service;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean UserAccountRepository users;
    @MockitoBean RefreshTokenSessionRepository sessions;
    @MockitoBean MfaCredentialRepository credentials;
    String path="/api/v1/work-tasks/66000000-0000-0000-0000-000000000001";
    @Test void anonymousPlanningAndEvidenceReadsAreRejectedBeforeBusinessService() throws Exception {
        mvc.perform(get(path+"/planning")).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"/evidence/66000000-0000-0000-0000-000000000002/download")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void anonymousMultipartAndDraftDeletionAreRejectedBeforeBusinessService() throws Exception {
        mvc.perform(multipart(path+"/evidence").file("file","%PDF-test".getBytes()).param("expectedVersion","0")).andExpect(status().isUnauthorized());
        mvc.perform(delete(path+"/evidence/66000000-0000-0000-0000-000000000002").param("expectedVersion","0")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void untrustedOriginIsRejectedBeforePlanning() throws Exception {
        mvc.perform(get(path+"/planning").header("Origin","https://untrusted.invalid")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
}
