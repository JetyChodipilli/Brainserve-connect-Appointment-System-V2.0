"use client";

import {
    type AccountClosureCandidate,
    type AccountClosureRequest,
    brainServeApi,
    isBackendConfigured,
    type MyProfile,
    type RoleDepartmentChangeRequest,
    type StaffAccount,
} from "../../services/brainserve-api";
import { officeToday } from "../../lib/appointments";
import { readableNotificationRole } from "../../lib/internal-notifications";
import { readDemoAccounts } from "../../preview/accounts";
import { readDemoDepartmentHrAssignments, readDemoTeamLeadAssignments } from "../../preview/directory";
import {
    readDemoAccountClosures,
    readDemoRoleDepartmentChanges,
    recordDemoClosureTransition,
    writeDemoAccountClosures,
    writeDemoRoleDepartmentChanges,
} from "../../preview/governance";
import { readDemoManagerAssignments } from "../../preview/manager-assignments";
import { readDemoInternalNotifications, writeDemoInternalNotifications } from "../../preview/notifications";
import { PageTitle } from "../../components/ui/page-title";
import { StatusPill } from "../../components/ui/status-pill";
import { ROLE_AUTHORITY_BY_LABEL } from "../../config/roles";
import { type Department, type Employee, type Role } from "../../types/workspace";
import { fail } from "../../utils/errors";
import { newClientId } from "../../utils/ids";
import { visitorInitials } from "../appointments/appointment-utils";
import {
    Archive,
    ArrowRight,
    BriefcaseBusiness,
    Building2,
    CheckCircle2,
    CircleUserRound,
    FileClock,
    Fingerprint,
    IdCard,
    Send,
    ShieldCheck,
    UserCog,
} from "lucide-react";
import { type FormEvent, useCallback, useEffect, useMemo, useState } from "react";

export function MyProfileView({ role, userEmail, departments, employees, staffAccounts, onProfileUpdated }: {
    role: Role; userEmail: string; departments: Department[]; employees: Employee[]; staffAccounts: StaffAccount[];
    onProfileUpdated: (profile: MyProfile) => void;
}) {
    const demoProfile = useCallback((): MyProfile => {
        const savedAccount = readDemoAccounts().find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
        const staffAccount = staffAccounts.find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
        const employeeId = savedAccount?.employeeId ?? staffAccount?.employeeId ?? null;
        const employee = employees.find((item) => item.email.toLowerCase() === userEmail.toLowerCase()
            || Boolean(employeeId && (item.uuid ?? item.id) === employeeId));
        const department = departments.find((item) => item.id === employee?.departmentId);
        const photoUrl = typeof window === "undefined" ? null
            : window.localStorage.getItem(`brainserve.demo.profile.photo.${userEmail.toLowerCase()}`);
        return {
            userId: savedAccount?.id ?? staffAccount?.userId ?? `demo-${role.toLowerCase().replaceAll(" ", "-")}`,
            employeeId: employeeId ?? employee?.uuid ?? null,
            fullName: savedAccount?.fullName ?? staffAccount?.fullName ?? employee?.name
                ?? (role === "System Admin" ? "Jety Chodipilli" : role === "Reception" ? "Reception Desk" : role === "Security" ? "Security Desk" : role),
            email: userEmail, roles: [ROLE_AUTHORITY_BY_LABEL[role]], employeeNumber: employee?.id ?? null,
            designation: employee?.role ?? (role === "System Admin" ? "System Administrator" : role),
            employeeStatus: employee?.status?.toUpperCase().replaceAll(" ", "_") ?? (staffAccount?.enabled ? "ACTIVE" : null),
            departmentId: department?.id ?? null, departmentCode: department?.code ?? null,
            departmentName: department?.name ?? null, departmentActive: department?.active ?? null,
            photoDocumentId: null, photoUrl, photoUrlExpiresAt: null,
        };
    }, [departments, employees, role, staffAccounts, userEmail]);
    const [profile, setProfile] = useState<MyProfile>(() => isBackendConfigured ? {
        userId: "", employeeId: null, fullName: role, email: userEmail,
        roles: [ROLE_AUTHORITY_BY_LABEL[role]], employeeNumber: null, designation: role,
        employeeStatus: null, departmentId: null, departmentCode: null, departmentName: null,
        departmentActive: null, photoDocumentId: null, photoUrl: null, photoUrlExpiresAt: null,
    } : demoProfile());
    const [backendProfileLoaded, setBackendProfileLoaded] = useState(!isBackendConfigured);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [message, setMessage] = useState("");
    const [changeRequests, setChangeRequests] = useState<RoleDepartmentChangeRequest[]>(() => !isBackendConfigured
        ? readDemoRoleDepartmentChanges().filter((item) => item.requesterUserId === profile.userId) : []);
    const [targetDepartmentId, setTargetDepartmentId] = useState("");
    const [changeBusy, setChangeBusy] = useState(false);
    const [closureRequests, setClosureRequests] = useState<AccountClosureRequest[]>(() => !isBackendConfigured
        ? readDemoAccountClosures().filter((item) => item.requesterUserId === profile.userId) : []);
    const [closureCandidates, setClosureCandidates] = useState<AccountClosureCandidate[]>([]);
    const [closureBusy, setClosureBusy] = useState(false);
    const demoClosureCandidates = useMemo((): AccountClosureCandidate[] => {
        if (isBackendConfigured) return [];
        const authority = role === "HR Admin" ? "ROLE_HR_ADMIN" : role === "Manager" ? "ROLE_MANAGER"
            : role === "Team Lead" ? "ROLE_TEAM_LEAD"
                : role === "Reception" ? "ROLE_RECEPTIONIST" : role === "Security" ? "ROLE_SECURITY" : "ROLE_CEO";
        const accounts = readDemoAccounts().filter((item) => item.status === "ACTIVE" && item.id !== profile.userId);
        const candidates = role === "Team Lead"
            ? accounts.filter((item) => item.role === "ROLE_EMPLOYEE").filter((item) => {
                const employee = employees.find((value) => value.email.toLowerCase() === item.email.toLowerCase());
                return Boolean(employee && employee.departmentId === profile.departmentId && employee.status === "Active");
            })
            : accounts.filter((item) => item.role === authority)
                .filter((item) => role !== "HR Admin" || !readDemoDepartmentHrAssignments()
                    .some((assignment) => assignment.active && assignment.hrUserId === item.id))
                .filter((item) => role !== "Manager" || !readDemoManagerAssignments()
                    .some((assignment) => assignment.active && assignment.managerUserId === item.id));
        return candidates.map((item) => ({ userId: item.id, fullName: item.fullName,
            email: item.email, role: item.role, employeeId: item.employeeId ?? null, departmentId: profile.departmentId }));
    }, [employees, profile.departmentId, profile.userId, role]);
    const availableClosureCandidates = isBackendConfigured ? closureCandidates : demoClosureCandidates;

    useEffect(() => {
        if (!isBackendConfigured) return;
        let active = true;
        brainServeApi.myProfile().then((value) => { if (active) { setProfile(value); setBackendProfileLoaded(true); onProfileUpdated(value); setError(""); } })
            .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "Your profile could not be loaded."); });
        if (["HR Admin", "Team Lead"].includes(role)) {
            brainServeApi.myRoleDepartmentChanges().then((items) => { if (active) setChangeRequests(items); })
                .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "Department change requests could not be loaded."); });
        }
        return () => { active = false; };
    }, [demoProfile, onProfileUpdated, role]);

    useEffect(() => {
        const eligible = ["CEO", "HR Admin", "Team Lead", "Reception", "Security"].includes(role);
        if (!eligible) return;
        if (isBackendConfigured) {
            if (!backendProfileLoaded) return;
            let active = true;
            Promise.all([brainServeApi.myAccountClosures(), brainServeApi.accountClosureCandidates(profile.userId)])
                .then(([requests, candidates]) => { if (active) { setClosureRequests(requests); setClosureCandidates(candidates); } })
                .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "Account lifecycle data could not be loaded."); });
            return () => { active = false; };
        }
        return;
    }, [backendProfileLoaded, employees, profile.departmentId, profile.userId, role]);

    const uploadPhoto = async (file: File | undefined) => {
        if (!file) return;
        setError(""); setMessage("");
        if (!["image/jpeg", "image/png"].includes(file.type)) { setError("Choose a JPEG or PNG image."); return; }
        if (file.size > 10 * 1024 * 1024) { setError("Profile photos must be 10 MB or smaller."); return; }
        setBusy(true);
        try {
            if (isBackendConfigured) {
                const updated = await brainServeApi.uploadMyProfilePhoto(file);
                setProfile(updated); onProfileUpdated(updated);
            }
            else {
                const photoUrl = await new Promise<string>((resolve, reject) => {
                    const reader = new FileReader(); reader.onload = () => resolve(String(reader.result));
                    reader.onerror = () => reject(new Error("The selected image could not be read.")); reader.readAsDataURL(file);
                });
                window.localStorage.setItem(`brainserve.demo.profile.photo.${userEmail.toLowerCase()}`, photoUrl);
                setProfile((current) => { const updated = { ...current, photoUrl }; onProfileUpdated(updated); return updated; });
            }
            setMessage("Your profile photo was updated securely.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Your profile photo could not be uploaded."); }
        finally { setBusy(false); }
    };

    const roles = profile.roles.map(readableNotificationRole);
    const canRequestDepartmentChange = role === "HR Admin" || role === "Team Lead";
    const pendingChange = changeRequests.find((item) => item.status === "PENDING");
    const selectedTarget = departments.find((item) => item.id === targetDepartmentId);
    const targetHrAssignment = !isBackendConfigured && role === "HR Admin"
        ? readDemoDepartmentHrAssignments().find((item) => item.active && item.departmentId === targetDepartmentId)
        : undefined;
    const targetTeamLeadAssignment = !isBackendConfigured && role === "Team Lead"
        ? readDemoTeamLeadAssignments().find((item) => item.active && item.departmentId === targetDepartmentId)
        : undefined;
    const targetOccupantName = role === "HR Admin"
        ? [...staffAccounts, ...readDemoAccounts().map((item) => ({ userId: item.id, fullName: item.fullName } as StaffAccount))]
            .find((item) => item.userId === targetHrAssignment?.hrUserId)?.fullName
        : employees.find((item) => (item.uuid ?? item.id) === targetTeamLeadAssignment?.teamLeadEmployeeId)?.name;

    const requestDepartmentChange = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setError(""); setMessage(""); setChangeBusy(true);
        const form = event.currentTarget; const data = new FormData(form);
        try {
            let created: RoleDepartmentChangeRequest;
            const payload = { targetDepartmentId: String(data.get("targetDepartmentId")),
                reason: String(data.get("reason")), phoneNumber: String(data.get("phoneNumber") ?? "") || null,
                designation: String(data.get("designation") ?? "") || profile.designation || role,
                joiningDate: String(data.get("joiningDate") ?? "") || officeToday() };
            if (isBackendConfigured) created = await brainServeApi.requestRoleDepartmentChange(payload);
            else {
                const target = departments.find((item) => item.id === payload.targetDepartmentId);
                if (!target) fail("Select an active department.");
                if (role === "Team Lead" && !readDemoDepartmentHrAssignments().some((item) => item.active && item.departmentId === target.id)) {
                    fail("The destination department needs an assigned HR Admin before a Team Lead can request access.");
                }
                const occupantUserId = role === "HR Admin" ? targetHrAssignment?.hrUserId
                    : targetTeamLeadAssignment?.teamLeadUserId;
                created = { id: newClientId(), requesterUserId: profile.userId, requesterEmployeeId: profile.employeeId,
                    requesterName: profile.fullName, requesterEmail: profile.email,
                    requesterRole: role === "HR Admin" ? "HR_ADMIN" : "TEAM_LEAD",
                    fromDepartmentId: profile.departmentId, fromDepartmentName: profile.departmentName,
                    targetDepartmentId: target.id, targetDepartmentName: target.name,
                    targetOccupied: Boolean(occupantUserId && occupantUserId !== profile.userId),
                    targetOccupantUserId: occupantUserId ?? null, targetOccupantName: targetOccupantName ?? null,
                    reason: payload.reason.trim(), status: "PENDING", requestedAt: new Date().toISOString(),
                    resolution: null, decisionNote: null, decidedByUserId: null, decidedAt: null };
                writeDemoRoleDepartmentChanges([created, ...readDemoRoleDepartmentChanges()]);
                const allAccounts = readDemoAccounts();
                const recipient = role === "HR Admin"
                    ? allAccounts.find((item) => item.status === "ACTIVE" && item.role === "ROLE_CEO")
                    : allAccounts.find((item) => item.id === readDemoDepartmentHrAssignments()
                        .find((item) => item.active && item.departmentId === target.id)?.hrUserId);
                const now = new Date().toISOString();
                writeDemoInternalNotifications([{ id: newClientId(), senderUserId: profile.userId,
                    recipientUserId: recipient?.id ?? (role === "HR Admin" ? "demo-ceo" : "demo-hr-admin"),
                    senderName: profile.fullName, recipientName: recipient?.fullName ?? (role === "HR Admin" ? "BrainServe CEO" : "Department HR Admin"),
                    message: `${profile.fullName} requested a ${role} department change to ${target.name}. Review it in Settings → Roles & responsibilities.`,
                    priority: "HIGH", category: "ACTION_REQUIRED",
                    conversationKey: `role-department-change:${created.id}`, deliveryStatus: "DELIVERED",
                    sentAt: now, deliveredAt: now, readAt: null, senderEmail: profile.email,
                    recipientEmail: recipient?.email ?? (role === "HR Admin" ? "ceo@brainserve.in" : "hr.admin@brainserve.in") },
                    ...readDemoInternalNotifications()]);
            }
            setChangeRequests((items) => [created, ...items]); setTargetDepartmentId(""); form.reset();
            setMessage(`Department change request sent to ${role === "HR Admin" ? "CEO" : "the destination department HR"}.`);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The department change request could not be sent."); }
        finally { setChangeBusy(false); }
    };

    const cancelDepartmentChange = async (request: RoleDepartmentChangeRequest) => {
        setChangeBusy(true); setError("");
        try {
            const updated = isBackendConfigured ? await brainServeApi.cancelRoleDepartmentChange(request.id)
                : { ...request, status: "CANCELLED" as const, decidedByUserId: profile.userId, decidedAt: new Date().toISOString() };
            if (!isBackendConfigured) writeDemoRoleDepartmentChanges(readDemoRoleDepartmentChanges()
                .map((item) => item.id === request.id ? updated : item));
            setChangeRequests((items) => items.map((item) => item.id === request.id ? updated : item));
            setMessage("Department change request cancelled.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The request could not be cancelled."); }
        finally { setChangeBusy(false); }
    };

    const requestAccountClosure = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setClosureBusy(true); setError(""); setMessage("");
        const form = event.currentTarget; const data = new FormData(form);
        const reason = String(data.get("reason") ?? "").trim();
        const effectiveDate = String(data.get("effectiveDate") ?? "");
        const replacementUserId = String(data.get("replacementUserId") ?? "") || null;
        try {
            let created: AccountClosureRequest;
            if (isBackendConfigured) created = await brainServeApi.requestMyAccountClosure(reason, effectiveDate, replacementUserId);
            else {
                const authority = role === "HR Admin" ? "ROLE_HR_ADMIN" : role === "Manager" ? "ROLE_MANAGER"
                    : role === "Team Lead" ? "ROLE_TEAM_LEAD"
                        : role === "Reception" ? "ROLE_RECEPTIONIST" : role === "Security" ? "ROLE_SECURITY" : "ROLE_CEO";
                if (readDemoAccountClosures().some((item) => item.targetUserId === profile.userId
                    && ["REQUESTED", "BUSINESS_APPROVED", "PENDING_SYSTEM_ADMIN", "SCHEDULED"].includes(item.status))) {
                    fail("This account already has an open closure request.");
                }
                if (!["Reception", "Security"].includes(role) && !replacementUserId) {
                    fail("Select an active replacement so responsibilities are not orphaned.");
                }
                const replacement = availableClosureCandidates.find((item) => item.userId === replacementUserId);
                const now = new Date().toISOString();
                created = { id: newClientId(), targetUserId: profile.userId, targetName: profile.fullName,
                    targetEmail: profile.email, targetRole: authority, employeeId: profile.employeeId,
                    departmentId: profile.departmentId, departmentName: profile.departmentName,
                    requesterUserId: profile.userId, origin: "SELF_SERVICE", reason,
                    requestedEffectiveDate: effectiveDate, replacementUserId,
                    replacementName: replacement?.fullName ?? null, status: "REQUESTED", requestedAt: now,
                    businessApproverUserId: null, businessApprovedAt: null, systemAdminApproverUserId: null,
                    systemAdminApprovedAt: null, decisionNote: null, scheduledAt: null, archivedAt: null,
                    cancelledAt: null };
                writeDemoAccountClosures([created, ...readDemoAccountClosures()]);
                recordDemoClosureTransition(created, "ACCOUNT_CLOSURE_REQUESTED", null, profile.userId,
                    "Self-service account closure requested");
            }
            setClosureRequests((items) => [created, ...items]); form.reset();
            setMessage(role === "CEO" ? "Closure request sent to System Admin."
                : role === "HR Admin" ? "Closure request sent to CEO for business review."
                    : role === "Team Lead" ? "Closure request sent to your department HR."
                        : "Closure request sent to HR for business review.");
        } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : "The closure request could not be sent."); }
        finally { setClosureBusy(false); }
    };

    const cancelAccountClosure = async (request: AccountClosureRequest) => {
        setClosureBusy(true); setError("");
        try {
            const updated = isBackendConfigured ? await brainServeApi.cancelAccountClosure(request.id)
                : { ...request, status: "CANCELLED" as const, cancelledAt: new Date().toISOString() };
            if (!isBackendConfigured) {
                writeDemoAccountClosures(readDemoAccountClosures().map((item) => item.id === updated.id ? updated : item));
                recordDemoClosureTransition(updated, "ACCOUNT_CLOSURE_CANCELLED", request.status, profile.userId,
                    "Requester cancelled account closure");
            }
            setClosureRequests((items) => items.map((item) => item.id === updated.id ? updated : item));
            setMessage("Account closure request cancelled.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The closure request could not be cancelled."); }
        finally { setClosureBusy(false); }
    };
    return <section className="my-profile-page">
        <PageTitle eyebrow="MY PROFILE" title="Your BrainServe Connect identity"
                   detail="Review your signed-in role, department assignment and employee identity in one private workspace." />
        <article className="profile-hero glass-panel">
            <div className="profile-photo" style={profile.photoUrl ? { backgroundImage: `url(${profile.photoUrl})` } : undefined}>
                {!profile.photoUrl && <span>{visitorInitials(profile.fullName)}</span>}
            </div>
            <div className="profile-identity"><span>ACTIVE STAFF IDENTITY</span><h2>{profile.fullName}</h2><p>{profile.email}</p>
                <div>{roles.map((value) => <b key={value}><ShieldCheck size={13} />{value}</b>)}</div></div>
            <label className={`button button-primary profile-upload${busy ? " disabled" : ""}`}>
                <CircleUserRound size={17} />{busy ? "Uploading…" : "Upload profile picture"}
                <input type="file" accept="image/jpeg,image/png" disabled={busy} onChange={(event) => {
                    void uploadPhoto(event.target.files?.[0]); event.target.value = "";
                }} />
            </label>
        </article>
        <div className="profile-detail-grid">
            <article className="profile-detail-card glass-panel"><Building2 size={20} /><span><small>DEPARTMENT</small><strong>{profile.departmentName ?? "Not assigned"}</strong><p>{profile.departmentCode ? `${profile.departmentCode} · ${profile.departmentActive ? "Active" : "Inactive"}` : "No department is linked to this role."}</p></span></article>
            <article className="profile-detail-card glass-panel"><IdCard size={20} /><span><small>EMPLOYEE ID</small><strong>{profile.employeeNumber ?? "Not applicable"}</strong><p>{profile.employeeId ? "Linked to your verified employee record." : "This operational account has no employee profile."}</p></span></article>
            <article className="profile-detail-card glass-panel"><BriefcaseBusiness size={20} /><span><small>DESIGNATION</small><strong>{profile.designation ?? role}</strong><p>{profile.employeeStatus ? profile.employeeStatus.replaceAll("_", " ").toLowerCase() : "Active account"}</p></span></article>
        </div>
        {canRequestDepartmentChange && <article className="department-change-profile glass-panel">
            <div className="panel-heading"><div><span>ROLE ASSIGNMENT REQUEST</span><h2>Request a department change</h2><p>{role === "HR Admin" ? "CEO reviews HR department ownership." : "The HR Admin assigned to your destination department reviews Team Lead access."} Existing ownership always requires an explicit swap or replacement decision.</p></div><Building2 size={23} /></div>
            {pendingChange ? <div className="pending-department-change"><span className="role-icon"><FileClock size={18} /></span><span><strong>{pendingChange.fromDepartmentName ?? "Unassigned"} <ArrowRight size={14} /> {pendingChange.targetDepartmentName}</strong><small>{pendingChange.reason} · requested {new Date(pendingChange.requestedAt).toLocaleDateString("en-IN")}</small></span><StatusPill status="Pending" /><button className="button button-reject" disabled={changeBusy} onClick={() => void cancelDepartmentChange(pendingChange)}>Cancel request</button></div>
                : <form className="department-change-form" onSubmit={requestDepartmentChange}>
                    <label>New department<select name="targetDepartmentId" value={targetDepartmentId} onChange={(event) => setTargetDepartmentId(event.target.value)} required><option value="">Select an active department</option>{departments.filter((item) => item.active && item.id !== profile.departmentId).map((department) => <option key={department.id} value={department.id}>{department.name} · {department.code}</option>)}</select></label>
                    <label>Reason for change<textarea name="reason" minLength={5} maxLength={500} required placeholder="Explain why this department assignment should change." /></label>
                    {!profile.employeeId && role === "HR Admin" && <div className="department-change-profile-fields"><label>Designation<input name="designation" defaultValue="HR Admin" maxLength={120} required /></label><label>Phone number<input name="phoneNumber" maxLength={30} placeholder="Optional" /></label><label>Joining date<input name="joiningDate" type="date" max={officeToday()} defaultValue={officeToday()} required /></label></div>}
                    {selectedTarget && (targetHrAssignment || targetTeamLeadAssignment) && <div className="role-conflict-warning"><ShieldCheck size={17} /><span><strong>{selectedTarget.name} already has {targetOccupantName ?? (role === "HR Admin" ? "an HR Admin" : "a Team Lead")}.</strong><small>The approver must choose to swap both assignments or replace the current role owner.</small></span></div>}
                    <button className="button button-primary" disabled={changeBusy || !targetDepartmentId}><Send size={16} />{changeBusy ? "Sending…" : "Send approval request"}</button>
                </form>}
            {changeRequests.some((item) => item.status !== "PENDING") && <div className="department-change-history">{changeRequests.filter((item) => item.status !== "PENDING").slice(0, 4).map((item) => <div key={item.id}><span><strong>{item.targetDepartmentName}</strong><small>{item.resolution ? item.resolution.toLowerCase() : item.status.toLowerCase()} · {item.decisionNote ?? item.reason}</small></span><StatusPill status={item.status === "APPROVED" ? "Approved" : item.status === "REJECTED" ? "Rejected" : "Cancelled"} /></div>)}</div>}
        </article>}
        <article className="account-closure-profile glass-panel">
            <div className="panel-heading"><div><span>ACCOUNT LIFECYCLE</span><h2>Deactivate &amp; archive</h2><p>Login is disabled only after the approval route completes. Visits, tasks, messages and audit history remain linked to your original account.</p></div><Archive size={23} /></div>
            {role === "System Admin" ? <div className="protected-account-note"><ShieldCheck size={19} /><span><strong>Permanent protected account</strong><small>The inbuilt System Admin cannot be closed, archived or replaced.</small></span></div>
                : role === "Employee" ? <div className="protected-account-note"><UserCog size={19} /><span><strong>Employee termination governance</strong><small>Employee access is archived only after HR requests termination and CEO gives final approval.</small></span></div>
                    : closureRequests.find((item) => ["REQUESTED", "BUSINESS_APPROVED", "PENDING_SYSTEM_ADMIN", "SCHEDULED"].includes(item.status))
                        ? (() => { const request = closureRequests.find((item) => ["REQUESTED", "BUSINESS_APPROVED", "PENDING_SYSTEM_ADMIN", "SCHEDULED"].includes(item.status))!;
                            return <div className="closure-request-summary"><span className="role-icon"><FileClock size={18} /></span><span><strong>{request.status.replaceAll("_", " ")}</strong><small>Effective {new Date(`${request.requestedEffectiveDate}T00:00:00`).toLocaleDateString("en-IN")} · {request.reason}{request.replacementName ? ` · replacement: ${request.replacementName}` : ""}</small></span><span className={`closure-status closure-${request.status.toLowerCase()}`}>{request.status.replaceAll("_", " ")}</span>{request.status !== "SCHEDULED" && <button className="button button-reject" disabled={closureBusy} onClick={() => void cancelAccountClosure(request)}>Cancel request</button>}</div>; })()
                        : <form className="account-closure-form" onSubmit={requestAccountClosure}>
                            <label>Reason<textarea name="reason" minLength={5} maxLength={1000} required placeholder="Explain why this account should be deactivated and archived." /></label>
                            <label>Effective date<input name="effectiveDate" type="date" min={officeToday()} defaultValue={officeToday()} required /></label>
                            <label>Replacement account<select name="replacementUserId" required={!(["Reception", "Security"].includes(role))}><option value="">{["Reception", "Security"].includes(role) ? "No replacement required" : "Select an active replacement"}</option>{availableClosureCandidates.map((candidate) => <option key={candidate.userId} value={candidate.userId}>{candidate.fullName} · {readableNotificationRole(candidate.role)}</option>)}</select></label>
                            <button className="button button-reject" disabled={closureBusy}><Archive size={16} />{closureBusy ? "Sending…" : "Request account closure"}</button>
                        </form>}
            {closureRequests.filter((item) => !["REQUESTED", "BUSINESS_APPROVED", "PENDING_SYSTEM_ADMIN", "SCHEDULED"].includes(item.status)).slice(0, 3).map((item) => <div className="closure-history-row" key={item.id}><span><strong>{item.status.replaceAll("_", " ")}</strong><small>{item.decisionNote ?? item.reason}</small></span><time>{new Date(item.archivedAt ?? item.cancelledAt ?? item.requestedAt).toLocaleDateString("en-IN")}</time></div>)}
        </article>
        <article className="profile-security-note glass-panel"><Fingerprint size={21} /><span><strong>Private profile storage</strong><small>Profile pictures are virus-scanned and stored privately. Role identity stays locked; HR and Team Lead department changes only take effect after the required approval and an audited assignment decision.</small></span></article>
        {message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}
        {error && <div className="login-error" role="alert">{error}</div>}
    </section>;
}

