package com.brainserve.appointment.iam.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NoopController.class, properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.allowed-origins=http://localhost:3000"
})
@Import({SecurityConfiguration.class, RateLimitFilter.class, ActiveAccountFilter.class})
class SecurityConfigurationTest {
    @Autowired MockMvc mockMvc;
    @MockBean StringRedisTemplate redis;
    @MockBean UserAccountRepository users;

    @Test
    void protectedEndpointRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/test/protected")).andExpect(status().isUnauthorized());
    }
}

@org.springframework.web.bind.annotation.RestController
class NoopController {
    @org.springframework.web.bind.annotation.GetMapping("/test/protected")
    String protectedEndpoint() { return "protected"; }
}
