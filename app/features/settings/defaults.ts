import { type RoleDefinition, type WorkspaceSetting } from "../../lib/api";

export const fallbackSettings: WorkspaceSetting[] = [
    { key: "COMPANY.NAME", value: "BrainServe Connect", type: "STRING", description: "Public company display name", version: 0 },
    { key: "COMPANY.EMAIL_DOMAIN", value: "brainserve.in", type: "STRING", description: "Official staff email domain", version: 0 },
    { key: "COMPANY.HQ_ADDRESS", value: "Hyderabad, Telangana, India", type: "STRING", description: "Primary visitor arrival address", version: 0 },
    { key: "COMPANY.SUPPORT_EMAIL", value: "support@brainserve.in", type: "STRING", description: "Visitor support contact", version: 0 },
    { key: "APPOINTMENT.SLOT_MINUTES", value: "30", type: "INTEGER", description: "Appointment duration in minutes", version: 0 },
    { key: "APPOINTMENT.MAX_ADVANCE_DAYS", value: "90", type: "INTEGER", description: "Maximum booking window in days", version: 0 },
    { key: "APPOINTMENT.MIN_LEAD_MINUTES", value: "10", type: "INTEGER", description: "Minimum time before a same-day appointment", version: 0 },
    { key: "APPOINTMENT.CHECK_IN_EARLY_MINUTES", value: "30", type: "INTEGER", description: "QR pass early check-in window", version: 0 },
    { key: "APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END", value: "120", type: "INTEGER", description: "QR pass expiry after visit end", version: 0 },
    { key: "NOTIFICATION.APPOINTMENT_EMAIL_ENABLED", value: "true", type: "BOOLEAN", description: "Booking and verification email", version: 0 },
    { key: "NOTIFICATION.APPROVAL_EMAIL_ENABLED", value: "true", type: "BOOLEAN", description: "Manager, HR and CEO approval alerts", version: 0 },
    { key: "NOTIFICATION.SECURITY_ALERT_EMAIL_ENABLED", value: "true", type: "BOOLEAN", description: "Rejected access security alerts", version: 0 },
    { key: "PRIVACY.CONSENT_VERSION", value: "2026.1", type: "STRING", description: "Active visitor consent version", version: 0 },
];

export const fallbackRoles: RoleDefinition[] = [
    { role: "ROLE_SYSTEM_ADMIN", defaultPermissions: ["ROLE_MANAGE", "SYSTEM_CONFIGURE", "AUDIT_VIEW"] },
    { role: "ROLE_CEO", defaultPermissions: ["CEO_VISIT_APPROVE", "COMPANY_PROFILE_MANAGE", "APPOINTMENT_POLICY_MANAGE", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND", "AUDIT_VIEW"] },
    { role: "ROLE_MANAGER", defaultPermissions: ["MANAGER_VISIT_APPROVE", "EMPLOYEE_READ", "WORK_TASK_READ", "WORK_INSIGHT_READ", "WORK_INSIGHT_MANAGER_APPROVE", "REPORT_VIEW", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"] },
    { role: "ROLE_HR_ADMIN", defaultPermissions: ["STAFF_ACCOUNT_APPROVE", "STAFF_ACCOUNT_MANAGE", "HR_VISIT_APPROVE", "EMPLOYEE_CREATE", "WORK_TASK_READ", "WORK_TASK_PERFORMANCE_READ", "APPOINTMENT_POLICY_MANAGE", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"] },
    { role: "ROLE_TEAM_LEAD", defaultPermissions: ["TEAM_LEAD_DIRECTORY_VIEW", "TEAM_LEAD_VISIT_APPROVE", "WORK_TASK_READ", "WORK_TASK_CREATE", "WORK_TASK_PROGRESS", "WORK_TASK_REVIEW", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"] },
    { role: "ROLE_EMPLOYEE", defaultPermissions: ["EMPLOYEE_READ", "WORK_TASK_READ", "WORK_TASK_PROGRESS", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"] },
    { role: "ROLE_RECEPTIONIST", defaultPermissions: ["EMPLOYEE_READ", "VISITOR_REGISTER", "RECEPTION_VISIT_VERIFY", "VISITOR_CHECK_IN", "VISITOR_CHECK_OUT", "QR_PASS_VERIFY", "INTERNAL_NOTIFICATION_READ"] },
    { role: "ROLE_SECURITY", defaultPermissions: ["SECURITY_VISITOR_INTAKE", "VISITOR_CHECK_IN", "VISITOR_CHECK_OUT", "QR_PASS_VERIFY"] },
];

