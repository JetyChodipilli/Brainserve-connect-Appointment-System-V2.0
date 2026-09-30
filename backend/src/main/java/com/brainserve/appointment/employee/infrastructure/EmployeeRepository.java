package com.brainserve.appointment.employee.infrastructure;

import com.brainserve.appointment.employee.domain.Employee;
import com.brainserve.appointment.employee.domain.EmployeeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    boolean existsByOfficialEmailIgnoreCase(String officialEmail);

    Optional<Employee> findByOfficialEmailIgnoreCase(String officialEmail);

    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select employee
              from Employee employee
             where employee.id = :id
            """)
    Optional<Employee> findByIdForUpdate(@Param("id") UUID id);

    /*
     * The old query checked ":query is null".
     *
     * With PostgreSQL and Hibernate, a null search value could be bound as
     * bytea, causing:
     *
     *     function lower(bytea) does not exist
     *
     * The service must now always pass a non-null String and indicate whether
     * searching is enabled through the hasQuery parameter.
     */
    @Query("""
            select employee
              from Employee employee
             where (:departmentId is null
                    or employee.departmentId = :departmentId)
               and (:status is null
                    or employee.status = :status)
               and (
                    :hasQuery = false
                    or lower(employee.displayName)
                        like concat('%', :query, '%')
                    or lower(employee.employeeNumber)
                        like concat(:query, '%')
                    or lower(employee.officialEmail)
                        like concat(:query, '%')
               )
             order by employee.displayName asc
            """)
    Page<Employee> search(
            @Param("departmentId") UUID departmentId,
            @Param("status") EmployeeStatus status,
            @Param("hasQuery") boolean hasQuery,
            @Param("query") String query,
            Pageable pageable
    );

    Page<Employee> findAllByDepartmentId(
            UUID departmentId,
            Pageable pageable
    );

    List<Employee> findAllByStatusOrderByDisplayNameAsc(
            EmployeeStatus status
    );

    long countByStatus(EmployeeStatus status);

    @Query(
            value = """
                    select department_id as "departmentId",
                           count(*) as "totalEmployees",
                           count(*) filter (
                               where status = 'ACTIVE'
                           ) as "activeEmployees",
                           count(*) filter (
                               where status = 'ON_LEAVE'
                           ) as "onLeaveEmployees",
                           count(*) filter (
                               where status = 'ONBOARDING'
                           ) as "onboardingEmployees"
                      from employee
                     group by department_id
                    """,
            nativeQuery = true
    )
    List<DepartmentEmployeeSummaryProjection> summarizeByDepartment();

    interface DepartmentEmployeeSummaryProjection {

        UUID getDepartmentId();

        long getTotalEmployees();

        long getActiveEmployees();

        long getOnLeaveEmployees();

        long getOnboardingEmployees();
    }
}