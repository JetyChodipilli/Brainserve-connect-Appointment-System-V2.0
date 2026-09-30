package com.brainserve.appointment.iam.config;

import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
@Order(10)
public class BootstrapAdministrator implements ApplicationRunner {
    static final String SYSTEM_ADMIN_EMAIL = "jetychodipilli@gmail.com";
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final String password;
    private final boolean enabled;

    public BootstrapAdministrator(UserAccountRepository users, PasswordEncoder encoder,
                                  @Value("${brainserve.bootstrap.system-admin-password:}") String password,
                                  @Value("${brainserve.bootstrap.system-admin-enabled:true}") boolean enabled) {
        this.users = users; this.encoder = encoder;
        this.password = password; this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (users.existsByEmailIgnoreCase(SYSTEM_ADMIN_EMAIL)) return;
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("SYSTEM_ADMIN_DEFAULT_PASSWORD must be set before the first application startup");
        }
        users.save(new UserAccount(SYSTEM_ADMIN_EMAIL, "Jety Chodipilli", null, encoder.encode(password), false,
                AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_SYSTEM_ADMIN), null));
    }
}
