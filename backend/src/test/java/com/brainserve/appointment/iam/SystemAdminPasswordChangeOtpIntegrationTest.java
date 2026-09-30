package com.brainserve.appointment.iam;

import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class SystemAdminPasswordChangeOtpIntegrationTest {
    private static final String EMAIL = "jetychodipilli@gmail.com";
    private static final String INITIAL_PASSWORD = "Initial!Admin2026";
    private static final String NEW_PASSWORD = "NewSecure!Pass2026";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine")
            .withDatabaseName("brainserve_system_admin_test")
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
    @Autowired UserAccountRepository users;
    @MockBean EmailService emailService;

    @Test
    void defaultPasswordAllowsAccessAndVoluntaryChangeRequiresEmailedOtp() throws Exception {
        String accessToken = login(INITIAL_PASSWORD);

        mockMvc.perform(get("/api/v1/admin/permissions")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        assertThat(users.findByEmailIgnoreCase(EMAIL).orElseThrow().isForcePasswordChange()).isFalse();

        mockMvc.perform(post("/api/auth/change-password/request-otp")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new OtpRequest(INITIAL_PASSWORD))))
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordChangeOtp(eq(EMAIL), eq("Jety Chodipilli"), otp.capture(), any(Instant.class));
        assertThat(otp.getValue()).matches("\\d{6}");

        mockMvc.perform(post("/api/auth/change-password/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new ConfirmRequest(otp.getValue(), NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        verify(emailService).sendPasswordChangedConfirmation(eq(EMAIL), eq("Jety Chodipilli"), any(Instant.class));
        verifyNoMoreInteractions(emailService);

        mockMvc.perform(post("/api/auth/change-password/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new ConfirmRequest(otp.getValue(), "Another!Secure2026"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new LoginRequest(EMAIL, INITIAL_PASSWORD))))
                .andExpect(status().isUnauthorized());
        login(NEW_PASSWORD);
    }

    private String login(String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new LoginRequest(EMAIL, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(response);
        return json.required("accessToken").asText();
    }

    private record LoginRequest(String email, String password) {}
    private record OtpRequest(String currentPassword) {}
    private record ConfirmRequest(String otp, String newPassword) {}
}
