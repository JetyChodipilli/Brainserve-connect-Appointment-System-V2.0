import type { StaffAccount, TeamLeadAssignment } from "../../../types/api";
import type { Appointment, Department, Employee, Role, View } from "../../../types/workspace";

export type WorkboardProps = {
    role: Role; userEmail: string; employees: Employee[]; departments: Department[];
    refreshKey: number;
    initialTaskId?: string;
    staffAccounts: StaffAccount[];
    teamLeadAssignments: TeamLeadAssignment[]; appointments: Appointment[];
    decideAppointment: (id: string, decision: "approve" | "reject") => Promise<void>;
    onNavigate: (view: View) => void;
};
