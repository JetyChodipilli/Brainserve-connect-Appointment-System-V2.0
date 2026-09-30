import { type WorkInsight, type WorkTask } from "../lib/api";
import { readDemoDepartments } from "./directory";
import { initialWorkTasks } from "./fixtures/workspace";
import { DEMO_WORK_INSIGHTS_KEY, DEMO_WORK_TASKS_KEY } from "./storage-keys";

export function readDemoWorkTasks(): WorkTask[] {
    if (typeof window === "undefined") return initialWorkTasks;
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_WORK_TASKS_KEY) ?? "[]");
        const tasks = Array.isArray(value) && value.length ? value : initialWorkTasks;
        const departments = readDemoDepartments();
        return tasks.map((item: WorkTask & { category?: string }) => ({ ...item,
            assignedByUserId: item.assignedByUserId ?? item.teamLeadUserId,
            assignedByRole: item.assignedByRole ?? "TEAM_LEAD",
            assigneeRole: item.assigneeRole ?? "EMPLOYEE",
            departmentBranch: item.departmentBranch
                ?? departments.find((department) => department.id === item.departmentId)?.name
                ?? item.category ?? "Assigned department",
            insightReviewSource: item.insightReviewSource ?? null,
            insightReviewReason: item.insightReviewReason ?? null,
            insightReviewRequestedAt: item.insightReviewRequestedAt ?? null,
            reworkCycle: item.reworkCycle ?? 0 }));
    } catch { return initialWorkTasks; }
}

export function writeDemoWorkTasks(items: WorkTask[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_WORK_TASKS_KEY, JSON.stringify(items));
}

export function readDemoWorkInsights(): WorkInsight[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_WORK_INSIGHTS_KEY) ?? "[]");
        return Array.isArray(value) ? value.map((item: WorkInsight) => ({ ...item,
            assignedByRole: item.assignedByRole ?? "TEAM_LEAD",
            assigneeRole: item.assigneeRole ?? "EMPLOYEE",
            managerDecidedAt: item.managerDecidedAt ?? null,
            managerRemarks: item.managerRemarks ?? null,
            reworkRequestedByRole: item.reworkRequestedByRole ?? null,
            reworkReason: item.reworkReason ?? null,
            reworkRequestedAt: item.reworkRequestedAt ?? null,
            teamLeadReworkGuidance: item.teamLeadReworkGuidance ?? null,
            teamLeadRespondedAt: item.teamLeadRespondedAt ?? null,
            reworkCycle: item.reworkCycle ?? 0 })) : [];
    } catch { return []; }
}

export function writeDemoWorkInsights(items: WorkInsight[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_WORK_INSIGHTS_KEY, JSON.stringify(items));
}

