package com.brainserve.appointment.iam.api;

import com.brainserve.appointment.iam.domain.Permission;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reads current authority without reusing an ORM session's account snapshot. */
@Service
public class CurrentAccountAuthority {
    private final JdbcTemplate jdbc;

    public CurrentAccountAuthority(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Authority requireActive(UUID userId) {
        List<Authority> accounts = jdbc.query("""
                select a.employee_id,
                    array(select role_name from iam_user_role where user_id = a.id order by role_name) roles,
                    array(select permission_name from iam_user_permission_grant where user_id = a.id) grants,
                    array(select permission_name from iam_user_permission_deny where user_id = a.id) denies
                from iam_user_account a
                where a.id = ? and a.enabled and a.account_status = 'ACTIVE' and not a.archived
                """, (rs, index) -> {
            String[] roles = (String[]) rs.getArray("roles").getArray();
            if (roles.length != 1) throw denied();
            SystemRole role;
            try { role = SystemRole.valueOf(roles[0]); }
            catch (IllegalArgumentException exception) { throw denied(); }
            Set<String> permissions = new HashSet<>();
            role.permissions().stream().map(Permission::name).forEach(permissions::add);
            for (String grant : (String[]) rs.getArray("grants").getArray()) {
                try { permissions.add(Permission.valueOf(grant).name()); }
                catch (IllegalArgumentException exception) { /* Unknown grants confer no authority. */ }
            }
            for (String deny : (String[]) rs.getArray("denies").getArray()) permissions.remove(deny);
            return new Authority(role.name(), rs.getObject("employee_id", UUID.class), Set.copyOf(permissions));
        }, userId);
        if (accounts.size() != 1) throw denied();
        return accounts.getFirst();
    }

    private static BusinessException denied() {
        return new BusinessException("ACCOUNT_INACTIVE", "An active account with one current role is required", HttpStatus.FORBIDDEN);
    }

    public record Authority(String role, UUID employeeId, Set<String> permissions) {}
}
