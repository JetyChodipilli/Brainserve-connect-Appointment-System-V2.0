package com.brainserve.appointment.iam.infrastructure;

import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    Optional<UserAccount> findByEmployeeId(UUID employeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserAccount user where user.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserAccount user where user.employeeId = :employeeId")
    Optional<UserAccount> findByEmployeeIdForUpdate(
            @Param("employeeId") UUID employeeId
    );

    List<UserAccount> findAllByFullNameIgnoreCase(String fullName);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, UUID id);

    List<UserAccount> findAllByStatusOrderByCreatedAtAsc(AccountStatus status);

    List<UserAccount> findDistinctByRolesContainingAndStatusAndEnabledTrueAndArchivedFalse(
            SystemRole role,
            AccountStatus status
    );

    @Query("""
            select distinct user
              from UserAccount user
              join user.roles role
             where role in :roles
               and user.status = :status
               and user.enabled = true
               and user.archived = false
             order by user.fullName, user.id
            """)
    List<UserAccount> findActiveWithAnyRole(
            @Param("roles") Set<SystemRole> roles,
            @Param("status") AccountStatus status
    );

    @Query(
            value = """
                    select distinct user
                      from UserAccount user
                      join user.roles role
                     where role in :roles
                       and user.status = :status
                       and user.enabled = true
                       and user.archived = false
                       and exists (
                            select employee.id
                              from Employee employee
                             where employee.id = user.employeeId
                               and employee.departmentId = :departmentId
                       )
                    """,
            countQuery = """
                    select count(distinct user.id)
                      from UserAccount user
                      join user.roles role
                     where role in :roles
                       and user.status = :status
                       and user.enabled = true
                       and user.archived = false
                       and exists (
                            select employee.id
                              from Employee employee
                             where employee.id = user.employeeId
                               and employee.departmentId = :departmentId
                       )
                    """
    )
    Page<UserAccount> findActiveWithAnyRoleInDepartment(
            @Param("roles") Set<SystemRole> roles,
            @Param("status") AccountStatus status,
            @Param("departmentId") UUID departmentId,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select distinct user
              from UserAccount user
              join user.roles role
             where role = :role
               and user.status in :statuses
               and user.archived = false
             order by user.createdAt, user.id
            """)
    List<UserAccount> findGoverningRoleAccountsForUpdate(
            @Param("role") SystemRole role,
            @Param("statuses") Set<AccountStatus> statuses
    );

    @Query("""
            select user
              from UserAccount user
             where user.status = :status
               and user.enabled = true
               and user.archived = false
               and (
                    :query = ''
                    or lower(user.fullName) like concat('%', :query, '%')
                    or lower(user.email) like concat('%', :query, '%')
               )
               and (
                    :role is null
                    or :role member of user.roles
               )
               and (
                    :departmentId is null
                    or exists (
                        select employee.id
                          from Employee employee
                         where employee.id = user.employeeId
                           and employee.departmentId = :departmentId
                    )
               )
            """)
    Page<UserAccount> findOperationalAccounts(
            @Param("status") AccountStatus status,
            @Param("query") String query,
            @Param("role") SystemRole role,
            @Param("departmentId") UUID departmentId,
            Pageable pageable
    );

    @Query(
            value = """
                    select distinct user
                      from UserAccount user
                      join user.roles role
                     where user.archived = false
                       and user.employeeId is not null
                       and (
                            (
                                user.status = :activeStatus
                                and user.enabled = true
                                and role in :operationalRoles
                            )
                            or (
                                :includeFormerCeo = true
                                and role = :ceoRole
                                and user.status in :formerCeoStatuses
                            )
                       )
                       and (
                            :query = ''
                            or lower(user.fullName) like concat('%', :query, '%')
                            or lower(user.email) like concat('%', :query, '%')
                       )
                    """,
            countQuery = """
                    select count(distinct user.id)
                      from UserAccount user
                      join user.roles role
                     where user.archived = false
                       and user.employeeId is not null
                       and (
                            (
                                user.status = :activeStatus
                                and user.enabled = true
                                and role in :operationalRoles
                            )
                            or (
                                :includeFormerCeo = true
                                and role = :ceoRole
                                and user.status in :formerCeoStatuses
                            )
                       )
                       and (
                            :query = ''
                            or lower(user.fullName) like concat('%', :query, '%')
                            or lower(user.email) like concat('%', :query, '%')
                       )
                    """
    )
    Page<UserAccount> findOperationalRoleTransitionCandidates(
            @Param("activeStatus") AccountStatus activeStatus,
            @Param("query") String query,
            @Param("operationalRoles") Set<SystemRole> operationalRoles,
            @Param("includeFormerCeo") boolean includeFormerCeo,
            @Param("ceoRole") SystemRole ceoRole,
            @Param("formerCeoStatuses") Set<AccountStatus> formerCeoStatuses,
            Pageable pageable
    );

    @Query(
            value = """
                    select account
                      from UserAccount account
                      join account.roles role
                     where account.archived = false
                       and size(account.roles) = 1
                       and role in :managedRoles
                       and (
                            :query = ''
                            or lower(account.fullName) like concat('%', :query, '%')
                            or lower(account.email) like concat('%', :query, '%')
                       )
                       and (
                            (
                                account.employeeId is not null
                                and exists (
                                    select employee.id
                                      from Employee employee
                                     where employee.id = account.employeeId
                                       and employee.departmentId = :departmentId
                                )
                            )
                            or (
                                account.employeeId is null
                                and account.createdByUser.id = :actorId
                            )
                       )
                     order by lower(account.email), account.id
                    """,
            countQuery = """
                    select count(distinct account.id)
                      from UserAccount account
                      join account.roles role
                     where account.archived = false
                       and size(account.roles) = 1
                       and role in :managedRoles
                       and (
                            :query = ''
                            or lower(account.fullName) like concat('%', :query, '%')
                            or lower(account.email) like concat('%', :query, '%')
                       )
                       and (
                            (
                                account.employeeId is not null
                                and exists (
                                    select employee.id
                                      from Employee employee
                                     where employee.id = account.employeeId
                                       and employee.departmentId = :departmentId
                                )
                            )
                            or (
                                account.employeeId is null
                                and account.createdByUser.id = :actorId
                            )
                       )
                    """
    )
    Page<UserAccount> findHrManagedAccountsByScope(
            @Param("actorId") UUID actorId,
            @Param("departmentId") UUID departmentId,
            @Param("query") String query,
            @Param("managedRoles") Set<SystemRole> managedRoles,
            Pageable pageable
    );

    default Page<UserAccount> findHrManagedAccounts(
            UUID actorId,
            UUID departmentId,
            String query,
            Pageable pageable
    ) {
        return findHrManagedAccountsByScope(
                actorId,
                departmentId,
                query,
                Set.of(
                        SystemRole.ROLE_EMPLOYEE,
                        SystemRole.ROLE_TEAM_LEAD,
                        SystemRole.ROLE_RECEPTIONIST,
                        SystemRole.ROLE_SECURITY
                ),
                pageable
        );
    }
}
