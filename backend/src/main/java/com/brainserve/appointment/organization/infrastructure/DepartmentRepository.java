package com.brainserve.appointment.organization.infrastructure;

import com.brainserve.appointment.organization.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    boolean existsByCodeIgnoreCase(String code);
    Optional<Department> findByCodeIgnoreCase(String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select department from Department department where department.id = :id")
    Optional<Department> findByIdForUpdate(@Param("id") UUID id);
    List<Department> findAllByOrderByNameAsc();
}
