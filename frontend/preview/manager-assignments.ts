import { type ManagerAssignment } from "../services/brainserve-api";
import { initialManagerAssignments } from "./fixtures/workspace";
import { DEMO_MANAGER_ASSIGNMENTS_KEY } from "./storage-keys";

export function readDemoManagerAssignments(): ManagerAssignment[] {
    if (typeof window === "undefined") return initialManagerAssignments;
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_MANAGER_ASSIGNMENTS_KEY) ?? "[]");
        return Array.isArray(value) && value.length ? value : initialManagerAssignments;
    } catch { return initialManagerAssignments; }
}

export function writeDemoManagerAssignments(items: ManagerAssignment[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_MANAGER_ASSIGNMENTS_KEY, JSON.stringify(items));
}

