"use client";

import { useState } from "react";
import { useSessionRevision } from "../../features/setup-imports/use-operation-scope";
import { UnifiedSearch } from "../../features/search/unified-search";
import type { SearchOpenRecord } from "../../features/search/types";
import { RecordDetailDialog } from "../../features/record-details/record-detail-dialog";
import { WorkBoard, TeamLeadPerformanceView, WorkInsightsView } from "../../features/workboard/routes";
import ConnectionRecovery from "../shared/connection-recovery";
import { AccountLifecycleView } from "../../features/accounts/account-lifecycle-view";
import { AccountProvisioningPanel } from "../../features/accounts/components/account-provisioning-panel";
import { AccountRecoveryApprovalPanel } from "../../features/accounts/components/account-recovery-approval-panel";
import { AppointmentsView } from "../../features/appointments/appointments-view";
import { VisitRegistrationModal } from "../../features/appointments/components/visit-registration-modal";
import { AuditView } from "../../features/audit/audit-view";
import { EssentialLogsView } from "../../features/audit/essential-logs-view";
import { Overview } from "../../features/dashboard/overview";
import { AdministrationDashboard, dashboardCardsEnabled } from "../../features/dashboard/administration-dashboard";
import { EmployeeModal } from "../../features/employees/components/employee-modal";
import { TerminationRequestModal } from "../../features/employees/components/termination-request-modal";
import { EmployeesView } from "../../features/employees/employees-view";
import { TerminationsView } from "../../features/employees/terminations-view";
import { InternalNotificationsView } from "../../features/notifications/internal-notifications-view";
import { OrganizationView } from "../../features/organization/organization-view";
import { PrivacyCentreModal } from "../../features/privacy/privacy-centre-modal";
import { MyProfileView } from "../../features/profile/my-profile-view";
import { ReportsView } from "../../features/reports/reports-view";
import { SettingsView } from "../../features/settings/settings-view";
import { IntegrationsWorkspace } from "../../features/integrations/integrations-workspace";
import { DeviceAdministration } from "../../features/kiosk/device-administration";
import { SupportWorkspace } from "../../features/support/support-workspace";
import { SecurityIntakeModal } from "../../features/visitors/components/security-intake-modal";
import { VisitorsView } from "../../features/visitors/visitors-view";
import { isBackendConfigured } from "../../services/brainserve-api";
import { setNotificationSoundEnabled } from "../../services/notification-sounds";
import { Logo } from "../shared/logo";
import { roleBadge, rolePermissions } from "../../config/roles";
import { useWorkspace } from "../../hooks/workspace/use-workspace";
import { type WorkspaceIdentity } from "../../hooks/workspace/use-workspace-state";
import {
    Bell,
    ChevronRight,
    CircleUserRound,
    LogOut,
    Menu,
    RotateCcw,
    ShieldCheck,
    Volume2,
    VolumeX,
    X,
} from "lucide-react";

export function DashboardApp({ role, userEmail, onLogout }: WorkspaceIdentity) {
    const { workspaceConnectionFailure, workspaceRetrying, setWorkspaceRetrying, setOperationError, setWorkspaceRevision, sidebarOpen, setSidebarOpen, permittedNav, view, setView, pendingAppointmentCount, unreadNotifications, setPrivacyOpen, profileMenuRef, profileMenuOpen, profilePhotoUrl, profileName, setProfileMenuOpen, soundEnabled, loggingOut, signOut, liveState, requestWorkspaceRefresh, lastLiveUpdate, operationError, workspaceRevision, departments, refreshStaffAccounts, approvedRecovery, setApprovedRecovery, appointments, metrics, setVisitModal, decideAppointment, currentEmployee, setSecurityIntakeAppointment, decideReceptionVisit, forwardReceptionVisit, employees, staffAccounts, teamLeadAssignments, managerAssignments, unassignedEmployeeAccounts, setEmployeeAccountId, setEmployeeDepartmentId, setEmployeeModal, changeEmployeeLifecycle, setEmployees, accessRecords, checkInAppointment, checkInByReference, checkInByPass, checkOutAppointment, setUnreadNotifications, departmentSummaries, departmentHrAssignments, createDepartment, toggleDepartment, assignTeamLead, endTeamLeadAssignment, assignDepartmentHr, endDepartmentHr, joinExecutiveDepartment, handleProfileUpdated, refreshRoleAssignments, createStaffAccount, changeStaffEmail, resetStaffPassword, setStaffEnabled, updateStaffPermissions, employeeModal, employeeAccountId, employeeDepartmentId, selectedEmployeeAccount, addEmployee, terminationEmployee, setTerminationEmployee, visitModal, appointmentHosts, registerVisit, securityIntakeAppointment, recordSecurityIntake, privacyOpen } = useWorkspace({ role, userEmail, onLogout });

    const sessionRevision = useSessionRevision();
    const identityKey = `${role}:${userEmail}:${sessionRevision}`;
    const [openedRecord, setOpenedRecord] = useState<{ identityKey: string; record: SearchOpenRecord } | null>(null);
    const [openedTask, setOpenedTask] = useState<{ identityKey: string; id: string; requestKey: string } | null>(null);

    if (workspaceConnectionFailure) {
        return <ConnectionRecovery
            mode="unavailable"
            title="Reconnecting to your workspace"
            busy={workspaceRetrying}
            onRetry={() => {
                setWorkspaceRetrying(true);
                setOperationError("");
                setWorkspaceRevision((revision) => revision + 1);
            }}
        />;
    }

    return <main className="app-shell">
        <a className="skip-link" href="#workspace-main">Skip to workspace content</a>
        {sidebarOpen && <button type="button" className="sidebar-scrim" aria-label="Close navigation" onClick={() => setSidebarOpen(false)} />}
        <aside className={sidebarOpen ? "sidebar open" : "sidebar"} aria-label="Workspace navigation">
            <div className="sidebar-top"><Logo /><button type="button" className="icon-button sidebar-close" aria-label="Close navigation" onClick={() => setSidebarOpen(false)}><X size={19} /></button></div>
            <div className="workspace-label">WORKSPACE</div>
            <nav aria-label="Role workspace">{permittedNav.map((item) => {
                const label = item.id === "insights"
                    ? role === "CEO" ? "Work approvals" : role === "Manager" ? "Work oversight" : item.label
                    : item.label;
                return <button type="button" key={item.id} className={view === item.id ? "active" : ""} aria-current={view === item.id ? "page" : undefined} onClick={() => { setView(item.id); setSidebarOpen(false); }}><item.icon size={19} /><span>{label}</span>{(item.id === "appointments" || (item.id === "work" && role === "Team Lead")) && pendingAppointmentCount > 0 && <b>{pendingAppointmentCount}</b>}{item.id === "notifications" && unreadNotifications > 0 && <b>{unreadNotifications}</b>}</button>;
            })}</nav>
            <div className="sidebar-bottom"><button onClick={() => setPrivacyOpen(true)}><ShieldCheck size={19} /><span><strong>Privacy centre</strong><small>Policies & consent</small></span></button><div className="profile-menu-wrap" ref={profileMenuRef}>{profileMenuOpen && <div className="profile-popover" role="menu" aria-label="Profile menu"><div className="profile-popover-identity"><span className={`avatar account-avatar${profilePhotoUrl ? " has-photo" : ""}`} style={profilePhotoUrl ? { backgroundImage: `url(${profilePhotoUrl})` } : undefined}>{!profilePhotoUrl && roleBadge(role)}</span><span><strong>{profileName}</strong><small>{userEmail}</small><em>{role}</em></span></div><button type="button" role="menuitem" onClick={() => { setView("profile"); setProfileMenuOpen(false); setSidebarOpen(false); }}><CircleUserRound size={17} /><span><strong>My profile</strong><small>View account details</small></span><ChevronRight size={16} /></button><button type="button" role="menuitemcheckbox" aria-checked={soundEnabled} onClick={() => setNotificationSoundEnabled(!soundEnabled)}>{soundEnabled ? <Volume2 size={17} /> : <VolumeX size={17} />}<span><strong>Notification sounds</strong><small>{soundEnabled ? "On · Play alerts for new activity" : "Off · Alerts stay silent"}</small></span><em className={`sound-toggle ${soundEnabled ? "on" : ""}`} aria-hidden="true"><i /></em></button><button type="button" role="menuitem" className="profile-logout" disabled={loggingOut} onClick={() => void signOut()}><LogOut size={17} /><span><strong>{loggingOut ? "Signing out…" : "Logout"}</strong><small>End this secure session</small></span></button></div>}<button type="button" className="user-block" aria-haspopup="menu" aria-expanded={profileMenuOpen} onClick={() => setProfileMenuOpen((open) => !open)}><span className={`avatar account-avatar${profilePhotoUrl ? " has-photo" : ""}`} style={profilePhotoUrl ? { backgroundImage: `url(${profilePhotoUrl})` } : undefined}>{!profilePhotoUrl && roleBadge(role)}</span><span><strong>{profileName}</strong><small>{role} · {userEmail}</small></span><ChevronRight className="profile-menu-chevron" size={17} /></button></div></div>
        </aside>

        <section className="app-main" id="workspace-main" tabIndex={-1}>
            <header className="app-header"><div className="global-search"><button type="button" className="icon-button menu-button" aria-label="Open navigation" onClick={() => setSidebarOpen(true)}><Menu size={20} /></button><UnifiedSearch key={identityKey} identityKey={identityKey} disabled={!isBackendConfigured} onOpen={(record) => { if (record.type === "worksheets" && record.route === "work" && rolePermissions[role].includes("work")) { setOpenedTask({ identityKey, id: record.id, requestKey: crypto.randomUUID() }); setView("work"); } else { setOpenedRecord({ identityKey, record }); } }} /></div><div className="header-actions"><button type="button" className={`live-status ${isBackendConfigured ? `live-${liveState}` : "live-preview"}`} onClick={requestWorkspaceRefresh} title={lastLiveUpdate ? `Last synchronized ${lastLiveUpdate.toLocaleTimeString("en-IN")}` : "Refresh BrainServe Connect data"}><span />{isBackendConfigured ? liveState === "live" ? "Live" : liveState === "connecting" ? "Connecting" : liveState === "offline" ? "Offline" : "Reconnecting" : "Preview"}<RotateCcw size={13} /></button>{rolePermissions[role].includes("notifications") && <button type="button" className="icon-button notification-button" onClick={() => setView("notifications")} aria-label={`Open notifications${unreadNotifications ? `, ${unreadNotifications} unread` : ""}`}><Bell size={19} />{unreadNotifications > 0 && <span />}</button>}</div></header>
            <div className="app-content">
                {operationError && <div className="login-error workspace-error" role="alert">{operationError}</div>}
                {view === "overview" && (
                    role === "System Admin" ? (
                        <>
                            {isBackendConfigured && dashboardCardsEnabled && <AdministrationDashboard
                                role={role}
                                userEmail={userEmail}
                                refreshKey={workspaceRevision}
                                onNavigate={setView}
                            />}
                            <AccountProvisioningPanel
                                key={`overview:${workspaceRevision}`}
                                role={role}
                                departments={departments}
                                onDecision={refreshStaffAccounts}
                            />
                            <AccountRecoveryApprovalPanel
                                generated={approvedRecovery}
                                onGeneratedChange={setApprovedRecovery}
                            />
                        </>
                    ) : ["CEO", "HR Admin"].includes(role) ? (
                        <>
                            {role === "CEO" && isBackendConfigured && dashboardCardsEnabled && <AdministrationDashboard
                                role={role}
                                userEmail={userEmail}
                                refreshKey={workspaceRevision}
                                onNavigate={setView}
                                legacyMetrics={metrics}
                            />}
                            <Overview
                                key={`overview:${workspaceRevision}:operations`}
                                role={role}
                                hideSummary={role === "CEO" && isBackendConfigured && dashboardCardsEnabled}
                                appointments={appointments}
                                metrics={metrics}
                                onNavigate={setView}
                                onRegister={() => setVisitModal(true)}
                                decideAppointment={decideAppointment}
                            />
                            <AccountProvisioningPanel
                                key={`overview:${workspaceRevision}:accounts`}
                                compact
                                role={role}
                                departments={departments}
                                onDecision={refreshStaffAccounts}
                            />
                        </>
                    ) : (
                        <Overview
                            key={`overview:${workspaceRevision}`}
                            role={role}
                            appointments={appointments}
                            metrics={metrics}
                            onNavigate={setView}
                            onRegister={() => setVisitModal(true)}
                            decideAppointment={decideAppointment}
                        />
                    )
                )}
                {view === "appointments" && <AppointmentsView key={`appointments:${identityKey}:${workspaceRevision}`} role={role} appointments={appointments} currentEmployee={currentEmployee}
                                                              onCreate={() => setVisitModal(true)} decideAppointment={decideAppointment}
                                                              onSecurityIntake={setSecurityIntakeAppointment} decideReceptionVisit={decideReceptionVisit}
                                                              forwardReceptionVisit={forwardReceptionVisit} />}
                {view === "work" && <WorkBoard key={openedTask?.identityKey === identityKey ? `${openedTask.id}:${openedTask.requestKey}` : identityKey} initialTaskId={openedTask?.identityKey === identityKey ? openedTask.id : undefined} role={role} refreshKey={workspaceRevision} userEmail={userEmail} employees={employees}
                                               staffAccounts={staffAccounts}
                                               departments={departments} teamLeadAssignments={teamLeadAssignments}
                                               appointments={appointments} decideAppointment={decideAppointment}
                                               onNavigate={setView} />}
                {view === "performance" && <TeamLeadPerformanceView key={`performance:${workspaceRevision}`} departments={departments}
                                                                    employees={employees} staffAccounts={staffAccounts} />}
                {view === "insights" && <WorkInsightsView key={`insights:${workspaceRevision}`} role={role} userEmail={userEmail} departments={departments}
                                                          employees={employees} staffAccounts={staffAccounts}
                                                          managerAssignments={managerAssignments} />}
                {view === "employees" && <EmployeesView role={role} userEmail={userEmail} refreshKey={workspaceRevision} employees={employees}
                                                        departments={departments}
                                                        staffAccounts={staffAccounts}
                                                        currentEmployee={currentEmployee}
                                                        unassignedAccounts={unassignedEmployeeAccounts}
                                                        onAssignDepartment={(account) => { setOperationError(""); setEmployeeAccountId(account.userId); setEmployeeDepartmentId(undefined); setEmployeeModal(true); }}
                                                        onAdd={() => { setOperationError(""); setEmployeeAccountId(undefined); setEmployeeDepartmentId(undefined); setEmployeeModal(true); }} onStatus={changeEmployeeLifecycle} />}
                {view === "terminations" && <TerminationsView key={`terminations:${workspaceRevision}`} role={role} userEmail={userEmail}
                                                              onEmployeeTerminated={(employeeId) => {
                                                                  setEmployees((items) => items.map((item) => (item.uuid ?? item.id) === employeeId ? { ...item, status: "Terminated" } : item));
                                                                  setWorkspaceRevision((revision) => revision + 1);
                                                              }} />}
                {view === "account-lifecycle" && <AccountLifecycleView key={`account-lifecycle:${workspaceRevision}`} role={role}
                                                                       userEmail={userEmail} staffAccounts={staffAccounts} departments={departments} employees={employees} />}
                {view === "visitors" && <VisitorsView key={`visitors:${workspaceRevision}`} role={role} userEmail={userEmail} appointments={appointments} accessRecords={accessRecords}
                                                      onCheckIn={checkInAppointment} onReferenceCheckIn={checkInByReference}
                                                      onPassCheckIn={checkInByPass} onCheckOut={checkOutAppointment}
                                                      decideReceptionVisit={decideReceptionVisit} onRegister={() => setVisitModal(true)} />}
                {view === "notifications" && <InternalNotificationsView role={role} userEmail={userEmail}
                                                                        onUnreadChange={setUnreadNotifications} />}
                {view === "organization" && <OrganizationView key={`organization:${workspaceRevision}`} role={role} userEmail={userEmail} departments={departments} employees={employees}
                                                              staffAccounts={staffAccounts}
                                                              summaries={departmentSummaries} teamLeadAssignments={teamLeadAssignments}
                                                              departmentHrAssignments={departmentHrAssignments} managerAssignments={managerAssignments}
                                                              onCreate={createDepartment} onToggle={toggleDepartment} onAssignTeamLead={assignTeamLead}
                                                              onEndTeamLead={endTeamLeadAssignment}
                                                              onAssignDepartmentHr={assignDepartmentHr} onEndDepartmentHr={endDepartmentHr}
                                                              onJoinExecutiveDepartment={joinExecutiveDepartment}
                                                              onAddEmployee={(departmentId) => { setOperationError(""); setEmployeeAccountId(undefined); setEmployeeDepartmentId(departmentId); setEmployeeModal(true); }} />}
                {view === "reports" && <ReportsView role={role} metrics={metrics} appointments={appointments}
                                                    accessRecords={accessRecords} refreshKey={workspaceRevision} onRefresh={requestWorkspaceRefresh} />}
                {view === "audit" && <AuditView key={`audit:${workspaceRevision}`} />}
                {view === "logs" && <EssentialLogsView key={`logs:${workspaceRevision}`} />}
                {view === "integrations" && role === "System Admin" && <IntegrationsWorkspace key={`${role}:${userEmail}`} />}
                {view === "kiosk-devices" && role === "System Admin" && <DeviceAdministration key={`${role}:${userEmail}`} />}
                {view === "support" && role === "System Admin" && <SupportWorkspace key={`${role}:${userEmail}`} />}
                {view === "profile" && <MyProfileView key={`profile:${workspaceRevision}`} role={role} userEmail={userEmail}
                                                      departments={departments} employees={employees} staffAccounts={staffAccounts}
                                                      onProfileUpdated={handleProfileUpdated} />}
                {view === "settings" && <SettingsView key={`settings:${workspaceRevision}`} role={role} userEmail={userEmail} accounts={staffAccounts}
                                                      departments={departments} employees={employees} teamLeadAssignments={teamLeadAssignments}
                                                      departmentHrAssignments={departmentHrAssignments} managerAssignments={managerAssignments}
                                                      approvedRecovery={approvedRecovery} onApprovedRecoveryChange={setApprovedRecovery}
                                                      onRoleAssignmentChanged={refreshRoleAssignments}
                                                      onAddEmployee={() => { setOperationError(""); setEmployeeAccountId(undefined); setEmployeeDepartmentId(undefined); setEmployeeModal(true); }}
                                                      onCreate={createStaffAccount} onChangeEmail={changeStaffEmail} onResetPassword={resetStaffPassword}
                                                      onSetEnabled={setStaffEnabled} onUpdatePermissions={updateStaffPermissions} />}
            </div>
        </section>
        {employeeModal && <EmployeeModal key={`${employeeAccountId ?? "new"}:${employeeDepartmentId ?? "employee"}`} departments={departments} employees={employees}
                                         teamLeadAssignments={teamLeadAssignments}
                                         account={selectedEmployeeAccount} initialDepartmentId={employeeDepartmentId} error={operationError}
                                         onClose={() => { setOperationError(""); setEmployeeModal(false); setEmployeeDepartmentId(undefined); setEmployeeAccountId(undefined); }} onSubmit={addEmployee} />}
        {terminationEmployee && <TerminationRequestModal employee={terminationEmployee} userEmail={userEmail}
                                                         onClose={() => setTerminationEmployee(null)} onSubmitted={() => {
            setTerminationEmployee(null); setView("terminations"); setWorkspaceRevision((revision) => revision + 1);
        }} />}
        {visitModal && <VisitRegistrationModal key={identityKey} employees={appointmentHosts.length ? appointmentHosts : employees}
                                               departments={departments} securityMode={role === "Security"} accountScope={identityKey}
                                               onDraftSubmitted={() => { setVisitModal(false); requestWorkspaceRefresh(); }} onClose={() => setVisitModal(false)} onSubmit={registerVisit} />}
        {securityIntakeAppointment && <SecurityIntakeModal appointment={securityIntakeAppointment}
                                                           onClose={() => setSecurityIntakeAppointment(null)} onSubmit={recordSecurityIntake} />}
        {openedRecord?.identityKey === identityKey && <RecordDetailDialog key={`${identityKey}:${openedRecord.record.type}:${openedRecord.record.id}`} record={openedRecord.record} onClose={() => { setOpenedRecord(null); requestAnimationFrame(() => document.querySelector<HTMLInputElement>('[aria-label="Workspace search"]')?.focus()); }} />}
        {privacyOpen && <PrivacyCentreModal onClose={() => setPrivacyOpen(false)} />}
    </main>;
}
