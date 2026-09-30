"use client";

import { brainServeApi, isBackendConfigured, type StaffAccount, type TeamLeadPerformance, type WorkTask } from "../../lib/api";
import { officeToday } from "../../lib/appointments";
import { initialStaffAccounts } from "../../preview/fixtures/workspace";
import { readDemoWorkTasks } from "../../preview/work";
import { PageTitle } from "../../shared/components/page-title";
import { type Department, type Employee } from "../../shared/types/workspace";
import { visitorInitials } from "../appointments/appointment-utils";
import { Sparkles } from "lucide-react";
import { useEffect, useState } from "react";

export function TeamLeadPerformanceView({ departments, employees, staffAccounts }: {
    departments: Department[]; employees: Employee[]; staffAccounts: StaffAccount[];
}) {
    const [items, setItems] = useState<TeamLeadPerformance[]>([]);
    const [error, setError] = useState("");
    useEffect(() => {
        let active = true;
        const load = async () => {
            try {
                if (isBackendConfigured) {
                    const values = await brainServeApi.teamLeadPerformance(); if (active) setItems(values);
                } else {
                    const grouped = new Map<string, WorkTask[]>();
                    readDemoWorkTasks().forEach((task) => { const key = `${task.teamLeadUserId}:${task.departmentId}`;
                        grouped.set(key, [...(grouped.get(key) ?? []), task]); });
                    setItems([...grouped.values()].map((tasks) => { const first = tasks[0]; const approved = tasks.filter((item) => ["APPROVED", "ACKNOWLEDGED"].includes(item.status));
                        return { teamLeadUserId: first.teamLeadUserId, departmentId: first.departmentId, totalTasks: tasks.length,
                            completedTasks: tasks.filter((item) => ["COMPLETED", "APPROVED", "ACKNOWLEDGED"].includes(item.status)).length,
                            approvedTasks: approved.length, inProgressTasks: tasks.filter((item) => item.status === "IN_PROGRESS").length,
                            pendingReviewTasks: tasks.filter((item) => item.status === "COMPLETED").length,
                            overdueTasks: tasks.filter((item) => item.dueDate < officeToday() && !["APPROVED", "ACKNOWLEDGED"].includes(item.status)).length,
                            completionRate: Math.round(approved.length * 100 / tasks.length), lastApprovedAt: approved.map((item) => item.approvedAt).filter(Boolean).sort().at(-1) ?? null };
                    }));
                }
                setError("");
            } catch (reason) { if (active) setError(reason instanceof Error ? reason.message : "Team Lead performance could not be loaded."); }
        };
        void load(); return () => { active = false; };
    }, []);
    const leadName = (userId: string) => staffAccounts.find((item) => item.userId === userId)?.fullName
        ?? (!isBackendConfigured ? initialStaffAccounts.find((item) => item.userId === userId)?.fullName : undefined)
        ?? "Team Lead";
    const departmentName = (id: string) => departments.find((item) => item.id === id)?.name ?? "Assigned department";
    const total = items.reduce((sum, item) => sum + item.totalTasks, 0);
    const approved = items.reduce((sum, item) => sum + item.approvedTasks, 0);
    const pending = items.reduce((sum, item) => sum + item.pendingReviewTasks, 0);
    const overdue = items.reduce((sum, item) => sum + item.overdueTasks, 0);
    return <section className="team-lead-performance-page"><PageTitle eyebrow="HR DELIVERY INTELLIGENCE" title="Team Lead performance"
                                                                      detail="Review department delivery, completion quality and overdue work. Every Team Lead approval also sends HR a BrainServe Internal Calls update." />
        <section className="work-metrics glass-panel"><div><span>Tracked work</span><strong>{total}</strong><small>Across Team Lead departments</small></div><i /><div><span>Approved</span><strong>{approved}</strong><small>Verified by Team Leads</small></div><i /><div><span>Awaiting review</span><strong>{pending}</strong><small>Completed by employees</small></div><i /><div><span>Overdue</span><strong>{overdue}</strong><small>Needs HR attention</small></div></section>
        <article className="performance-table glass-panel"><div className="performance-head"><span>Team Lead & department</span><span>Delivery</span><span>In progress</span><span>Awaiting review</span><span>Overdue</span><span>Last approval</span></div>{items.map((item) => <div className="performance-row" key={`${item.teamLeadUserId}:${item.departmentId}`}><div className="person-cell"><span className="avatar">{visitorInitials(leadName(item.teamLeadUserId))}</span><span><strong>{leadName(item.teamLeadUserId)}</strong><small>{departmentName(item.departmentId)} · {employees.filter((employee) => employee.departmentId === item.departmentId).length} employees</small></span></div><div><strong className="performance-rate">{item.completionRate}%</strong><small>{item.approvedTasks}/{item.totalTasks} approved</small></div><div><strong>{item.inProgressTasks}</strong><small>Active work</small></div><div><strong>{item.pendingReviewTasks}</strong><small>Needs Team Lead</small></div><div><strong className={item.overdueTasks ? "danger-text" : ""}>{item.overdueTasks}</strong><small>Past due</small></div><div><strong>{item.lastApprovedAt ? new Date(item.lastApprovedAt).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" }) : "No approvals"}</strong><small>Live update sent to HR</small></div></div>)}{items.length === 0 && <div className="empty-state"><Sparkles size={28} /><strong>No Team Lead delivery data yet</strong><small>Performance appears after a Team Lead assigns the first department task.</small></div>}</article>{error && <div className="login-error" role="alert">{error}</div>}
    </section>;
}

