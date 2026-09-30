package com.brainserve.appointment.iam.config;

import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.EnumSet;
import java.util.Set;

@Component
@Order(20)
public class BootstrapChiefExecutive implements ApplicationRunner {
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final boolean enabled;
    private final String fullName;
    private final String email;
    private final String password;

    public BootstrapChiefExecutive(UserAccountRepository users, PasswordEncoder encoder,
                                   @Value("${brainserve.bootstrap.ceo-enabled:false}") boolean enabled,
                                   @Value("${brainserve.bootstrap.ceo-name:BrainServe CEO}") String fullName,
                                   @Value("${brainserve.bootstrap.ceo-email:ceo@brainserve.in}") String email,
                                   @Value("${brainserve.bootstrap.ceo-password:}") String password) {
        this.users = users; this.encoder = encoder; this.enabled = enabled;
        this.fullName = fullName; this.email = email; this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (!users.findGoverningRoleAccountsForUpdate(SystemRole.ROLE_CEO,
                EnumSet.of(AccountStatus.ACTIVE, AccountStatus.PENDING_APPROVAL)).isEmpty()) return;
        if (!StringUtils.hasText(fullName) || !StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            throw new IllegalStateException("CEO_BOOTSTRAP_NAME, CEO_BOOTSTRAP_EMAIL and CEO_BOOTSTRAP_PASSWORD must be configured for the first CEO seed");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("CEO bootstrap email already belongs to a non-CEO account");
        }
        users.save(new UserAccount(email, fullName, null, encoder.encode(password), false,
                AccountStatus.ACTIVE, Set.of(SystemRole.ROLE_CEO), null));
    }
}
