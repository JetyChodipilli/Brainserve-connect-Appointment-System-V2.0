"use client";

import {
    type AccountRecoveryRequest,
    ApiError,
    brainServeApi,
    type DepartmentHrAssignment,
    isBackendConfigured,
    type ManagerAssignment,
    type RoleDefinition,
    type StaffAccount,
    type TeamLeadAssignment,
    type WorkspaceSetting,
} from "../../services/brainserve-api";
import { PageTitle } from "../../components/ui/page-title";
import { CompanySetup } from "../setup-imports/company-setup";
import { BulkImports } from "../setup-imports/bulk-imports";
import { type Department, type Employee, type Role, type SettingsSection } from "../../types/workspace";
import { fail } from "../../utils/errors";
import { AccountRecoveryApprovalPanel } from "../accounts/components/account-recovery-approval-panel";
import { HrLifecyclePanel } from "../accounts/components/hr-lifecycle-panel";
import { PasswordChangeCard } from "../auth/components/password-change-card";
import { AccountPermissionEditor } from "./components/account-permission-editor";
import { DataGovernancePanel } from "./components/data-governance-panel";
import { IntegrationStatusPanel } from "./components/integration-status-panel";
import { ManagerDepartmentAssignmentPanel } from "./components/manager-department-assignment-panel";
import { OperationalRoleTransitionPanel } from "./components/operational-role-transition-panel";
import { RoleAssignmentChangePanel } from "./components/role-assignment-change-panel";
import { SettingControl } from "./components/setting-control";
import { StaffAccountRow } from "./components/staff-account-row";
import { fallbackRoles, fallbackSettings } from "./defaults";
import {
    ArrowLeft,
    ArrowRight,
    BadgeCheck,
    Bell,
    Building2,
    CalendarDays,
    CheckCircle2,
    ChevronRight,
    Fingerprint,
    LockKeyhole,
    MessageSquare,
    Search,
    ShieldCheck,
    UserCog,
    UserPlus,
    ListChecks,
    Upload,
} from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function SettingsView({ role, userEmail, accounts, departments, employees, teamLeadAssignments, onAddEmployee,
                          departmentHrAssignments, managerAssignments, approvedRecovery, onApprovedRecoveryChange,
                          onRoleAssignmentChanged,
                          onCreate, onChangeEmail, onResetPassword, onSetEnabled, onUpdatePermissions }: {
    role: Role; userEmail: string; accounts: StaffAccount[]; departments: Department[]; employees: Employee[];
    teamLeadAssignments: TeamLeadAssignment[]; departmentHrAssignments: DepartmentHrAssignment[];
    managerAssignments: ManagerAssignment[];
    approvedRecovery: AccountRecoveryRequest | null;
    onApprovedRecoveryChange: (request: AccountRecoveryRequest | null) => void;
    onRoleAssignmentChanged: () => Promise<void>;
    onAddEmployee: () => void;
    onCreate: (email: string, password: string, role: string) => Promise<void>;
    onChangeEmail: (userId: string, email: string) => Promise<void>;
    onResetPassword: (userId: string, password: string) => Promise<void>;
    onSetEnabled: (userId: string, enabled: boolean) => Promise<void>;
    onUpdatePermissions: (userId: string, grants: string[], denies: string[]) => Promise<void>;
}) {
    const [section, setSection] = useState<SettingsSection>("identity");
    const [settings, setSettings] = useState<WorkspaceSetting[]>(() => isBackendConfigured ? [] : fallbackSettings);
    const [roles, setRoles] = useState<RoleDefinition[]>(() => isBackendConfigured ? [] : fallbackRoles);
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");
    const [busyKey, setBusyKey] = useState("");
    const [managedAccounts, setManagedAccounts] = useState<StaffAccount[]>(accounts);
    const [managedAccountPage, setManagedAccountPage] = useState(0);
    const [managedAccountTotalPages, setManagedAccountTotalPages] = useState(1);
    const [managedAccountTotal, setManagedAccountTotal] = useState(accounts.length);
    const [managedAccountQuery, setManagedAccountQuery] = useState("");
    const [managedAccountsLoading, setManagedAccountsLoading] = useState(false);
    const allowedRoles = [["ROLE_RECEPTIONIST", "Receptionist"], ["ROLE_SECURITY", "Security"]];
    const nav: Array<[SettingsSection, typeof Building2, string]> = [
        ...(role === "System Admin" ? [["setup", ListChecks, "Company setup"] as [SettingsSection, typeof Building2, string]] : []),
        ["company", Building2, "Company profile"], ["identity", Fingerprint, "Identity & access"],
        ["roles", UserCog, "Roles & responsibilities"], ["policy", CalendarDays, "Appointment policy"],
        ["notifications", Bell, "Notifications"], ["privacy", ShieldCheck, "Privacy & retention"],
        ["imports", Upload, "Safe CSV imports"],
    ];

    useEffect(() => {
        if (!isBackendConfigured) return;
        let active = true;
        const loadSettings = role === "System Admin"
            ? brainServeApi.systemSettings()
            : brainServeApi.workspaceSettings();
        loadSettings.then((items) => { if (active) setSettings(items); })
            .catch((reason) => { if (active) setError(reason instanceof ApiError ? reason.message : "Workspace settings could not be loaded."); });
        if (["System Admin", "HR Admin"].includes(role)) {
            brainServeApi.roleDefinitions().then((items) => { if (active) setRoles(items); })
                .catch((reason) => { if (active) setError(reason instanceof ApiError ? reason.message : "Role definitions could not be loaded."); });
        }
        return () => { active = false; };
    }, [role]);

    const canEdit = (key: string) => role === "System Admin"
        || (role === "CEO" && ["COMPANY.", "APPOINTMENT.", "APPROVAL.", "NOTIFICATION.", "PRIVACY.", "VISITOR.RETENTION"].some((prefix) => key.startsWith(prefix)))
        || (role === "HR Admin" && ["APPOINTMENT.", "APPROVAL.", "NOTIFICATION.", "PRIVACY.", "VISITOR.RETENTION"].some((prefix) => key.startsWith(prefix)));

    const loadManagedAccounts = useCallback(async (pageNumber = 0, query = "") => {
        if (role !== "HR Admin") return;
        if (!isBackendConfigured) {
            const matching = accounts.filter((account) => !query
                || `${account.fullName} ${account.email}`.toLowerCase().includes(query.toLowerCase()));
            setManagedAccounts(matching.slice(pageNumber * 25, (pageNumber + 1) * 25));
            setManagedAccountPage(pageNumber); setManagedAccountTotal(matching.length);
            setManagedAccountTotalPages(Math.max(1, Math.ceil(matching.length / 25)));
            return;
        }
        setManagedAccountsLoading(true);
        try {
            const page = await brainServeApi.staffAccountPage({ query, page: pageNumber, size: 25 });
            setManagedAccounts(page.content); setManagedAccountPage(page.number ?? pageNumber);
            setManagedAccountTotal(page.totalElements ?? page.content.length);
            setManagedAccountTotalPages(page.totalPages ?? 1);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "Managed staff accounts could not be loaded.");
        } finally { setManagedAccountsLoading(false); }
    }, [accounts, role]);

    useEffect(() => {
        if (role !== "HR Admin") return;
        const timer = window.setTimeout(() => void loadManagedAccounts(0, ""), 0);
        return () => window.clearTimeout(timer);
    }, [loadManagedAccounts, role]);

    const updateSetting = async (key: string, value: string) => {
        setBusyKey(key); setError(""); setMessage("");
        try {
            const updated = isBackendConfigured
                ? role === "System Admin"
                    ? await brainServeApi.updateSystemSetting(key, value)
                    : await brainServeApi.updateWorkspaceSetting(key, value)
                : { ...(settings.find((item) => item.key === key) as WorkspaceSetting), value };
            setSettings((items) => items.map((item) => item.key === key ? updated : item));
            setMessage("Workspace setting saved and recorded in the audit trail.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The setting could not be saved."); }
        finally { setBusyKey(""); }
    };

    const  create = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setError(""); setMessage("");
        const form = event.currentTarget; const data = new FormData(form);
        try {
            await onCreate(String(data.get("email")), String(data.get("password")), String(data.get("role")));
            form.reset(); setMessage("Access-only login created and sent to the HR Admin approval queue.");
        } catch (reason) { setError(reason instanceof ApiError ? reason.message : "The staff account could not be created."); }
    };

    const changeOwnEmail = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setError(""); setMessage("");
        const form = event.currentTarget; const data = new FormData(form);
        try {
            if (!isBackendConfigured) fail("Connect the Spring backend to change the stored login email.");
            await brainServeApi.changeMyEmail(String(data.get("currentPassword")), String(data.get("newEmail")));
            setMessage("Your company email was updated. Sign in again with the new email."); form.reset();
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Your email could not be updated."); }
    };

    const settingPanel = (title: string, eyebrow: string, detail: string, keys: string[]) => <article className="panel glass-panel"><div className="panel-heading"><div><span>{eyebrow}</span><h2>{title}</h2><p>{detail}</p></div></div><div className="setting-list">{keys.map((key) => { const item = settings.find((value) => value.key === key); return item ? <SettingControl key={`${key}:${item.version}:${item.value}`} setting={item} disabled={!canEdit(key) || busyKey === key} onSave={updateSetting} /> : null; })}</div></article>;

    return <><PageTitle eyebrow="WORKSPACE ADMINISTRATION" title="BrainServe Connect controls" detail="Company identity, role-scoped access, appointment rules, notifications and privacy settings backed by the Spring service." />
        {role === "System Admin" && <IntegrationStatusPanel />}
        <div className="settings-grid"><article className="settings-nav glass-panel">{nav.map(([id, Icon, label]) => <button type="button" className={section === id ? "active" : ""} key={id} onClick={() => { setSection(id); setError(""); setMessage(""); }}><Icon size={18} />{label}<ChevronRight size={16} /></button>)}</article><div className="identity-settings">
            {section === "setup" && <CompanySetup role={role} userEmail={userEmail} onConfigure={setSection} />}
            {section === "imports" && <BulkImports accountScope={`${role}:${userEmail}`} />}
            {section === "company" && settingPanel("Company profile", "ORGANIZATION IDENTITY", "These values drive the public visitor experience and official support details.", ["COMPANY.NAME", "COMPANY.EMAIL_DOMAIN", "COMPANY.HQ_ADDRESS", "COMPANY.SUPPORT_EMAIL"])}
            {section === "identity" && <>
                <article className="panel glass-panel"><div className="panel-heading"><div><span>YOUR STAFF IDENTITY</span><h2>Company login</h2><p>Current login: <strong>{userEmail}</strong>. Your authenticated role is locked to <strong>{role}</strong>.</p></div><LockKeyhole size={22} /></div>{role !== "System Admin" && <form className="inline-account-form" onSubmit={changeOwnEmail}><label>New company email<input name="newEmail" type="email" placeholder="name@brainserve.in" required /></label><label>Current password<input name="currentPassword" type="password" minLength={8} required /></label><button className="button button-secondary"><Fingerprint size={16} /> Update my email</button></form>}</article>
                <PasswordChangeCard />
                {role === "System Admin" && <AccountRecoveryApprovalPanel
                    generated={approvedRecovery} onGeneratedChange={onApprovedRecoveryChange} />}
                {role === "HR Admin" ? <>
                    <article className="panel glass-panel"><div className="panel-heading"><div><span>CREATE EMPLOYEE</span><h2>Employee profile and department</h2><p>Create the employee profile, assign the HR Admin&apos;s department and generate the employee ID in one flow.</p></div><Building2 size={22} /></div><div className="team-lead-access-note"><BadgeCheck size={17} /><span><strong>Department assignment is mandatory</strong><small>The employee form includes department, designation and joining date. If HR manages one department, it is selected automatically.</small></span></div><button type="button" className="button button-primary" onClick={onAddEmployee} disabled={!departments.some((item) => item.active)}><UserPlus size={16} /> Add employee &amp; assign department</button>{!departments.some((item) => item.active) && <div className="login-error" role="alert">No active department is available. Ask the CEO or System Admin to assign this HR Admin to a department.</div>}</article>
                    <article className="panel glass-panel"><div className="panel-heading"><div><span>CREATE ACCESS-ONLY LOGIN</span><h2>Receptionist or Security</h2><p>These roles do not belong to an employee department. Each login remains pending until an HR Admin approves it.</p></div></div><form className="staff-create-form" onSubmit={create}><label>Company email<input name="email" type="email" placeholder="name@brainserve.in" required /></label><label>Temporary password<input name="password" type="password" minLength={12} placeholder="Minimum 12 characters" required /></label><label>Role<select name="role">{allowedRoles.map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></label><button className="button button-primary"><UserPlus size={16} /> Create login</button></form></article>
                    <article className="panel glass-panel"><div className="panel-heading"><div><span>MANAGED ACCOUNTS</span><h2>HR staff directory</h2><p>Search and manage one bounded department page at a time. Pending accounts must still use the HR approval action.</p></div><b>{managedAccountTotal.toLocaleString("en-IN")}</b></div>
                        <form className="inline-account-form" onSubmit={(event) => { event.preventDefault(); void loadManagedAccounts(0, managedAccountQuery); }}>
                            <label>Find account<input value={managedAccountQuery} onChange={(event) => setManagedAccountQuery(event.target.value)} placeholder="Name or company email" /></label>
                            <button className="button button-secondary" disabled={managedAccountsLoading}><Search size={16} />{managedAccountsLoading ? "Searching…" : "Search"}</button>
                        </form>
                        <div className="staff-account-list">{managedAccounts.map((account) => <StaffAccountRow key={account.userId} account={account}
                                                                                                               onChangeEmail={async (userId, email) => { await onChangeEmail(userId, email); await loadManagedAccounts(managedAccountPage, managedAccountQuery); }}
                                                                                                               onResetPassword={async (userId, password) => { await onResetPassword(userId, password); await loadManagedAccounts(managedAccountPage, managedAccountQuery); }}
                                                                                                               onSetEnabled={async (userId, enabled) => { await onSetEnabled(userId, enabled); await loadManagedAccounts(managedAccountPage, managedAccountQuery); }} />)}
                            {managedAccounts.length === 0 && <div className="empty-state"><UserCog size={28} /><strong>No managed accounts found</strong><small>Change the search or create the first staff login above.</small></div>}</div>
                        {managedAccountTotalPages > 1 && <div className="table-pagination"><button type="button" className="button button-secondary"
                                                                                                   disabled={managedAccountPage === 0 || managedAccountsLoading}
                                                                                                   onClick={() => void loadManagedAccounts(managedAccountPage - 1, managedAccountQuery)}><ArrowLeft size={15} /> Previous</button>
                            <span>Page {managedAccountPage + 1} of {managedAccountTotalPages}</span><button type="button" className="button button-secondary"
                                                                                                            disabled={managedAccountPage + 1 >= managedAccountTotalPages || managedAccountsLoading}
                                                                                                            onClick={() => void loadManagedAccounts(managedAccountPage + 1, managedAccountQuery)}>Next <ArrowRight size={15} /></button></div>}
                    </article>
                </> : <><article className="panel glass-panel scope-card"><div className="panel-heading"><div><span>APPROVAL SCOPE</span><h2>{role === "CEO" ? "CEO approves every HR Admin and Manager request" : "System Admin creates and approves only the single CEO"}</h2><p>{role === "CEO" ? "Your department is a work assignment only; CEO governance remains company-wide." : "HR Admin and Manager activation is routed to the CEO. Employee, Receptionist and Security accounts remain controlled by their HR Admin."}</p></div><ShieldCheck size={22} /></div></article><HrLifecyclePanel /></>}
            </>}
            {section === "roles" && <>{(["CEO", "HR Admin"] as Role[]).includes(role) && <RoleAssignmentChangePanel
                role={role as "CEO" | "HR Admin"} userEmail={userEmail} accounts={accounts} departments={departments} employees={employees}
                teamLeadAssignments={teamLeadAssignments} departmentHrAssignments={departmentHrAssignments}
                onChanged={onRoleAssignmentChanged} />}
                <article className="panel glass-panel permission-panel"><div className="panel-heading"><div><span>ROLE DEFINITIONS</span><h2>Eight locked BrainServe Connect roles</h2><p>Roles remain locked after login. Department ownership changes through the approval ledger above; default permissions stay enforced at every backend endpoint.</p></div><b>{roles.length}</b></div><div className="role-definition-list">{roles.map((definition) => <div key={definition.role}><span className="role-icon"><UserCog size={18} /></span><span><strong>{definition.role.replace("ROLE_", "").replaceAll("_", " ")}</strong><small>{definition.defaultPermissions.length} default permissions</small></span><div>{definition.defaultPermissions.slice(0, 6).map((permission) => <code key={permission}>{permission.replaceAll("_", " ")}</code>)}{definition.defaultPermissions.length > 6 && <code>+{definition.defaultPermissions.length - 6} more</code>}</div></div>)}</div></article>{role === "HR Admin" && <AccountPermissionEditor accounts={managedAccounts} roles={roles} onUpdate={onUpdatePermissions} />}</>}
            {section === "roles" && ["CEO", "System Admin"].includes(role) && <OperationalRoleTransitionPanel
                actorRole={role as "CEO" | "System Admin"} departments={departments}
                managerAssignments={managerAssignments} onChanged={onRoleAssignmentChanged} />}
            {section === "roles" && ["CEO", "System Admin"].includes(role) && <ManagerDepartmentAssignmentPanel
                departments={departments} assignments={managerAssignments} onChanged={onRoleAssignmentChanged} />}
            {section === "policy" && settingPanel("Appointment and QR pass policy", "VISITOR WORKFLOW", "Booking duration, same-day lead time, advance window and signed pass validity are enforced by backend services.", ["APPOINTMENT.SLOT_MINUTES", "APPOINTMENT.MAX_ADVANCE_DAYS", "APPOINTMENT.MIN_LEAD_MINUTES", "APPOINTMENT.CHECK_IN_EARLY_MINUTES", "APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END"])}
            {section === "notifications" && <>{settingPanel("Notification delivery", "EMAIL & ALERTS", "Control transactional booking, approval and security alert email.", ["NOTIFICATION.APPOINTMENT_EMAIL_ENABLED", "NOTIFICATION.APPROVAL_EMAIL_ENABLED", "NOTIFICATION.SECURITY_ALERT_EMAIL_ENABLED"])}<article className="panel glass-panel"><div className="panel-heading"><div><span>BRAINSERVE CONNECT INTERNAL CALLS</span><h2>Prioritized, role-controlled conversations</h2><p>Unread and urgent requests rise first. Department-bound routes are restricted to the sender’s assigned department.</p></div><MessageSquare size={22} /></div><div className="notification-route-grid"><span><strong>CEO</strong><ChevronRight size={14} />Manager · HR Admin · Team Lead · Receptionist</span><span><strong>Manager</strong><ChevronRight size={14} />CEO · same-department HR · Receptionist</span><span><strong>HR Admin</strong><ChevronRight size={14} />CEO · same-department Team Lead and Employee · Receptionist</span><span><strong>Team Lead</strong><ChevronRight size={14} />same-department HR · Receptionist</span><span><strong>Employee</strong><ChevronRight size={14} />same-department HR</span><span><strong>Receptionist</strong><ChevronRight size={14} />CEO · Manager · HR Admin · Team Lead</span></div></article></>}
            {section === "privacy" && <>{settingPanel("Privacy and consent", "DATA GOVERNANCE", "Apply the consent version used across the service. Dataset retention is managed in the governed lifecycle below.", ["PRIVACY.CONSENT_VERSION"])}{role === "System Admin" && <DataGovernancePanel />}</>}
            {message && <div className="success-banner"><CheckCircle2 size={17} /> {message}</div>}{error && <div className="login-error" role="alert">{error}</div>}
        </div></div></>;
}
