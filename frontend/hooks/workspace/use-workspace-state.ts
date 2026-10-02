"use client";

import {
    type AccountRecoveryRequest,
    type DepartmentEmployeeSummary,
    type DepartmentHrAssignment,
    isBackendConfigured,
    type ManagerAssignment,
    type MyProfile,
    type RealtimeConnectionState,
    type StaffAccount,
    type TeamLeadAssignment,
} from "../../services/brainserve-api";
import { notificationSoundEnabled } from "../../services/notification-sounds";
import { mergeDemoStaffAccounts, readDemoAccounts } from "../../preview/accounts";
import { readPreviewWorkspaceAppointments } from "../../preview/appointments";
import {
    readDemoDepartmentHrAssignments,
    readDemoDepartments,
    readDemoEmployees,
    readDemoTeamLeadAssignments,
} from "../../preview/directory";
import {
    initialAccessRecords,
    initialAppointments,
    initialEmployees,
    initialStaffAccounts,
} from "../../preview/fixtures/workspace";
import { readDemoManagerAssignments } from "../../preview/manager-assignments";
import {
    type AccessRecord,
    type Appointment,
    type AppointmentStatus,
    type DashboardMetrics,
    type Department,
    type Employee,
    type Role,
    type View,
} from "../../types/workspace";
import { useCallback, useRef, useState } from "react";

export type WorkspaceIdentity = { role: Role; userEmail: string; onLogout: () => void | Promise<void> };

export function useWorkspaceState({ role, userEmail, onLogout }: WorkspaceIdentity) {
    const [view, setView] = useState<View>("overview");
    const [appointments, setAppointments] = useState(() => isBackendConfigured
        ? [] : readPreviewWorkspaceAppointments());
    const [employees, setEmployees] = useState<Employee[]>(() => isBackendConfigured ? [] : readDemoEmployees());
    const [appointmentHosts, setAppointmentHosts] = useState<Employee[]>([]);
    const [staffAccounts, setStaffAccounts] = useState<StaffAccount[]>(() => isBackendConfigured
        ? [] : mergeDemoStaffAccounts(initialStaffAccounts));
    const [departments, setDepartments] = useState<Department[]>(() => isBackendConfigured
        ? [] : readDemoDepartments());
    const [departmentSummaries, setDepartmentSummaries] = useState<DepartmentEmployeeSummary[]>([]);
    const [teamLeadAssignments, setTeamLeadAssignments] = useState<TeamLeadAssignment[]>(() => isBackendConfigured
        ? [] : readDemoTeamLeadAssignments());
    const [departmentHrAssignments, setDepartmentHrAssignments] = useState<DepartmentHrAssignment[]>(() => isBackendConfigured
        ? [] : readDemoDepartmentHrAssignments());
    const [managerAssignments, setManagerAssignments] = useState<ManagerAssignment[]>(() => isBackendConfigured
        ? [] : readDemoManagerAssignments());
    const [metrics, setMetrics] = useState<DashboardMetrics>(() => isBackendConfigured ? {
        awaitingApproval: 0, activeVisits: 0, visitorsInside: 0, totalEmployees: 0, activeEmployees: 0, arrivedVisits: 0,
        metricsLoadState: "loading", freshness: "UNKNOWN",
    } : {
        awaitingApproval: initialAppointments.filter((item) => ["Pending", "Awaiting Security", "Awaiting Reception", "Awaiting HR", "Awaiting Team Lead", "Awaiting Manager", "Awaiting CEO"].includes(item.status)).length,
        activeVisits: initialAppointments.filter((item) => ["Approved", "Checked in"].includes(item.status)).length,
        visitorsInside: initialAppointments.filter((item) => item.status === "Checked in").length,
        totalEmployees: initialEmployees.length, activeEmployees: initialEmployees.filter((item) => item.status === "Active").length,
        arrivedVisits: initialAppointments.filter((item) => Boolean(item.securityIntakeAt) || item.status === "Checked in").length,
    });
    const [accessRecords, setAccessRecords] = useState<AccessRecord[]>(() =>
        isBackendConfigured ? [] : initialAccessRecords,
    );
    const [employeeModal, setEmployeeModal] = useState(false);
    const [terminationEmployee, setTerminationEmployee] = useState<Employee | null>(null);
    const [employeeDepartmentId, setEmployeeDepartmentId] = useState<string>();
    const [employeeAccountId, setEmployeeAccountId] = useState<string>();
    const [visitModal, setVisitModal] = useState(false);
    const [securityIntakeAppointment, setSecurityIntakeAppointment] = useState<Appointment | null>(null);
    const [privacyOpen, setPrivacyOpen] = useState(false);
    const [sidebarOpen, setSidebarOpen] = useState(false);
    const [profileMenuOpen, setProfileMenuOpen] = useState(false);
    const [loggingOut, setLoggingOut] = useState(false);
    const profileMenuRef = useRef<HTMLDivElement>(null);
    const [operationError, setOperationError] = useState("");
    const [unreadNotifications, setUnreadNotifications] = useState(0);
    const [soundEnabled, setSoundEnabled] = useState(notificationSoundEnabled);
    const previousUnreadRef = useRef<number | null>(null);
    const appointmentSoundSnapshotRef = useRef<Map<string, AppointmentStatus> | null>(null);
    const [globalSearch, setGlobalSearch] = useState("");
    const [workspaceRevision, setWorkspaceRevision] = useState(0);
    const [approvedRecovery, setApprovedRecovery] = useState<AccountRecoveryRequest | null>(null);
    const [liveState, setLiveState] = useState<RealtimeConnectionState>(isBackendConfigured ? "connecting" : "offline");
    const [lastLiveUpdate, setLastLiveUpdate] = useState<Date | null>(null);
    const [workspaceConnectionFailure, setWorkspaceConnectionFailure] = useState(false);
    const [workspaceRetrying, setWorkspaceRetrying] = useState(false);
    const [profilePhotoUrl, setProfilePhotoUrl] = useState<string | null>(() =>
        isBackendConfigured || typeof window === "undefined" ? null
            : window.localStorage.getItem(`brainserve.demo.profile.photo.${userEmail.toLowerCase()}`),
    );
    const [profileName, setProfileName] = useState(() => isBackendConfigured ? role : readDemoAccounts()
        .find((account) => account.email.toLowerCase() === userEmail.toLowerCase())?.fullName ?? role);
    const handleProfileUpdated = useCallback((profile: MyProfile) => {
        setProfilePhotoUrl(profile.photoUrl);
        setProfileName(profile.fullName);
    }, []);

    const refreshPreviewWorkspace = useCallback(() => {
        if (isBackendConfigured) return;
        setAppointments(readPreviewWorkspaceAppointments());
        setEmployees(readDemoEmployees());
        setDepartments(readDemoDepartments());
        setTeamLeadAssignments(readDemoTeamLeadAssignments());
        setDepartmentHrAssignments(readDemoDepartmentHrAssignments());
        setManagerAssignments(readDemoManagerAssignments());
        setLastLiveUpdate(new Date());
    }, []);
    return { role, userEmail, onLogout, view, setView, appointments, setAppointments, employees, setEmployees, appointmentHosts, setAppointmentHosts, staffAccounts, setStaffAccounts, departments, setDepartments, departmentSummaries, setDepartmentSummaries, teamLeadAssignments, setTeamLeadAssignments, departmentHrAssignments, setDepartmentHrAssignments, managerAssignments, setManagerAssignments, metrics, setMetrics, accessRecords, setAccessRecords, employeeModal, setEmployeeModal, terminationEmployee, setTerminationEmployee, employeeDepartmentId, setEmployeeDepartmentId, employeeAccountId, setEmployeeAccountId, visitModal, setVisitModal, securityIntakeAppointment, setSecurityIntakeAppointment, privacyOpen, setPrivacyOpen, sidebarOpen, setSidebarOpen, profileMenuOpen, setProfileMenuOpen, loggingOut, setLoggingOut, profileMenuRef, operationError, setOperationError, unreadNotifications, setUnreadNotifications, soundEnabled, setSoundEnabled, previousUnreadRef, appointmentSoundSnapshotRef, globalSearch, setGlobalSearch, workspaceRevision, setWorkspaceRevision, approvedRecovery, setApprovedRecovery, liveState, setLiveState, lastLiveUpdate, setLastLiveUpdate, workspaceConnectionFailure, setWorkspaceConnectionFailure, workspaceRetrying, setWorkspaceRetrying, profilePhotoUrl, setProfilePhotoUrl, profileName, setProfileName, handleProfileUpdated, refreshPreviewWorkspace };
}

export type WorkspaceState = ReturnType<typeof useWorkspaceState>;
