"use client";

import { appointmentStatusFromApi, visitorInitials, visitTypeLabel } from "../../features/appointments/appointment-utils";
import { employeeStatusLabel } from "../../features/employees/employee-utils";
import { ApiError, brainServeApi, isBackendConfigured } from "../../lib/api";
import { formatOfficeDate, formatOfficeTime, type PublicHost } from "../../lib/appointments";
import { type Employee } from "../../shared/types/workspace";
import { fail, isConnectivityFailure } from "../../shared/utils/errors";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect, useRef } from "react";

export function useWorkspaceData(workspace: Pick<WorkspaceState, "role" | "setDepartments" | "setManagerAssignments" | "setEmployees" | "setDepartmentSummaries" | "setTeamLeadAssignments" | "setDepartmentHrAssignments" | "setAppointmentHosts" | "setAppointments" | "setMetrics" | "setAccessRecords" | "setStaffAccounts" | "setWorkspaceRetrying" | "setWorkspaceConnectionFailure" | "setOperationError" | "setLastLiveUpdate" | "workspaceRevision">) {
    const { role, setDepartments, setManagerAssignments, setEmployees, setDepartmentSummaries, setTeamLeadAssignments, setDepartmentHrAssignments, setAppointmentHosts, setAppointments, setMetrics, setAccessRecords, setStaffAccounts, setWorkspaceRetrying, setWorkspaceConnectionFailure, setOperationError, setLastLiveUpdate, workspaceRevision } = workspace;

    const hasLoadedCoreData = useRef(false);
    useEffect(() => {
        if (!isBackendConfigured) return;
        let active = true;
        const loadWorkspace = async () => {
            const errors: string[] = [];
            let coreLoads = 0;
            let connectivityFailures = 0;
            let hostNames = new Map<string, string>();
            let hostCategories = new Map<string, PublicHost["category"]>();
            if (role === "System Admin") {
                try {
                    const [departmentList, managerAssignmentList] = await Promise.all([
                        brainServeApi.departments(),
                        brainServeApi.managerAssignments(),
                    ]);
                    if (!active) return;
                    setDepartments(departmentList);
                    setManagerAssignments(managerAssignmentList);
                } catch (reason) {
                    errors.push(reason instanceof ApiError ? reason.message
                        : "The System Admin department directory could not be loaded from the database.");
                }
            } else if (role === "Team Lead") {
                try {
                    const workspace = await brainServeApi.myTeamLeadWorkspace().catch(async () => {
                        const [assignment, employees, visibleDepartments] = await Promise.all([
                            brainServeApi.myTeamLeadAssignment(),
                            brainServeApi.myTeam(),
                            brainServeApi.visibleDepartments(),
                        ]);
                        const department = visibleDepartments.find((item) => item.id === assignment.departmentId);
                        if (!department) fail("Your assigned department is unavailable.");
                        return { assignment, department, employees };
                    });
                    if (!active) return;
                    const assignment = workspace.assignment;
                    const department = { ...workspace.department, active: true, version: 0 };
                    const nextEmployees: Employee[] = workspace.employees.content.map((item) => ({
                        id: item.employeeNumber, uuid: item.id, departmentId: item.departmentId,
                        name: item.displayName, initials: visitorInitials(item.displayName),
                        role: item.designation, department: department.name,
                        email: item.officialEmail, lifecycleProtected: item.lifecycleProtected,
                        status: employeeStatusLabel(item.status),
                    }));
                    setEmployees(nextEmployees); setDepartments([department]);
                    setDepartmentSummaries([{ departmentId: assignment.departmentId,
                        totalEmployees: workspace.employees.totalElements ?? nextEmployees.length,
                        activeEmployees: nextEmployees.filter((item) => item.status === "Active").length,
                        onLeaveEmployees: nextEmployees.filter((item) => item.status === "On leave").length,
                        onboardingEmployees: nextEmployees.filter((item) => item.status === "Onboarding").length }]);
                    setTeamLeadAssignments([{ id: assignment.assignmentId, departmentId: assignment.departmentId,
                        teamLeadUserId: assignment.teamLeadUserId, teamLeadEmployeeId: assignment.teamLeadEmployeeId,
                        active: true, assignedByUserId: "", assignedAt: "", endedByUserId: null, endedAt: null }]);
                    hostNames = new Map(nextEmployees.map((item) => [item.uuid ?? item.id, item.name]));
                } catch (reason) { errors.push(reason instanceof ApiError ? reason.message : "Your Team Lead workspace could not be loaded."); }
            } else if (!["Security", "System Admin"].includes(role)) {
                const departmentRequest = ["HR Admin", "Manager", "Employee"].includes(role)
                    ? brainServeApi.visibleDepartments()
                    : role === "Reception"
                        ? brainServeApi.publicDepartments()
                        : brainServeApi.departments();
                const [employeeResult, departmentResult] = await Promise.allSettled([
                    brainServeApi.employees(),
                    departmentRequest,
                ] as const);
                const [summaryResult, assignmentResult, hrAssignmentResult,
                    managerAssignmentResult] = await Promise.allSettled([
                    ["CEO", "HR Admin", "Manager"].includes(role)
                        ? brainServeApi.departmentEmployeeSummary()
                        : Promise.resolve([]),
                    ["CEO", "HR Admin"].includes(role)
                        ? brainServeApi.teamLeadAssignments()
                        : Promise.resolve([]),
                    ["CEO", "HR Admin"].includes(role)
                        ? brainServeApi.departmentHrAssignments()
                        : Promise.resolve([]),
                    role === "CEO"
                        ? brainServeApi.managerAssignments()
                        : role === "Manager"
                            ? brainServeApi.myManagerAssignment().then((assignment) => [{
                                id: assignment.assignmentId,
                                departmentId: assignment.departmentId,
                                managerUserId: assignment.managerUserId,
                                managerEmployeeId: assignment.managerEmployeeId,
                                active: true,
                                assignedByUserId: "",
                                assignedAt: "",
                                endedByUserId: null,
                                endedAt: null,
                            }])
                            : Promise.resolve([]),
                ] as const);
                if (!active) return;

                const departmentList = departmentResult.status === "fulfilled"
                    ? departmentResult.value
                    : [];
                if (departmentResult.status === "fulfilled") {
                    setDepartments(departmentList);
                } else {
                    errors.push(departmentResult.reason instanceof ApiError
                        ? departmentResult.reason.message
                        : "The department directory could not be loaded.");
                }

                if (summaryResult.status === "fulfilled") setDepartmentSummaries(summaryResult.value);
                else errors.push(summaryResult.reason instanceof ApiError
                    ? summaryResult.reason.message : "Department totals could not be loaded.");
                if (assignmentResult.status === "fulfilled") setTeamLeadAssignments(assignmentResult.value);
                else errors.push(assignmentResult.reason instanceof ApiError
                    ? assignmentResult.reason.message : "Team Lead assignments could not be loaded.");
                if (hrAssignmentResult.status === "fulfilled") setDepartmentHrAssignments(hrAssignmentResult.value);
                else errors.push(hrAssignmentResult.reason instanceof ApiError
                    ? hrAssignmentResult.reason.message : "Department HR assignments could not be loaded.");
                if (managerAssignmentResult.status === "fulfilled") setManagerAssignments(managerAssignmentResult.value);
                else errors.push(managerAssignmentResult.reason instanceof ApiError
                    ? managerAssignmentResult.reason.message : "Manager assignments could not be loaded.");

                if (employeeResult.status === "fulfilled") {
                    const departmentNames = new Map(departmentList.map((item) => [item.id, item.name]));
                    const nextEmployees: Employee[] = employeeResult.value.content.map((item) => ({
                        id: item.employeeNumber,
                        uuid: item.id,
                        departmentId: item.departmentId,
                        name: item.displayName,
                        initials: visitorInitials(item.displayName),
                        role: item.designation,
                        department: departmentNames.get(item.departmentId) ?? "Assigned department",
                        email: item.officialEmail,
                        lifecycleProtected: item.lifecycleProtected,
                        status: employeeStatusLabel(item.status),
                    }));
                    setEmployees(nextEmployees);
                    hostNames = new Map(nextEmployees.map((item) => [item.uuid ?? item.id, item.name]));
                } else {
                    errors.push(employeeResult.reason instanceof ApiError
                        ? employeeResult.reason.message
                        : "Employee data could not be loaded.");
                }
            }
            if (role === "Security" || role === "Reception") {
                try {
                    const [publicHosts, publicDepartments] = await Promise.all([
                        brainServeApi.publicHosts(),
                        role === "Security" ? brainServeApi.publicDepartments() : Promise.resolve([]),
                    ]);
                    if (!active) return;
                    const securityHosts: Employee[] = publicHosts.map((host) => ({
                        id: host.id, uuid: host.id, departmentId: host.departmentId,
                        name: host.displayName, initials: visitorInitials(host.displayName),
                        role: host.designation, department: host.departmentName, hostCategory: host.category,
                        email: "", status: "Active",
                    }));
                    setAppointmentHosts(securityHosts);
                    if (role === "Security") {
                        setEmployees(securityHosts);
                    }
                    // Reception already loads the authoritative department directory above. Do not replace it
                    // with the much smaller set of departments that currently have an HR host assignment.
                    // Security cannot read the private employee directory. Its form uses the bounded public
                    // department directory and only retrieves employee names after a department is selected.
                    if (role === "Security") {
                        setDepartments(publicDepartments.sort((left, right) => left.name.localeCompare(right.name)));
                    }
                    hostNames = new Map(securityHosts.map((item) => [item.uuid ?? item.id, item.name]));
                    hostCategories = new Map(publicHosts.map((item) => [item.id, item.category]));
                } catch (reason) { errors.push(reason instanceof ApiError ? reason.message : "Appointment hosts could not be loaded."); }
            }
            if (role !== "System Admin") {
                try {
                    const appointmentPage = await brainServeApi.appointments();
                    if (!active) return;
                    setAppointments(appointmentPage.content.map((item) => {
                        return {
                            id: item.id, initials: visitorInitials(item.visitorName), visitor: item.visitorName,
                            visitorEmail: item.visitorEmail, visitorPhone: item.visitorPhone,
                            company: item.visitorCompany ?? "Independent",
                            host: hostNames.get(item.requestedEmployeeId ?? item.hostEmployeeId) ?? "BrainServe host",
                            purpose: item.purpose, time: formatOfficeTime(item.slotStart),
                            date: formatOfficeDate(item.slotStart, { year: undefined }),
                            status: appointmentStatusFromApi(item.status), type: visitTypeLabel(item.type), referenceNumber: item.referenceNumber,
                            hostEmployeeId: item.hostEmployeeId, hostCategory: hostCategories.get(item.hostEmployeeId),
                            routingDepartmentId: item.routingDepartmentId,
                            requestedEmployeeId: item.requestedEmployeeId, slotStart: item.slotStart,
                            securityIntakeActorId: item.securityIntakeActorId, securityIntakeAt: item.securityIntakeAt,
                            arrivalVisitorName: item.arrivalVisitorName, arrivalPurpose: item.arrivalPurpose,
                            identityDocumentType: item.identityDocumentType, identityDocumentLastFour: item.identityDocumentLastFour,
                            securityNotes: item.securityNotes, receptionVerificationActorId: item.receptionVerificationActorId,
                            receptionVerifiedAt: item.receptionVerifiedAt,
                            receptionVerificationRemarks: item.receptionVerificationRemarks,
                            hrApprovalActorId: item.hrApprovalActorId, hrDecisionAt: item.hrDecisionAt,
                            hrDecisionRemarks: item.hrDecisionRemarks,
                            teamLeadApprovalActorId: item.teamLeadApprovalActorId,
                            teamLeadDecisionAt: item.teamLeadDecisionAt,
                            teamLeadDecisionRemarks: item.teamLeadDecisionRemarks,
                            managerApprovalActorId: item.managerApprovalActorId,
                            managerDecisionAt: item.managerDecisionAt,
                            managerDecisionRemarks: item.managerDecisionRemarks,
                            ceoApprovalActorId: item.ceoApprovalActorId,
                            ceoDecisionAt: item.ceoDecisionAt,
                            ceoDecisionRemarks: item.ceoDecisionRemarks,
                            receptionForwardActorId: item.receptionForwardActorId,
                            receptionForwardedAt: item.receptionForwardedAt,
                            receptionForwardRemarks: item.receptionForwardRemarks,
                            createdAt: item.createdAt, assignedToCurrentActor: item.assignedToCurrentActor,
                        };
                    }));
                    coreLoads += 1;
                    hasLoadedCoreData.current = true;
                } catch (reason) {
                    if (isConnectivityFailure(reason)) connectivityFailures += 1;
                    errors.push(reason instanceof ApiError ? reason.message : "Appointment data could not be loaded.");
                }
            }
            if (role !== "System Admin") {
                try {
                    const summary = await brainServeApi.dashboard();
                    if (active) {
                        setMetrics({ ...summary, arrivedVisits: summary.arrivedVisits ?? 0 });
                    }
                    if (!active) return;
                    coreLoads += 1;
                    hasLoadedCoreData.current = true;
                } catch (reason) {
                    if (isConnectivityFailure(reason)) connectivityFailures += 1;
                    errors.push(reason instanceof ApiError ? reason.message : "Dashboard metrics could not be loaded.");
                }
            }
            if (["Reception", "Security", "CEO"].includes(role)) {
                try {
                    const records = await brainServeApi.visitorsInside();
                    if (active) setAccessRecords(records);
                } catch (reason) { errors.push(reason instanceof ApiError ? reason.message : "Visitor occupancy could not be loaded."); }
            }
            if (role === "HR Admin") {
                try {
                    const accounts = await brainServeApi.staffAccounts();
                    if (active) setStaffAccounts(accounts);
                } catch (reason) { errors.push(reason instanceof ApiError ? reason.message : "Staff accounts could not be loaded."); }
            } else if (role === "CEO") {
                try {
                    const candidates = await brainServeApi.departmentHrCandidates();
                    if (active) setStaffAccounts(candidates.map((candidate) => ({ ...candidate, roles: ["ROLE_HR_ADMIN"],
                        enabled: true, forcePasswordChange: false, status: "ACTIVE", grantedPermissions: [], deniedPermissions: [],
                        effectivePermissions: [] })));
                } catch (reason) { errors.push(reason instanceof ApiError ? reason.message : "Department HR candidates could not be loaded."); }
            }
            if (active) {
                setWorkspaceRetrying(false);
                setWorkspaceConnectionFailure(
                    role !== "System Admin"
                    && !hasLoadedCoreData.current
                    && coreLoads === 0
                    && connectivityFailures >= 2,
                );
                setOperationError(errors.join(" "));
                setLastLiveUpdate(new Date());
            }
        };
        void loadWorkspace();
        return () => { active = false; };
    }, [role, setAccessRecords, setAppointmentHosts, setAppointments, setDepartmentHrAssignments, setDepartmentSummaries, setDepartments, setEmployees, setLastLiveUpdate, setManagerAssignments, setMetrics, setOperationError, setStaffAccounts, setTeamLeadAssignments, setWorkspaceConnectionFailure, setWorkspaceRetrying, workspaceRevision]);
}
