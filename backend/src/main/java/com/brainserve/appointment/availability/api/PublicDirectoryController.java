package com.brainserve.appointment.availability.api;

import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
public class PublicDirectoryController {
    private final EmployeeDirectory employees;
    private final OrganizationDirectory organization;

    public PublicDirectoryController(EmployeeDirectory employees, OrganizationDirectory organization) {
        this.employees = employees;
        this.organization = organization;
    }

    @GetMapping("/employees")
    public Page<EmployeeDirectory.PublicEmployee> employees(@RequestParam UUID departmentId,
                                                            @RequestParam(required = false) String query,
                                                            Pageable pageable) {
        requireBoundedPage(pageable);
        return employees.publicActiveEmployees(departmentId, query, pageable);
    }

    @GetMapping("/departments")
    public List<OrganizationDirectory.DepartmentSummary> departments() {
        return organization.allDepartments().stream().filter(OrganizationDirectory.DepartmentSummary::active).toList();
    }

    private void requireBoundedPage(Pageable pageable) {
        if (pageable.getPageSize() < 10 || pageable.getPageSize() > 50) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Public directory page size must be between 10 and 50",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
