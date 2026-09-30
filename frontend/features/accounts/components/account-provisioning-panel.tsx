"use client";

import {
    ApiError,
    brainServeApi,
    type DepartmentHrAssignment,
    type HrAccountApprovalInput,
    isBackendConfigured,
    type ManagerAssignment,
    type ProvisioningAccount,
} from "../../../services/brainserve-api";
import { officeToday } from "../../../lib/appointments";
import { hashDemoPassword, newDemoTemporaryPassword, readDemoAccounts, writeDemoAccounts } from "../../../preview/accounts";
import {
    readDemoDepartmentHrAssignments,
    readDemoEmployees,
    writeDemoDepartmentHrAssignments,
    writeDemoEmployees,
} from "../../../preview/directory";
import { DEMO_SYSTEM_ADMIN } from "../../../preview/fixtures/accounts";
import { readDemoManagerAssignments, writeDemoManagerAssignments } from "../../../preview/manager-assignments";
import { type DemoProvisioningAccount } from "../../../preview/types";
import { PageTitle } from "../../../components/ui/page-title";
import { type Department, type Role } from "../../../types/workspace";
import { fail } from "../../../utils/errors";
import { newClientId } from "../../../utils/ids";
import { visitorInitials } from "../../appointments/appointment-utils";
import { accountVisibleToApprover } from "../account-utils";
import { Building2, Check, CheckCircle2, ShieldCheck, UserCog, UserPlus, X } from "lucide-react";
import { type FormEvent, useEffect, useState } from "react";

export function AccountProvisioningPanel({ role, departments: initialApprovalDepartments, onDecision, compact = false }: {
    role: Role; departments: Department[]; onDecision?: () => Promise<void> | void; compact?: boolean;
}) {
    const [accounts, setAccounts] = useState<ProvisioningAccount[]>(() =>
        !isBackendConfigured ? readDemoAccounts().filter((account) => accountVisibleToApprover(account, role)) : []);
    const [approvalDepartments, setApprovalDepartments] = useState<Department[]>(initialApprovalDepartments);
    const [assignedHrDepartmentIds, setAssignedHrDepartmentIds] = useState<Set<string>>(() => new Set(
        (!isBackendConfigured ? readDemoDepartmentHrAssignments() : [])
            .filter((assignment) => assignment.active).map((assignment) => assignment.departmentId)));
    const [assignedManagerDepartmentIds, setAssignedManagerDepartmentIds] = useState<Set<string>>(() => new Set(
        (!isBackendConfigured ? readDemoManagerAssignments() : [])
            .filter((assignment) => assignment.active).map((assignment) => assignment.departmentId)));
    const [hrDrafts, setHrDrafts] = useState<Record<string, HrAccountApprovalInput>>({});
    const [busyId, setBusyId] = useState("");
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");
    const [previewTemporaryPassword, setPreviewTemporaryPassword] = useState("");
    const [ceoSlotAvailable, setCeoSlotAvailable] = useState(() =>
        !isBackendConfigured && !readDemoAccounts().some((account) => account.role === "ROLE_CEO"
            && ["ACTIVE", "PENDING_APPROVAL"].includes(account.status)));
    const [governingCeoName, setGoverningCeoName] = useState(() =>
        !isBackendConfigured ? readDemoAccounts().find((account) => account.role === "ROLE_CEO"
            && ["ACTIVE", "PENDING_APPROVAL"].includes(account.status))?.fullName ?? "" : "");
    const [createAccountRole, setCreateAccountRole] = useState<
        "ROLE_CEO" | "ROLE_HR_ADMIN" | "ROLE_MANAGER"
    >("ROLE_HR_ADMIN");

    useEffect(() => {
        if (!["System Admin", "CEO", "HR Admin"].includes(role)) return;
        if (!isBackendConfigured) return;
        let active = true;
        const load = async () => {
            try {
                const pending = role === "System Admin"
                    ? await brainServeApi.pendingSystemAdminUsers()
                    : role === "CEO"
                        ? await brainServeApi.pendingCeoUsers()
                        : await brainServeApi.pendingHrUsers();
                if (active) setAccounts(pending);
            } catch (reason) {
                if (active) setError(reason instanceof ApiError ? reason.message : "The approval queue could not be loaded.");
            }
        };
        void load();
        return () => { active = false; };
    }, [role]);

    useEffect(() => {
        if (!isBackendConfigured || !["System Admin", "CEO"].includes(role)) return;
        let active = true;
        Promise.all([brainServeApi.departments(), brainServeApi.departmentHrAssignments(),
            brainServeApi.managerAssignments()])
            .then(([nextDepartments, hrAssignments, managerAssignments]) => {
                if (!active) return;
                setApprovalDepartments(nextDepartments);
                setAssignedHrDepartmentIds(new Set(hrAssignments.filter((assignment) => assignment.active)
                    .map((assignment) => assignment.departmentId)));
                setAssignedManagerDepartmentIds(new Set(managerAssignments.filter((assignment) => assignment.active)
                    .map((assignment) => assignment.departmentId)));
            })
            .catch((reason) => { if (active) setError(reason instanceof ApiError ? reason.message
                : "Department assignments could not be loaded."); });
        return () => { active = false; };
    }, [role]);

    useEffect(() => {
        if (role !== "System Admin" || !isBackendConfigured) return;
        let active = true;
        brainServeApi.ceoSlot()
            .then((slot) => {
                if (!active) return;
                setCeoSlotAvailable(slot.available);
                setGoverningCeoName(slot.fullName ?? "");
            })
            .catch((reason) => {
                if (active) setError(reason instanceof Error ? reason.message
                    : "CEO governance status could not be loaded.");
            });
        return () => { active = false; };
    }, [role]);

    if (!["System Admin", "CEO", "HR Admin"].includes(role)) return null;

    const createPrivileged = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();

        const form = event.currentTarget;
        const data = new FormData(form);

        setBusyId("create-privileged");
        setError("");
        setMessage("");
        setPreviewTemporaryPassword("");

        const fullName = String(data.get("fullName") ?? "").trim();
        const email = String(data.get("email") ?? "").trim().toLowerCase();
        const accountRole = String(data.get("role") ?? "");
        const departmentId = String(data.get("departmentId") ?? "").trim();
        const phoneNumber = String(data.get("phoneNumber") ?? "").trim();
        const designation = String(data.get("designation") ?? "").trim() || "HR Administrator";
        const joiningDate = String(data.get("joiningDate") ?? "").trim();

        const isHrAdmin = accountRole === "ROLE_HR_ADMIN";
        let createdAccountId: string | null = null;

        try {
            if (!fullName) fail("Enter the full name.");
            if (!email) fail("Enter the company email.");
            if (!["ROLE_CEO", "ROLE_HR_ADMIN", "ROLE_MANAGER"].includes(accountRole)) {
                fail("Select CEO, HR Admin or Manager.");
            }
            if (!email.endsWith("@brainserve.in")) {
                fail("Use an official @brainserve.in company email.");
            }

            const onboarding: HrAccountApprovalInput | undefined = isHrAdmin
                ? {
                    departmentId,
                    phoneNumber: phoneNumber || null,
                    designation,
                    joiningDate,
                }
                : undefined;

            if (isHrAdmin) {
                if (!departmentId) fail("Select a department for the HR Admin.");
                if (!designation) fail("Enter the HR Admin designation.");
                if (!joiningDate) fail("Select the HR Admin joining date.");

                const selectedDepartment = approvalDepartments.find(
                    (department) => department.id === departmentId,
                );

                if (!selectedDepartment) {
                    fail("The selected department was not found. Reload the page and select it again.");
                }
                if (!selectedDepartment.active) {
                    fail("The selected department is inactive.");
                }
                if (assignedHrDepartmentIds.has(departmentId)) {
                    fail(`${selectedDepartment.name} already has an active HR Admin.`);
                }
            }

            if (isBackendConfigured) {
                /*
         * STEP 1: Create the pending privileged account.
         */
                const created = await brainServeApi.createPrivilegedAccount(
                    fullName,
                    email,
                    accountRole,
                );

                createdAccountId = created.id;

                /*
         * STEP 2: For HR Admin, immediately approve the account and
         * assign the selected department in the same form submission.
         */
                if (isHrAdmin && onboarding) {
                    await brainServeApi.decideSystemAdminUser(
                        created.id,
                        "approve",
                        onboarding,
                    );

                    setAssignedHrDepartmentIds(
                        (current) => new Set([...current, onboarding.departmentId]),
                    );

                    setMessage(
                        `${fullName} was created, approved and assigned to the selected department as HR Admin.`,
                    );

                    form.reset();
                    setCreateAccountRole("ROLE_HR_ADMIN");
                    await onDecision?.();
                    return;
                }

                /*
         * CEO and Manager keep their existing pending workflow.
         */
                if (accountRole === "ROLE_CEO") {
                    setAccounts((items) => [...items, created]);
                    setCeoSlotAvailable(false);
                    setGoverningCeoName(fullName);
                    setMessage(
                        "The CEO account was created. System Admin approval is still required.",
                    );
                } else {
                    setMessage(
                        "The Manager account was created in pending status and routed for approval.",
                    );
                }

                form.reset();
                setCreateAccountRole("ROLE_HR_ADMIN");
                await onDecision?.();
                return;
            }

            /*
       * Browser-preview workflow.
       */
            const existingAccounts = readDemoAccounts();

            if (existingAccounts.some((account) => account.email.toLowerCase() === email)) {
                fail("An account already exists for this company email.");
            }

            const governingCeo = existingAccounts.find(
                (account) =>
                    account.role === "ROLE_CEO"
                    && ["ACTIVE", "PENDING_APPROVAL"].includes(account.status),
            );

            if (accountRole === "ROLE_CEO" && governingCeo) {
                fail(
                    `BrainServe Connect already has one governing CEO: ${governingCeo.fullName}.`,
                );
            }

            if (
                accountRole !== "ROLE_CEO"
                && !existingAccounts.some(
                    (account) => account.role === "ROLE_CEO" && account.status === "ACTIVE",
                )
            ) {
                fail(
                    "Activate the company CEO before creating an HR Admin or Manager account.",
                );
            }

            const temporaryPassword = newDemoTemporaryPassword();
            const now = new Date().toISOString();

            if (isHrAdmin && onboarding) {
                const department = approvalDepartments.find(
                    (item) => item.id === onboarding.departmentId,
                );

                if (!department) fail("The selected department was not found.");

                const accountId = newClientId();
                const employeeId = newClientId();
                const employeeNumber =
                    `BSPL-${department.code}-${String(Date.now()).slice(-4)}`;

                const previewAccount: DemoProvisioningAccount = {
                    id: accountId,
                    fullName,
                    email,
                    role: "ROLE_HR_ADMIN",
                    status: "ACTIVE",
                    employeeId,
                    createdByUserId: DEMO_SYSTEM_ADMIN.id,
                    approvedByUserId: DEMO_SYSTEM_ADMIN.id,
                    createdAt: now,
                    approvedAt: now,
                    rejectedAt: null,
                    forcePasswordChange: true,
                    passwordHash: await hashDemoPassword(temporaryPassword),
                };

                writeDemoAccounts([...existingAccounts, previewAccount]);

                writeDemoEmployees([
                    ...readDemoEmployees(),
                    {
                        id: employeeNumber,
                        uuid: employeeId,
                        departmentId: onboarding.departmentId,
                        name: fullName,
                        initials: visitorInitials(fullName),
                        role: onboarding.designation,
                        department: department.name,
                        email,
                        status: "Active",
                    },
                ]);

                const nextAssignment: DepartmentHrAssignment = {
                    id: newClientId(),
                    departmentId: onboarding.departmentId,
                    hrUserId: accountId,
                    hrEmployeeId: employeeId,
                    active: true,
                    assignedByUserId: DEMO_SYSTEM_ADMIN.id,
                    assignedAt: now,
                    endedByUserId: null,
                    endedAt: null,
                };

                writeDemoDepartmentHrAssignments([
                    nextAssignment,
                    ...readDemoDepartmentHrAssignments(),
                ]);

                setAssignedHrDepartmentIds(
                    (current) => new Set([...current, onboarding.departmentId]),
                );
                setPreviewTemporaryPassword(temporaryPassword);
                setMessage(
                    `${fullName} was created, approved and assigned to ${department.name} as HR Admin.`,
                );

                form.reset();
                setCreateAccountRole("ROLE_HR_ADMIN");
                await onDecision?.();
                return;
            }

            const previewAccount: DemoProvisioningAccount = {
                id: newClientId(),
                fullName,
                email,
                role: accountRole,
                status: "PENDING_APPROVAL",
                createdByUserId: DEMO_SYSTEM_ADMIN.id,
                approvedByUserId: null,
                createdAt: now,
                approvedAt: null,
                forcePasswordChange: true,
                passwordHash: await hashDemoPassword(temporaryPassword),
            };

            writeDemoAccounts([...existingAccounts, previewAccount]);
            setPreviewTemporaryPassword(temporaryPassword);

            if (accountRole === "ROLE_CEO") {
                setAccounts((items) => [...items, previewAccount]);
                setCeoSlotAvailable(false);
                setGoverningCeoName(fullName);
            }

            setMessage(`${fullName} was created and is waiting for approval.`);

            form.reset();
            setCreateAccountRole("ROLE_HR_ADMIN");
            await onDecision?.();
        } catch (reason) {
            const detail =
                reason instanceof Error
                    ? reason.message
                    : "The account could not be created.";

            setError(
                createdAccountId
                    ? `The account was created, but approval and department assignment failed. Account ID: ${createdAccountId}. ${detail}`
                    : detail,
            );
        } finally {
            setBusyId("");
        }
    };

    const decide = async (account: ProvisioningAccount, decision: "approve" | "reject") => {
        setBusyId(account.id); setError(""); setMessage("");
        try {
            const privilegedOnboarding = decision === "approve"
                && ["ROLE_HR_ADMIN", "ROLE_MANAGER"].includes(account.role);
            const employeeOnboarding = decision === "approve"
                && role === "HR Admin" && account.role === "ROLE_EMPLOYEE";
            const requiresOnboarding = privilegedOnboarding || employeeOnboarding;
            const onboarding = requiresOnboarding ? hrDrafts[account.id] : undefined;
            const accountRoleLabel = account.role === "ROLE_MANAGER" ? "Manager"
                : account.role === "ROLE_EMPLOYEE" ? "Employee" : "HR Admin";
            if (requiresOnboarding
                && (!onboarding?.departmentId || !onboarding.designation.trim() || !onboarding.joiningDate)) {
                fail(`Select an available department, designation and joining date before approving this ${accountRoleLabel}.`);
            }
            if (isBackendConfigured) {
                if (role === "System Admin") await brainServeApi.decideSystemAdminUser(account.id, decision, onboarding);
                else if (role === "CEO") await brainServeApi.decideCeoUser(account.id, decision, onboarding);
                else await brainServeApi.decideHrUser(account.id, decision, onboarding);
            } else {
                let employeeId: string | null = null;
                if (requiresOnboarding && onboarding) {
                    const department = approvalDepartments.find((item) => item.id === onboarding.departmentId);
                    employeeId = newClientId();
                    const employeeNumber = `BSPL-${department?.code ?? "OPS"}-${String(Date.now()).slice(-4)}`;
                    writeDemoEmployees([...readDemoEmployees(), {
                        id: employeeNumber, uuid: employeeId, departmentId: onboarding.departmentId,
                        name: account.fullName, initials: visitorInitials(account.fullName), role: onboarding.designation,
                        department: department?.name ?? "Department", email: account.email, status: "Active",
                    }]);
                    const now = new Date().toISOString();
                    if (account.role === "ROLE_HR_ADMIN") {
                        const nextAssignment: DepartmentHrAssignment = {
                            id: newClientId(), departmentId: onboarding.departmentId, hrUserId: account.id,
                            hrEmployeeId: employeeId, active: true, assignedByUserId: role === "CEO" ? "demo-ceo" : "demo-system-admin",
                            assignedAt: now, endedByUserId: null, endedAt: null,
                        };
                        writeDemoDepartmentHrAssignments([nextAssignment, ...readDemoDepartmentHrAssignments()]);
                        setAssignedHrDepartmentIds((current) => new Set([...current, onboarding.departmentId]));
                    } else if (account.role === "ROLE_MANAGER") {
                        const nextAssignment: ManagerAssignment = {
                            id: newClientId(), departmentId: onboarding.departmentId, managerUserId: account.id,
                            managerEmployeeId: employeeId, active: true,
                            assignedByUserId: role === "CEO" ? "demo-ceo" : "demo-system-admin",
                            assignedAt: now, endedByUserId: null, endedAt: null,
                        };
                        writeDemoManagerAssignments([nextAssignment, ...readDemoManagerAssignments()]);
                        setAssignedManagerDepartmentIds((current) => new Set([...current, onboarding.departmentId]));
                    }
                }
                const updated = readDemoAccounts().map((item) => item.id === account.id ? {
                    ...item,
                    status: decision === "approve" ? "ACTIVE" : "REJECTED",
                    employeeId: employeeId ?? item.employeeId,
                    approvedAt: decision === "approve" ? new Date().toISOString() : null,
                    rejectedAt: decision === "reject" ? new Date().toISOString() : null,
                } : item);
                writeDemoAccounts(updated);
            }
            setAccounts((items) => items.filter((item) => item.id !== account.id));
            if (account.role === "ROLE_CEO") {
                setCeoSlotAvailable(decision === "reject");
                setGoverningCeoName(decision === "approve" ? account.fullName : "");
            }
            if (privilegedOnboarding && onboarding) {
                if (account.role === "ROLE_HR_ADMIN") {
                    setAssignedHrDepartmentIds((current) => new Set([...current, onboarding.departmentId]));
                } else {
                    setAssignedManagerDepartmentIds((current) => new Set([...current, onboarding.departmentId]));
                }
            }
            setMessage(decision === "approve"
                ? requiresOnboarding
                    ? employeeOnboarding
                        ? `${account.fullName} was approved, assigned an employee ID and linked to the selected department.`
                        : `${account.fullName} was approved, linked to an employee ID and assigned as ${accountRoleLabel} for the selected department.`
                    : `${account.fullName} was approved and activated. The account can now sign in.`
                : `${account.fullName} was rejected and cannot sign in.`);
            await onDecision?.();
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The approval action failed."); }
        finally { setBusyId(""); }
    };

    const heading = role === "System Admin"
        ? { eyebrow: "SYSTEM ADMIN GOVERNANCE", title: "Single CEO governance", detail: "Create and approve the one company CEO. HR Admin and Manager activation belongs to that CEO company-wide." }
        : role === "CEO"
            ? { eyebrow: "CEO APPROVAL", title: "Company-wide HR and Manager approval", detail: "Review every HR Admin and Manager request regardless of your own working department." }
            : { eyebrow: "HR ADMIN APPROVAL", title: "Staff account approval", detail: "Review Employee, Receptionist and Security registrations only." };
    const queueTitle = role === "System Admin" ? "CEO account request"
        : role === "CEO" ? "HR Admin & Manager requests" : "Employee, Receptionist & Security requests";

    const updateHrDraft = (accountId: string, accountRole: string, patch: Partial<HrAccountApprovalInput>) => {
        setHrDrafts((current) => ({ ...current, [accountId]: {
                departmentId: current[accountId]?.departmentId ?? "",
                phoneNumber: current[accountId]?.phoneNumber ?? "",
                designation: current[accountId]?.designation
                    ?? (accountRole === "ROLE_MANAGER" ? "Department Manager"
                        : accountRole === "ROLE_EMPLOYEE" ? "Employee" : "HR Business Partner"),
                joiningDate: current[accountId]?.joiningDate ?? officeToday(),
                ...patch,
            } }));
    };

    return <section className="provisioning-section">
        {compact
            ? <article className="panel glass-panel"><div className="panel-heading"><div><span>{heading.eyebrow}</span>
                <h2>{heading.title}</h2><p>{heading.detail}</p></div><UserCog size={22} /></div></article>
            : <PageTitle eyebrow={heading.eyebrow} title={heading.title} detail={heading.detail} />}
        {role === "System Admin" && <article className="panel glass-panel">
            <div className="panel-heading"><div><span>CREATE PRIVILEGED ACCOUNT</span><h2>CEO, HR Admin or Manager</h2>
                <p>{isBackendConfigured
                    ? "The generated password is emailed to the user. CEO is a singleton role; HR Admin and Manager requests go only to that CEO."
                    : "Preview mode stores the account in this browser. CEO remains singleton; HR Admin and Manager requests go to the CEO queue."}</p>
            </div><UserCog size={22} /></div>
            <div className="protected-account-note"><ShieldCheck size={19} /><span>
        <strong>{ceoSlotAvailable ? "CEO slot available" : "CEO slot protected"}</strong>
        <small>{ceoSlotAvailable
            ? "Only System Admin can create the first CEO."
            : `${governingCeoName || "The company CEO"} holds the single company-wide approval role.`}</small>
      </span></div>
            <form className="staff-create-form" onSubmit={createPrivileged}>
                <label>
                    Full name
                    <input name="fullName" minLength={2} maxLength={170} required />
                </label>

                <label>
                    Company email
                    <input name="email" type="email" placeholder="name@brainserve.in" required />
                </label>

                <label>
                    Role
                    <select
                        name="role"
                        value={createAccountRole}
                        onChange={(event) => setCreateAccountRole(
                            event.target.value as "ROLE_CEO" | "ROLE_HR_ADMIN" | "ROLE_MANAGER",
                        )}
                    >
                        <option value="ROLE_CEO" disabled={!ceoSlotAvailable}>
                            {ceoSlotAvailable ? "CEO" : "CEO · already assigned"}
                        </option>
                        <option value="ROLE_HR_ADMIN">
                            HR Admin · create, approve and assign
                        </option>
                        <option value="ROLE_MANAGER">
                            Manager · pending approval
                        </option>
                    </select>
                </label>

                {createAccountRole === "ROLE_HR_ADMIN" && (
                    <>
                        <label>
                            Department
                            <select name="departmentId" defaultValue="" required>
                                <option value="">Select department</option>
                                {approvalDepartments
                                    .filter((department) => department.active)
                                    .map((department) => {
                                        const occupied = assignedHrDepartmentIds.has(department.id);
                                        return (
                                            <option
                                                key={department.id}
                                                value={department.id}
                                                disabled={occupied}
                                            >
                                                {department.name} · {department.code}
                                                {occupied ? " · HR already assigned" : ""}
                                            </option>
                                        );
                                    })}
                            </select>
                        </label>

                        <label>
                            Phone number
                            <input
                                name="phoneNumber"
                                type="tel"
                                maxLength={30}
                                placeholder="+91 98765 43210"
                            />
                        </label>

                        <label>
                            Designation
                            <input
                                name="designation"
                                defaultValue="HR Administrator"
                                minLength={2}
                                maxLength={120}
                                required
                            />
                        </label>

                        <label>
                            Joining date
                            <input
                                name="joiningDate"
                                type="date"
                                max={officeToday()}
                                defaultValue={officeToday()}
                                required
                            />
                        </label>
                    </>
                )}

                <button
                    className="button button-primary"
                    disabled={busyId === "create-privileged"}
                >
                    <UserPlus size={16} />
                    {busyId === "create-privileged"
                        ? "Creating and assigning…"
                        : createAccountRole === "ROLE_HR_ADMIN"
                            ? "Create, approve & assign HR Admin"
                            : "Create pending account"}
                </button>
            </form>
            {!isBackendConfigured && previewTemporaryPassword && <div className="recovery-code-card">
                <span><ShieldCheck size={18} /> PREVIEW PASSWORD · SHOWN FOR THIS SESSION</span>
                <h3>Temporary sign-in password</h3>
                <p>No email is sent in Preview mode. Copy this password before dismissing it.</p>
                <code>{previewTemporaryPassword}</code>
                <div><button type="button" className="text-button" onClick={() => setPreviewTemporaryPassword("")}>Dismiss password</button></div>
                <small>This account is available only in this browser profile until the Spring Boot API is connected.</small>
            </div>}
        </article>}
        <article className="panel glass-panel">
            <div className="panel-heading"><div><span>PENDING REQUESTS</span><h2>{queueTitle}</h2>
                <p>{role === "System Admin"
                    ? "Only the first CEO appears here. HR Admin and Manager requests are routed to the CEO."
                    : "HR Admin and Manager approval creates the employee profile and department ownership in the same audited action."}</p>
            </div><b>{accounts.length}</b></div>
            <div className="staff-account-list">{accounts.map((account) => {
                const requiresPrivilegedOnboarding = ["ROLE_HR_ADMIN", "ROLE_MANAGER"].includes(account.role)
                    && ["System Admin", "CEO"].includes(role);
                const requiresEmployeeOnboarding = account.role === "ROLE_EMPLOYEE" && role === "HR Admin";
                const requiresOnboarding = requiresPrivilegedOnboarding || requiresEmployeeOnboarding;
                const accountRoleLabel = account.role === "ROLE_MANAGER" ? "Manager"
                    : account.role === "ROLE_EMPLOYEE" ? "Employee" : "HR Admin";
                const unavailableDepartments = account.role === "ROLE_MANAGER"
                    ? assignedManagerDepartmentIds : assignedHrDepartmentIds;
                const availableDepartments = approvalDepartments.filter((department) => department.active
                    && (requiresEmployeeOnboarding || !unavailableDepartments.has(department.id)));
                const draft = hrDrafts[account.id] ?? {
                    departmentId: "", phoneNumber: "",
                    designation: account.role === "ROLE_MANAGER" ? "Department Manager"
                        : account.role === "ROLE_EMPLOYEE" ? "Employee" : "HR Business Partner",
                    joiningDate: officeToday(),
                };
                return <div className="staff-account-row" key={account.id}>
                    <div className="staff-account-head"><span className="role-icon"><UserCog size={18} /></span><span>
            <strong>{account.fullName}</strong><small>{account.email} · {account.role.replace("ROLE_", "").replaceAll("_", " ")}</small>
          </span><span className="status-pill status-pending"><span />Pending</span></div>
                    {requiresOnboarding && <div className="hr-approval-onboarding">
                        <div className="hr-approval-intro"><Building2 size={18} /><span><strong>Assign department before activation</strong>
              <small>{requiresEmployeeOnboarding
                  ? "This creates the employee ID and links the employee to the selected department."
                  : `This creates the employee ID and makes this person the only active ${accountRoleLabel} for the department.`}</small></span></div>
                        <div className="hr-approval-fields">
                            <label>Department<select value={draft.departmentId}
                                                     onChange={(event) => updateHrDraft(account.id, account.role, { departmentId: event.target.value })} required>
                                <option value="">{requiresEmployeeOnboarding ? "Select department" : "Select an unassigned department"}</option>
                                {availableDepartments.map((department) => <option value={department.id} key={department.id}>
                                    {department.name} · {department.code}
                                </option>)}
                            </select></label>
                            <label>Designation<input value={draft.designation} maxLength={120}
                                                     onChange={(event) => updateHrDraft(account.id, account.role, { designation: event.target.value })} required /></label>
                            <label>Phone number<input value={draft.phoneNumber ?? ""} maxLength={30} placeholder="+91 98765 43210"
                                                      onChange={(event) => updateHrDraft(account.id, account.role, { phoneNumber: event.target.value })} /></label>
                            <label>Joining date<input type="date" max={officeToday()} value={draft.joiningDate}
                                                      onChange={(event) => updateHrDraft(account.id, account.role, { joiningDate: event.target.value })} required /></label>
                        </div>
                        {availableDepartments.length === 0 && <div className="login-error" role="alert">
                            {requiresEmployeeOnboarding
                                ? "No active department is available for employee assignment."
                                : account.role === "ROLE_MANAGER"
                                    ? "Every active department already has a Manager. End an existing assignment before approving another Manager."
                                    : "Every active department already has an HR Admin. End an existing assignment before approving another HR Admin."}
                        </div>}
                    </div>}
                    <div className="approval-actions">
                        <button className="button button-reject" disabled={busyId === account.id}
                                onClick={() => void decide(account, "reject")}><X size={16} /> Reject</button>
                        <button className="button button-approve" disabled={busyId === account.id
                            || (requiresOnboarding && (!draft.departmentId || availableDepartments.length === 0))}
                                onClick={() => void decide(account, "approve")}><Check size={16} />
                            {requiresOnboarding ? "Approve, create ID & assign" : "Approve & activate"}</button>
                    </div>
                </div>;
            })}
                {accounts.length === 0 && <div className="empty-state"><CheckCircle2 size={28} />
                    <strong>No pending account requests</strong><small>The approval queue is clear.</small></div>}
            </div>
        </article>
        {message && <div className="success-banner"><CheckCircle2 size={17} /> {message}</div>}
        {error && <div className="login-error" role="alert">{error}</div>}
    </section>;
}

