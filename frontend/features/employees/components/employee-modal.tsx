"use client";

import { type StaffAccount, type TeamLeadAssignment } from "../../../services/brainserve-api";
import { nextBusinessDays } from "../../../lib/appointments";
import { useModalDialog } from "../../../hooks/use-modal-dialog";
import { type Department, type Employee } from "../../../types/workspace";
import { BadgeCheck, Fingerprint, UserPlus, X } from "lucide-react";
import { type FormEvent, useMemo, useState } from "react";

export function EmployeeModal({ departments, employees, teamLeadAssignments, account, initialDepartmentId, error, onClose, onSubmit }: {
    departments: Department[]; employees: Employee[]; teamLeadAssignments: TeamLeadAssignment[]; account?: StaffAccount;
    initialDepartmentId?: string; error?: string; onClose: () => void; onSubmit: (event: FormEvent<HTMLFormElement>) => void | Promise<void> }) {
    useModalDialog(onClose);
    const joiningDate = nextBusinessDays(1)[0];
    const activeDepartments = useMemo(() => departments.filter((item) => item.active), [departments]);
    const [departmentId, setDepartmentId] = useState(() => {
        if (initialDepartmentId && activeDepartments.some((item) => item.id === initialDepartmentId)) {
            return initialDepartmentId;
        }
        return activeDepartments.length === 1 ? activeDepartments[0].id : "";
    });
    const selectedDepartmentId = activeDepartments.some((item) => item.id === departmentId)
        ? departmentId : activeDepartments.length === 1 ? activeDepartments[0].id : "";
    const activeLeadAssignment = teamLeadAssignments.find((assignment) => assignment.active
        && assignment.departmentId === selectedDepartmentId);
    const departmentTeamLead = employees.find((employee) => (employee.uuid ?? employee.id)
        === activeLeadAssignment?.teamLeadEmployeeId);
    const teamLeadLabel = !selectedDepartmentId ? "Select a department first"
        : departmentTeamLead ? `${departmentTeamLead.name} · Team Lead`
            : activeLeadAssignment ? "Assigned Team Lead"
                : "No Team Lead assigned";
    return <div className="modal-backdrop" role="presentation"><section className="modal glass-panel" role="dialog" aria-modal="true" aria-labelledby="employee-modal-title"><header><div><span>{account ? "DEPARTMENT ASSIGNMENT" : "EMPLOYEE ONBOARDING"}</span><h2 id="employee-modal-title">{account ? "Complete approved employee profile" : "Add a new employee"}</h2><p>{account ? "Assign the approved login to a department. BrainServe then links the employee ID to this account." : "The employee ID is generated safely after submission."}</p></div><button className="icon-button" onClick={onClose} aria-label="Close employee form"><X size={19} /></button></header>{account && <div className="approved-account-banner"><BadgeCheck size={18} /><span><strong>Approved Employee login</strong><small>{account.email} · account role remains Employee until an eligible Team Lead promotion</small></span></div>}<form onSubmit={onSubmit}>{error && <div className="login-error" role="alert">{error}</div>}{activeDepartments.length === 0 && <div className="login-error" role="alert">No active department is available for this account. Ask the CEO or System Admin to complete the HR department assignment.</div>}<div className="modal-form-grid"><label>Full name<input name="name" required minLength={2} maxLength={170} defaultValue={account?.fullName ?? ""} placeholder="Employee’s full name" /></label><label>Official email<input name="email" type="email" required readOnly={Boolean(account)} defaultValue={account?.email ?? ""} placeholder="name@brainserve.in" /></label><label>Phone number<input name="phone" placeholder="+91 98765 43210" /></label><label>Department<select name="departmentId" value={selectedDepartmentId} onChange={(event) => setDepartmentId(event.target.value)} required disabled={activeDepartments.length === 0}><option value="">{activeDepartments.length ? "Select a department" : "No active department available"}</option>{activeDepartments.map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}</select></label><label>Designation<input name="designation" required placeholder="e.g. Software Engineer" /></label><label>Joining date<input name="joiningDate" type="date" min={joiningDate} defaultValue={joiningDate} required /></label><label>Department Team Lead<input value={teamLeadLabel} readOnly aria-readonly="true" /><small>Resolved automatically from the active Team Lead assignment for this department.</small></label></div><div className="employee-id-preview"><Fingerprint size={21} /><span><small>GENERATED EMPLOYEE ID</small><strong>BSPL-XXXX-####</strong></span><small>Concurrency-safe sequence</small></div><div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose}>Cancel</button><button className="button button-primary" disabled={activeDepartments.length === 0 || !selectedDepartmentId}><UserPlus size={17} /> {account ? "Assign department & create ID" : "Create employee"}</button></div></form></section></div>;
}

