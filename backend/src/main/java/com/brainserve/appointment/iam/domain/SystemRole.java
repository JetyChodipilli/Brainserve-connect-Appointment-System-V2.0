package com.brainserve.appointment.iam.domain;

import java.util.EnumSet;
import java.util.Set;

import static com.brainserve.appointment.iam.domain.Permission.*;

public enum SystemRole {

    ROLE_CEO(EnumSet.of(
            EMPLOYEE_READ,
            EMPLOYEE_DOCUMENT_READ,
            SALARY_READ,
            SALARY_APPROVE,
            DEPARTMENT_MANAGE,
            DESIGNATION_MANAGE,
            APPOINTMENT_REQUEST,
            APPOINTMENT_APPROVE,
            APPOINTMENT_REJECT,
            APPOINTMENT_RESCHEDULE,
            CEO_VISIT_APPROVE,
            VISITOR_OCCUPANCY_READ,
            COMPANY_PROFILE_MANAGE,
            APPOINTMENT_POLICY_MANAGE,
            NOTIFICATION_CONFIGURE,
            INTERNAL_NOTIFICATION_READ,
            INTERNAL_NOTIFICATION_SEND,
            PRIVACY_POLICY_MANAGE,
            HR_ACCOUNT_DEACTIVATE,
            TEAM_LEAD_DIRECTORY_VIEW,
            MANAGER_ASSIGNMENT_MANAGE,
            WORK_INSIGHT_READ,
            WORK_INSIGHT_CEO_APPROVE,
            REPORT_VIEW,
            AUDIT_VIEW
    )),

    ROLE_HR_ADMIN(EnumSet.of(
            EMPLOYEE_CREATE,
            EMPLOYEE_READ,
            EMPLOYEE_UPDATE,
            EMPLOYEE_STATUS_CHANGE,
            EMPLOYEE_DOCUMENT_READ,
            EMPLOYEE_DOCUMENT_WRITE,
            SALARY_READ,
            SALARY_WRITE,
            SALARY_APPROVE,
            DEPARTMENT_MANAGE,
            DESIGNATION_MANAGE,
            APPOINTMENT_REQUEST,
            APPOINTMENT_APPROVE,
            APPOINTMENT_REJECT,
            APPOINTMENT_RESCHEDULE,
            HR_VISIT_APPROVE,
            STAFF_ACCOUNT_MANAGE,
            STAFF_ACCOUNT_APPROVE,
            VISITOR_REGISTER,
            VISITOR_VERIFY,
            APPOINTMENT_POLICY_MANAGE,
            NOTIFICATION_CONFIGURE,
            INTERNAL_NOTIFICATION_READ,
            INTERNAL_NOTIFICATION_SEND,
            PRIVACY_POLICY_MANAGE,
            LEAVE_REQUEST_REVIEW,
            TEAM_LEAD_ASSIGNMENT_MANAGE,
            TEAM_LEAD_DIRECTORY_VIEW,
            WORK_TASK_READ,
            WORK_TASK_CREATE,
            WORK_TASK_PERFORMANCE_READ,
            WORK_INSIGHT_READ,
            WORK_INSIGHT_AUDIT,
            REPORT_VIEW,
            AUDIT_VIEW
    )),

    ROLE_MANAGER(EnumSet.of(
            EMPLOYEE_READ,
            APPOINTMENT_REQUEST,
            APPOINTMENT_RESCHEDULE,
            MANAGER_VISIT_APPROVE,
            INTERNAL_NOTIFICATION_READ,
            INTERNAL_NOTIFICATION_SEND,
            TEAM_LEAD_DIRECTORY_VIEW,
            WORK_TASK_READ,
            WORK_INSIGHT_READ,
            WORK_INSIGHT_MANAGER_APPROVE,
            REPORT_VIEW
    )),

    ROLE_TEAM_LEAD(EnumSet.of(
            EMPLOYEE_READ,
            TEAM_LEAD_DIRECTORY_VIEW,
            TEAM_LEAD_VISIT_APPROVE,
            APPOINTMENT_REQUEST,
            APPOINTMENT_RESCHEDULE,
            INTERNAL_NOTIFICATION_READ,
            INTERNAL_NOTIFICATION_SEND,
            WORK_TASK_READ,
            WORK_TASK_CREATE,
            WORK_TASK_PROGRESS,
            WORK_TASK_REVIEW,
            REPORT_VIEW
    )),

    ROLE_EMPLOYEE(EnumSet.of(
            EMPLOYEE_READ,
            APPOINTMENT_REQUEST,
            APPOINTMENT_APPROVE,
            APPOINTMENT_REJECT,
            APPOINTMENT_RESCHEDULE,
            INTERNAL_NOTIFICATION_READ,
            INTERNAL_NOTIFICATION_SEND,
            LEAVE_REQUEST_CREATE,
            WORK_TASK_READ,
            WORK_TASK_PROGRESS
    )),

    ROLE_RECEPTIONIST(EnumSet.of(
            EMPLOYEE_READ,
            VISITOR_REGISTER,
            VISITOR_VERIFY,
            RECEPTION_VISIT_VERIFY,
            VISITOR_CHECK_IN,
            VISITOR_CHECK_OUT,
            QR_PASS_VERIFY,
            INTERNAL_NOTIFICATION_READ,
            INTERNAL_NOTIFICATION_SEND
    )),

    ROLE_SECURITY(EnumSet.of(
            VISITOR_VERIFY,
            SECURITY_VISITOR_INTAKE,
            VISITOR_CHECK_IN,
            VISITOR_CHECK_OUT,
            QR_PASS_VERIFY
    )),

    ROLE_SYSTEM_ADMIN(EnumSet.of(
            ROLE_MANAGE,
            WORKFORCE_RECORD_VIEW,
            HR_ACCOUNT_DEACTIVATE,
            MANAGER_ASSIGNMENT_MANAGE,
            WORK_INSIGHT_READ,
            WORK_INSIGHT_ARCHIVE_READ,
            REPORT_VIEW,
            AUDIT_VIEW,
            SYSTEM_CONFIGURE
    ));

    private final Set<Permission> permissions;

    SystemRole(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
