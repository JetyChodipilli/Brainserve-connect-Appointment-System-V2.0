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

    /** Fresh work scope for reads and for revalidation after a business writer acquires its task lock. */
    public WorkScope requireWorkScope(UUID actor) {
        Authority current = requireActive(actor);
        if (current.employeeId() == null) throw denied();
        String table, userColumn, employeeColumn;
        switch (current.role()) {
            case "ROLE_EMPLOYEE" -> { table = null; userColumn = null; employeeColumn = null; }
            case "ROLE_TEAM_LEAD" -> { table = "department_team_lead"; userColumn = "team_lead_user_id"; employeeColumn = "team_lead_employee_id"; }
            case "ROLE_HR_ADMIN" -> { table = "department_hr_assignment"; userColumn = "hr_user_id"; employeeColumn = "hr_employee_id"; }
            case "ROLE_MANAGER" -> { table = "department_manager_assignment"; userColumn = "manager_user_id"; employeeColumn = "manager_employee_id"; }
            default -> throw denied();
        }
        String sql = table == null
                ? "select e.department_id,e.version employee_version,d.version department_version,null::uuid assignment_id,0 assignment_version from employee e join org_department d on d.id=e.department_id where e.id=? and e.status='ACTIVE' and d.active"
                : "select a.department_id,e.version employee_version,d.version department_version,a.id assignment_id,a.version assignment_version from " + table + " a join employee e on e.id=a." + employeeColumn
                    + " join org_department d on d.id=a.department_id where a." + userColumn + "=? and a.active and e.id=? and e.status='ACTIVE' and e.department_id=a.department_id and d.active";
        Object[] params = table == null ? new Object[]{current.employeeId()} : new Object[]{actor, current.employeeId()};
        List<WorkScope> scopes = jdbc.query(sql, (rs,n) -> new WorkScope(current, rs.getObject("department_id",UUID.class),rs.getObject("assignment_id",UUID.class),
                rs.getLong("assignment_version"),rs.getLong("employee_version"),rs.getLong("department_version")),params);
        if (scopes.size() != 1) throw denied();
        return scopes.getFirst();
    }

    public record WorkScope(Authority authority, UUID departmentId, UUID assignmentId, long assignmentVersion, long employeeVersion, long departmentVersion) {}

    private static BusinessException denied() {
        return new BusinessException("ACCOUNT_INACTIVE", "An active account with one current role is required", HttpStatus.FORBIDDEN);
    }

    public record Authority(String role, UUID employeeId, Set<String> permissions) {}
}
