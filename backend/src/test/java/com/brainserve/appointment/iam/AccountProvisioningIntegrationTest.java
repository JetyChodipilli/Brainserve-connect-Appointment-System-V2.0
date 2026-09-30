package com.brainserve.appointment.iam;

import com.brainserve.appointment.iam.api.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.bootstrap.system-admin-password=Initial!Admin2026",
        "brainserve.bootstrap.system-admin-enabled=true",
        "spring.task.scheduling.enabled=false",
        "aws.s3.access-key=test-access-key",
        "aws.s3.secret-key=test-secret-key"
})
@AutoConfigureMockMvc
class AccountProvisioningIntegrationTest {
    private static final String SYSTEM_ADMIN_EMAIL = "jetychodipilli@gmail.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine")
            .withDatabaseName("brainserve_provisioning_test")
            .withUsername("brainserve")
            .withPassword("brainserve_test_password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean  EmailService emailService;
    private final Map<String, String> pendingPasswords = new ConcurrentHashMap<>();
    private final Map<String, String> pendingPasswordOtps = new ConcurrentHashMap<>();

    @BeforeEach
    void captureEmailedTemporaryPasswords() {
        pendingPasswords.clear();
        pendingPasswordOtps.clear();
        doAnswer(invocation -> {
            pendingPasswords.put(invocation.getArgument(0), invocation.getArgument(3));
            return null;
        }).when(emailService).sendPendingAccountCreated(anyString(), anyString(), anyString(), anyString(), anyString());
        doAnswer(invocation -> {
            pendingPasswordOtps.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(emailService).sendPasswordChangeOtp(anyString(), anyString(), anyString(), any());
    }

    @Test
    void enforcesTheHierarchicalAccountApprovalQueues() throws Exception {
        String systemAdminToken = login(SYSTEM_ADMIN_EMAIL, "Initial!Admin2026", 200);

        CreatedAccount ceo = createPrivileged(systemAdminToken, "Aarav CEO", "aarav.ceo@brainserve.in", "ROLE_CEO");
        login("aarav.ceo@brainserve.in", ceo.password(), 401);
        mockMvc.perform(post("/api/admin/users/{id}/approve", ceo.id())
                        .header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(emailService).sendAccountApproved(eq("aarav.ceo@brainserve.in"), eq("Aarav CEO"),
                eq("ROLE_CEO"), eq(SYSTEM_ADMIN_EMAIL));
        String ceoToken = completeRequiredPasswordChange(
                "aarav.ceo@brainserve.in", ceo.password(), "AaravCeo!2026");
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(systemAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new PrivilegedRequest(
                                "Second CEO", "second.ceo@brainserve.in", "ROLE_CEO"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CEO_ACCOUNT_ALREADY_EXISTS"));
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new RegistrationRequest("Self Registered CEO",
                                "self.ceo@brainserve.in", "Invalid!Pass2026", "ROLE_CEO"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ROLE"));

        String managerId = register("Meera Manager", "meera.manager@brainserve.in",
                "ManagerAccess!2026", "ROLE_MANAGER", "PENDING_APPROVAL");
        mockMvc.perform(get("/api/ceo/users").header("Authorization", bearer(ceoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(managerId))
                .andExpect(jsonPath("$[0].role").value("ROLE_MANAGER"));
        mockMvc.perform(post("/api/ceo/users/{id}/reject", managerId)
                        .header("Authorization", bearer(ceoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new RejectRequest("Manager position not yet assigned"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        String primaryHrDepartmentId = createDepartment(ceoToken, "HRA", "People Operations A");
        String hrId = register("Kavya HR", "kavya.hr@brainserve.in",
                "HumanResource!2026", "ROLE_HR_ADMIN", "PENDING_APPROVAL");
        login("kavya.hr@brainserve.in", "HumanResource!2026", 401);
        mockMvc.perform(get("/api/ceo/users").header("Authorization", bearer(ceoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(hrId))
                .andExpect(jsonPath("$[0].role").value("ROLE_HR_ADMIN"));
        mockMvc.perform(post("/api/ceo/users/{id}/approve", hrId)
                        .header("Authorization", bearer(ceoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OnboardingRequest(primaryHrDepartmentId,
                                "+91 90000 00001", "HR Business Partner", LocalDate.now()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(emailService).sendAccountApproved(eq("kavya.hr@brainserve.in"), eq("Kavya HR"),
                eq("ROLE_HR_ADMIN"), eq("aarav.ceo@brainserve.in"));
        String hrToken = login("kavya.hr@brainserve.in", "HumanResource!2026", 200);

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(ceoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new PrivilegedRequest(
                                "Unauthorized HR", "unauthorized.hr@brainserve.in", "ROLE_HR_ADMIN"))))
                .andExpect(status().isForbidden());

        String secondHrId = register("Nisha HR", "nisha.hr@brainserve.in",
                "SecondHuman!2026", "ROLE_HR_ADMIN", "PENDING_APPROVAL");
        String secondaryHrDepartmentId = createDepartment(ceoToken, "HRB", "People Operations B");
        mockMvc.perform(post("/api/admin/users/{id}/approve", secondHrId)
                        .header("Authorization", bearer(systemAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OnboardingRequest(secondaryHrDepartmentId,
                                "+91 90000 00002", "Senior HR Partner", LocalDate.now()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CEO_APPROVAL_REQUIRED"));
        mockMvc.perform(post("/api/ceo/users/{id}/approve", secondHrId)
                        .header("Authorization", bearer(ceoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OnboardingRequest(secondaryHrDepartmentId,
                                "+91 90000 00002", "Senior HR Partner", LocalDate.now()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(emailService).sendAccountApproved(eq("nisha.hr@brainserve.in"), eq("Nisha HR"),
                eq("ROLE_HR_ADMIN"), eq("aarav.ceo@brainserve.in"));

        String managerDepartmentId = createDepartment(ceoToken, "MGR", "Executive Coordination");
        String approvedManagerId = register("Arjun Manager", "arjun.manager@brainserve.in",
                "ManagerReady!2026", "ROLE_MANAGER", "PENDING_APPROVAL");
        mockMvc.perform(post("/api/ceo/users/{id}/approve", approvedManagerId)
                        .header("Authorization", bearer(ceoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OnboardingRequest(managerDepartmentId,
                                "+91 90000 00003", "Department Manager", LocalDate.now()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("ROLE_MANAGER"))
                .andExpect(jsonPath("$.employeeId").isNotEmpty());
        login("arjun.manager@brainserve.in", "ManagerReady!2026", 200);

        String receptionistId = register("Riya Reception", "riya.reception@brainserve.in",
                "Reception!Pass2026", "ROLE_RECEPTIONIST", "PENDING_HR_APPROVAL");
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new RegistrationRequest("Invalid CEO",
                                "invalid.ceo@brainserve.in", "Invalid!Pass2026", "ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ROLE"));
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new RegistrationRequest("Duplicate Reception",
                                "riya.reception@brainserve.in", "Duplicate!Pass2026", "ROLE_RECEPTIONIST"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_EMAIL_EXISTS"));
        login("riya.reception@brainserve.in", "Reception!Pass2026", 401);
        mockMvc.perform(get("/api/hr/users").header("Authorization", bearer(hrToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING_HR_APPROVAL"))
                .andExpect(jsonPath("$[0].role").value("ROLE_RECEPTIONIST"));
        mockMvc.perform(post("/api/admin/users/{id}/approve", receptionistId)
                        .header("Authorization", bearer(systemAdminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ACCOUNT_STATUS"));
        mockMvc.perform(post("/api/hr/users/{id}/approve", receptionistId)
                        .header("Authorization", bearer(hrToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(emailService).sendAccountApproved(eq("riya.reception@brainserve.in"), eq("Riya Reception"),
                eq("ROLE_RECEPTIONIST"), eq("kavya.hr@brainserve.in"));
        login("riya.reception@brainserve.in", "Reception!Pass2026", 200);

        String securityId = register("Sanjay Security", "sanjay.security@brainserve.in",
                "Security!Pass2026", "ROLE_SECURITY", "PENDING_HR_APPROVAL");
        mockMvc.perform(post("/api/ceo/users/{id}/approve", securityId)
                        .header("Authorization", bearer(ceoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OnboardingRequest(managerDepartmentId,
                                "+91 90000 00004", "Security Officer", LocalDate.now()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ACCOUNT_STATUS"));
        mockMvc.perform(post("/api/hr/users/{id}/reject", securityId)
                        .header("Authorization", bearer(hrToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new RejectRequest("Position is not yet confirmed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectedByUserId").isNotEmpty())
                .andExpect(jsonPath("$.rejectedAt").isNotEmpty());
        verify(emailService).sendAccountRejected(eq("sanjay.security@brainserve.in"), eq("Sanjay Security"),
                eq("ROLE_SECURITY"), eq("Position is not yet confirmed"), eq("kavya.hr@brainserve.in"));
        login("sanjay.security@brainserve.in", "Security!Pass2026", 401);

        mockMvc.perform(post("/api/hr/users/{id}/approve", receptionistId)
                        .header("Authorization", bearer(hrToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ACCOUNT_STATUS"));
    }

    private CreatedAccount createPrivileged(String token, String fullName, String email, String role) throws Exception {
        String response = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new PrivilegedRequest(fullName, email, role))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.oneTimeTemporaryPassword").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(response);
        String password = pendingPasswords.get(email);
        if (password == null) throw new AssertionError("Temporary password email was not sent");
        return new CreatedAccount(json.required("id").asText(), password);
    }

    private String register(String fullName, String email, String password, String role,
                            String expectedStatus) throws Exception {
        String response = mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new RegistrationRequest(fullName, email, password, role))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).required("id").asText();
    }

    private String createDepartment(String token, String code, String name) throws Exception {
        String response = mockMvc.perform(post("/api/v1/departments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(Map.of("code", code, "name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).required("id").asText();
    }

    private String login(String email, String password, int expectedStatus) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new LoginRequest(email, password))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        if (expectedStatus != 200) return "";
        return mapper.readTree(result.getResponse().getContentAsString()).required("accessToken").asText();
    }

    private String completeRequiredPasswordChange(String email, String temporaryPassword,
                                                  String newPassword) throws Exception {
        String temporaryToken = login(email, temporaryPassword, 200);
        mockMvc.perform(post("/api/auth/change-password/request-otp")
                        .header("Authorization", bearer(temporaryToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OtpRequest(temporaryPassword))))
                .andExpect(status().isNoContent());

        String otp = pendingPasswordOtps.get(email);
        if (otp == null) throw new AssertionError("Password-change OTP email was not sent");
        mockMvc.perform(post("/api/auth/change-password/confirm")
                        .header("Authorization", bearer(temporaryToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new ConfirmPasswordRequest(otp, newPassword))))
                .andExpect(status().isNoContent());
        return login(email, newPassword, 200);
    }

    private String bearer(String token) { return "Bearer " + token; }

    private record LoginRequest(String email, String password) {}
    private record PrivilegedRequest(String fullName, String email, String role) {}
    private record RegistrationRequest(String fullName, String email, String password, String role) {}
    private record RejectRequest(String reason) {}
    private record OtpRequest(String currentPassword) {}
    private record ConfirmPasswordRequest(String otp, String newPassword) {}
    private record OnboardingRequest(String departmentId, String phoneNumber,
                                     String designation, LocalDate joiningDate) {}
    private record CreatedAccount(String id, String password) {}
}
