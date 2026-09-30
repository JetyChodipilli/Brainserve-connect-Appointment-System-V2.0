"use client";

import { createStaffActions } from "../../features/accounts/actions/workspace-staff-actions";
import { createAppointmentActions } from "../../features/appointments/actions/workspace-appointment-actions";
import { createEmployeeActions } from "../../features/employees/actions/workspace-employee-actions";
import { createOrganizationActions } from "../../features/organization/actions/workspace-organization-actions";
import { createVisitorActions } from "../../features/visitors/actions/workspace-visitor-actions";
import { useAppointmentSounds } from "./use-appointment-sounds";
import { useAppointmentPolling } from "./use-appointment-polling";
import { useNotificationPreferences } from "./use-notification-preferences";
import { usePreviewIdentity } from "./use-preview-identity";
import { usePreviewSynchronization } from "./use-preview-synchronization";
import { useProfileMenu } from "./use-profile-menu";
import { useProfileSynchronization } from "./use-profile-synchronization";
import { useRealtimeWorkspace } from "./use-realtime-workspace";
import { useWorkspaceData } from "./use-workspace-data";
import { useNotificationCounts } from "./use-notification-counts";
import { useWorkspaceSelectors } from "./use-workspace-selectors";
import { useWorkspaceState, type WorkspaceIdentity } from "./use-workspace-state";

export function useWorkspace(props: WorkspaceIdentity) {
    const workspace = useWorkspaceState(props);
    const { refreshPreviewWorkspace, setWorkspaceRevision, loggingOut, setLoggingOut, onLogout, setProfileMenuOpen } = workspace;

    usePreviewIdentity(workspace);


    const requestWorkspaceRefresh = () => {
        refreshPreviewWorkspace();
        setWorkspaceRevision((revision) => revision + 1);
    };
    useNotificationPreferences(workspace);

    usePreviewSynchronization(workspace);

    useAppointmentSounds(workspace);

    useProfileSynchronization(workspace);

    useProfileMenu(workspace);


    const signOut = async () => {
        if (loggingOut) return;
        setLoggingOut(true);
        try { await onLogout(); }
        finally { setLoggingOut(false); setProfileMenuOpen(false); }
    };
    useRealtimeWorkspace(workspace);

    useWorkspaceData(workspace);

    useAppointmentPolling(workspace);

    useNotificationCounts(workspace);

    const selectors = useWorkspaceSelectors(workspace);
    const appointmentActions = createAppointmentActions(workspace);
    const employeeActions = createEmployeeActions(workspace);
    const organizationActions = createOrganizationActions(workspace);
    const visitorActions = createVisitorActions({ ...workspace, updateAppointment: appointmentActions.updateAppointment });
    const staffActions = createStaffActions(workspace);
    return { ...workspace, ...selectors, requestWorkspaceRefresh, signOut, ...appointmentActions, ...employeeActions, ...organizationActions, ...visitorActions, ...staffActions };
}
