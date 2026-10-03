package com.brainserve.appointment.organization.api;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.organization.domain.Department;
import com.brainserve.appointment.organization.infrastructure.DepartmentRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared create-only department operation for the normal form and governed imports. */
@Service
public class DepartmentCommands {
    private final DepartmentRepository departments;
    private final AuditService audit;
    public DepartmentCommands(DepartmentRepository departments, AuditService audit) { this.departments = departments; this.audit = audit; }
    @Transactional
    public Department create(String code, String name) {
        if (departments.existsByCodeIgnoreCase(code)) throw conflict();
        Department created;
        try { created = departments.saveAndFlush(new Department(code, name)); }
        catch (DataIntegrityViolationException exception) { throw conflict(); }
        audit.record("DEPARTMENT_CREATED", "DEPARTMENT", created.getId().toString(), "{\"code\":\"" + created.getCode() + "\"}");
        return created;
    }
    @Transactional
    public java.util.UUID createId(String code, String name) { return create(code, name).getId(); }
    private BusinessException conflict() { return new BusinessException("DEPARTMENT_CODE_EXISTS", "Department code already exists", HttpStatus.CONFLICT); }
}
