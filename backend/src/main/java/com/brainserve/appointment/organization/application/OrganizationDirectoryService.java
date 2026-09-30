package com.brainserve.appointment.organization.application;

import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.organization.infrastructure.DepartmentRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Optional;

@Service
public class OrganizationDirectoryService implements OrganizationDirectory {
    private final DepartmentRepository departments;
    public OrganizationDirectoryService(DepartmentRepository departments) { this.departments = departments; }

    @Override
    @Transactional(readOnly = true)
    public ActiveDepartment requireActiveDepartment(UUID departmentId) {
        return departments.findById(departmentId).filter(value -> value.isActive())
                .map(value -> new ActiveDepartment(value.getId(), value.getCode(), value.getName()))
                .orElseThrow(() -> new BusinessException("DEPARTMENT_NOT_ACTIVE", "An active department is required", HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Override
    @Transactional
    public ActiveDepartment lockActiveDepartment(UUID departmentId) {
        return departments.findByIdForUpdate(departmentId).filter(value -> value.isActive())
                .map(value -> new ActiveDepartment(value.getId(), value.getCode(), value.getName()))
                .orElseThrow(() -> new BusinessException("DEPARTMENT_NOT_ACTIVE",
                        "An active department is required", HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Override
    @Transactional(readOnly = true)
    public ActiveDepartment requireActiveDepartmentByCode(String code) {
        return departments.findByCodeIgnoreCase(code).filter(value -> value.isActive())
                .map(value -> new ActiveDepartment(value.getId(), value.getCode(), value.getName()))
                .orElseThrow(() -> new BusinessException("DEPARTMENT_NOT_ACTIVE", "An active department is required", HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DepartmentSummary> findDepartment(UUID departmentId) {
        return departments.findById(departmentId)
                .map(value -> new DepartmentSummary(value.getId(), value.getCode(), value.getName(), value.isActive(), value.getVersion()));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<DepartmentSummary> allDepartments() {
        return departments.findAllByOrderByNameAsc().stream()
                .map(value -> new DepartmentSummary(value.getId(), value.getCode(), value.getName(),
                        value.isActive(), value.getVersion())).toList();
    }
}
