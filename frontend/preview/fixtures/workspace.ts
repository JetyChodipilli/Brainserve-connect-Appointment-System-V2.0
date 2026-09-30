import {
    type DepartmentHrAssignment,
    type ManagerAssignment,
    type StaffAccount,
    type TeamLeadAssignment,
    type WorkTask,
} from "../../services/brainserve-api";
import { nextBusinessDays } from "../../lib/appointments";
import { type AccessRecord, type Appointment, type Department, type Employee } from "../../types/workspace";

export const initialAppointments: Appointment[] = [
    { id: "1", initials: "AK", visitor: "Arjun Kumar", company: "Acme Technologies", host: "Riya Sharma", purpose: "Product partnership", time: "10:00 AM", date: "Today", status: "Approved", type: "Client meeting" },
    { id: "2", initials: "NS", visitor: "Neha Singh", company: "Independent", host: "Kavya Reddy", purpose: "Backend developer interview", time: "11:30 AM", date: "Today", status: "Awaiting HR", type: "Interview" },
    { id: "3", initials: "VP", visitor: "Vikram Patel", company: "Northstar Systems", host: "Aarav Mehta", purpose: "Quarterly service review", time: "1:00 PM", date: "Today", status: "Checked in", type: "Vendor visit" },
    { id: "4", initials: "SM", visitor: "Sara Mathew", company: "Vertex Labs", host: "CEO Office", purpose: "Research collaboration", time: "3:30 PM", date: "Today", status: "Awaiting Reception", type: "CEO visit", arrivalVisitorName: "Sara Mathew", arrivalPurpose: "Research collaboration", identityDocumentType: "Passport", identityDocumentLastFour: "A123", securityNotes: "Photo identity matched", securityIntakeAt: new Date().toISOString() },
    { id: "5", initials: "DR", visitor: "Dev Rao", company: "Cobalt Design", host: "Ananya Joshi", purpose: "Design handoff", time: "4:30 PM", date: "Today", status: "Approved", type: "Employee visit" },
    { id: "6", initials: "RK", visitor: "Rohan Khanna", company: "Axis Ventures", host: "CEO Office", purpose: "Strategic investment meeting", time: "5:00 PM", date: "Today", status: "Awaiting Manager", type: "CEO visit", routingDepartmentId: "OPS" },
    { id: "7", initials: "PJ", visitor: "Priya Jain", company: "Helios Labs", host: "Riya Sharma", purpose: "Product implementation review", time: "2:20 PM", date: "Today", status: "Awaiting Security", type: "Employee visit" },
    { id: "8", initials: "AM", visitor: "Aditi Menon", visitorEmail: "aditi.menon@northstar.example",
        visitorPhone: "+91 98765 12004", company: "Northstar Systems", host: "Kalyan Reddy",
        hostEmployeeId: "BSPL-IT-0071", purpose: "Project delivery and integration review", time: "4:00 PM",
        date: "Today", status: "Awaiting Team Lead", type: "Employee visit", referenceNumber: "BSA-KLYN-2041",
        arrivalVisitorName: "Aditi Menon", arrivalPurpose: "Project delivery and integration review",
        securityIntakeAt: new Date(Date.now() - 35 * 60 * 1000).toISOString(),
        receptionVerifiedAt: new Date(Date.now() - 22 * 60 * 1000).toISOString(),
        hrDecisionAt: new Date(Date.now() - 8 * 60 * 1000).toISOString(),
        hrDecisionRemarks: "Visitor details verified and forwarded to the employee and Team Lead" },
];

export const initialWorkTasks: WorkTask[] = [
    { id: "demo-work-1", departmentId: "TECH", employeeId: "BSPL-IT-0071", teamLeadUserId: "demo-team-lead",
        assignedByUserId: "demo-team-lead", assignedByRole: "TEAM_LEAD", assigneeRole: "EMPLOYEE",
        title: "Complete visitor workflow API validation", description: "Verify Security, Reception and HR routing contracts and document the result.",
        departmentBranch: "Technology", dueDate: nextBusinessDays(3)[0], status: "IN_PROGRESS",
        employeeUpdate: "Security and Reception routes validated; HR tests are in progress.", teamLeadReview: null,
        startedAt: new Date().toISOString(), completedAt: null, approvedAt: null, acknowledgedAt: null,
        createdAt: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(), version: 1 },
    { id: "demo-work-2", departmentId: "TECH", employeeId: "BSPL-IT-0071", teamLeadUserId: "demo-team-lead",
        assignedByUserId: "demo-team-lead", assignedByRole: "TEAM_LEAD", assigneeRole: "EMPLOYEE",
        title: "Publish appointment dashboard refinements", description: "Complete the responsive employee dashboard and submit it for Team Lead review.",
        departmentBranch: "Technology", dueDate: nextBusinessDays(1)[0], status: "COMPLETED",
        employeeUpdate: "Responsive view and empty states are complete.", teamLeadReview: null,
        startedAt: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString(), completedAt: new Date().toISOString(),
        approvedAt: null, acknowledgedAt: null, createdAt: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString(), version: 2 },
];

export const initialStaffAccounts: StaffAccount[] = [
    { userId: "demo-manager", employeeId: "BSPL-OP-0027", fullName: "Aarav Mehta", email: "aarav.mehta@brainserve.in",
        roles: ["ROLE_MANAGER"], enabled: true, forcePasswordChange: false, status: "ACTIVE",
        grantedPermissions: [], deniedPermissions: [],
        effectivePermissions: ["MANAGER_VISIT_APPROVE", "EMPLOYEE_READ", "REPORT_VIEW", "INTERNAL_NOTIFICATION_READ"] },
    { userId: "demo-hr-admin", employeeId: "BSPL-HR-0018", fullName: "Kavya Reddy", email: "hr.admin@brainserve.in",
        roles: ["ROLE_HR_ADMIN"], enabled: true, forcePasswordChange: false, status: "ACTIVE",
        grantedPermissions: [], deniedPermissions: [],
        effectivePermissions: ["HR_VISIT_APPROVE", "WORK_INSIGHT_AUDIT", "WORK_TASK_PERFORMANCE_READ", "INTERNAL_NOTIFICATION_READ"] },
    { userId: "demo-team-lead", employeeId: "BSPL-IT-0042", fullName: "Riya Sharma", email: "riya.sharma@brainserve.in",
        roles: ["ROLE_TEAM_LEAD"], enabled: true, forcePasswordChange: false, status: "ACTIVE",
        grantedPermissions: [], deniedPermissions: [],
        effectivePermissions: ["TEAM_LEAD_DIRECTORY_VIEW", "TEAM_LEAD_VISIT_APPROVE", "APPOINTMENT_REQUEST", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"] },
    { userId: "demo-employee-kalyan", employeeId: "BSPL-IT-0071", fullName: "Kalyan Reddy", email: "kalyan@brainserve.in",
        roles: ["ROLE_EMPLOYEE"], enabled: true, forcePasswordChange: false, status: "ACTIVE",
        grantedPermissions: [], deniedPermissions: [],
        effectivePermissions: ["EMPLOYEE_READ", "APPOINTMENT_REQUEST", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"] },
    { userId: "reception-preview", fullName: "Reception Desk", email: "reception@brainserve.in",
        roles: ["ROLE_RECEPTIONIST"], enabled: true, forcePasswordChange: true, status: "ACTIVE",
        grantedPermissions: [], deniedPermissions: [],
        effectivePermissions: ["EMPLOYEE_READ", "VISITOR_REGISTER", "RECEPTION_VISIT_VERIFY", "VISITOR_CHECK_IN", "VISITOR_CHECK_OUT", "QR_PASS_VERIFY"] },
    { userId: "security-preview", fullName: "Security Desk", email: "security@brainserve.in",
        roles: ["ROLE_SECURITY"], enabled: true, forcePasswordChange: false, status: "ACTIVE",
        grantedPermissions: [], deniedPermissions: [],
        effectivePermissions: ["SECURITY_VISITOR_INTAKE", "VISITOR_CHECK_IN", "VISITOR_CHECK_OUT", "QR_PASS_VERIFY"] },
];

export const initialAccessRecords: AccessRecord[] = [{ id: "demo-access-1", appointmentId: "3",
    visitorName: "Vikram Patel", badgeNumber: "B-103",
    checkedInAt: new Date(Date.now() - 42 * 60 * 1000).toISOString(), checkedOutAt: null, processedBy: "Reception Desk" }];

export const initialEmployees: Employee[] = [
    { id: "BSPL-IT-0042", departmentId: "TECH", name: "Riya Sharma", initials: "RS", role: "Engineering Manager", department: "Technology", email: "riya.sharma@brainserve.in", status: "Active" },
    { id: "BSPL-HR-0018", departmentId: "HR", name: "Kavya Reddy", initials: "KR", role: "HR Business Partner", department: "Human Resources", email: "kavya.reddy@brainserve.in", status: "Active" },
    { id: "BSPL-OP-0027", departmentId: "OPS", name: "Aarav Mehta", initials: "AM", role: "Operations Lead", department: "Operations", email: "aarav.mehta@brainserve.in", status: "Active" },
    { id: "BSPL-FN-0011", departmentId: "FIN", name: "Ananya Joshi", initials: "AJ", role: "Finance Analyst", department: "Finance", email: "ananya.joshi@brainserve.in", status: "On leave" },
    { id: "BSPL-IT-0069", departmentId: "TECH", name: "Ishaan Verma", initials: "IV", role: "Software Engineer", department: "Technology", email: "ishaan.verma@brainserve.in", status: "Onboarding" },
    { id: "BSPL-IT-0071", departmentId: "TECH", name: "Kalyan Reddy", initials: "KR", role: "Software Engineer", department: "Technology", email: "kalyan@brainserve.in", status: "Active" },
];

export const initialDepartments: Department[] = [
    { id: "TECH", code: "TECH", name: "Technology", active: true, version: 0 },
    { id: "HR", code: "HR", name: "Human Resources", active: true, version: 0 },
    { id: "OPS", code: "OPS", name: "Operations", active: true, version: 0 },
    { id: "FIN", code: "FIN", name: "Finance", active: true, version: 0 },
];

export const initialTeamLeadAssignments: TeamLeadAssignment[] = [
    { id: "demo-tl-tech", departmentId: "TECH", teamLeadUserId: "demo-team-lead",
        teamLeadEmployeeId: "BSPL-IT-0042", active: true, assignedByUserId: "demo-hr-admin",
        assignedAt: "2026-07-15T04:30:00.000Z", endedByUserId: null, endedAt: null },
];

export const initialDepartmentHrAssignments: DepartmentHrAssignment[] = [
    { id: "demo-hr-tech", departmentId: "TECH", hrUserId: "demo-hr-admin", hrEmployeeId: "BSPL-HR-0018",
        active: true, assignedByUserId: "demo-ceo", assignedAt: "2026-07-16T04:30:00.000Z",
        endedByUserId: null, endedAt: null },
];

export const initialManagerAssignments: ManagerAssignment[] = [
    { id: "demo-manager-ops", departmentId: "OPS", managerUserId: "demo-manager",
        managerEmployeeId: "BSPL-OP-0027", active: true, assignedByUserId: "demo-ceo",
        assignedAt: "2026-07-20T04:30:00.000Z", endedByUserId: null, endedAt: null },
];

