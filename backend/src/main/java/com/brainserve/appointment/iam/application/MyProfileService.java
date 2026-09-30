package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.document.api.ProfilePhotoStore;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class MyProfileService {
    private final StaffCommunicationDirectory staff;
    private final EmployeeDirectory employees;
    private final OrganizationDirectory organization;
    private final ProfilePhotoStore photos;

    public MyProfileService(StaffCommunicationDirectory staff, EmployeeDirectory employees,
                            OrganizationDirectory organization, ProfilePhotoStore photos) {
        this.staff = staff; this.employees = employees; this.organization = organization; this.photos = photos;
    }

    public Profile profile(UUID userId) {
        var account = staff.requireActive(userId);
        String employeeNumber = null;
        String designation = null;
        String employeeStatus = null;
        UUID departmentId = null;
        String departmentCode = null;
        String departmentName = null;
        Boolean departmentActive = null;

        if (account.employeeId() != null) {
            var employee = employees.employeeSummary(account.employeeId());
            employeeNumber = employee.employeeNumber();
            designation = employee.designation();
            employeeStatus = employee.status();
            departmentId = employee.departmentId();
            var department = organization.findDepartment(departmentId).orElse(null);
            if (department != null) {
                departmentCode = department.code();
                departmentName = department.name();
                departmentActive = department.active();
            }
        }

        var photo = photos.current(userId).orElse(null);
        ProfilePhotoStore.ProfilePhotoAccess access = photo == null ? null : photos.createAccess(photo.documentId());
        List<String> roles = account.roles().stream().sorted(Comparator.naturalOrder()).toList();
        return new Profile(account.userId(), account.employeeId(), account.fullName(), account.email(), roles,
                employeeNumber, designation, employeeStatus, departmentId, departmentCode, departmentName,
                departmentActive, photo == null ? null : photo.documentId(), access == null ? null : access.url(),
                access == null ? null : access.expiresAt());
    }

    public Profile uploadPhoto(UUID userId, MultipartFile file) {
        staff.requireActive(userId);
        photos.save(userId, file);
        return profile(userId);
    }

    public record Profile(UUID userId, UUID employeeId, String fullName, String email, List<String> roles,
                          String employeeNumber, String designation, String employeeStatus,
                          UUID departmentId, String departmentCode, String departmentName, Boolean departmentActive,
                          UUID photoDocumentId, String photoUrl, Instant photoUrlExpiresAt) {}
}
