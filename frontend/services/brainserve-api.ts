// Cross-feature compatibility facade. Add endpoints in the owning feature's api folder.
export * from "../lib/api-client";
export type * from "../types/api";

import { authApi } from "../lib/api-client";
import { publicApi } from "../features/public/api/public-api";
import { visitorsApi } from "../features/visitors/api/visitors-api";
import { accountsApi } from "../features/accounts/api/accounts-api";
import { appointmentsApi } from "../features/appointments/api/appointments-api";
import { dashboardApi } from "../features/dashboard/api/dashboard-api";
import { reportsApi } from "../features/reports/api/reports-api";
import { governanceApi } from "../features/governance/api/governance-api";
import { employeesApi } from "../features/employees/api/employees-api";
import { organizationApi } from "../features/organization/api/organization-api";
import { profileApi } from "../features/profile/api/profile-api";
import { auditApi } from "../features/audit/api/audit-api";
import { settingsApi } from "../features/settings/api/settings-api";
import { notificationsApi } from "../features/notifications/api/notifications-api";
import { leaveApi } from "../features/leave/api/leave-api";
import { discussionsApi } from "../features/discussions/api/discussions-api";
import { workboardApi } from "../features/workboard/api/workboard-api";

export const brainServeApi = {
    ...authApi,
    ...publicApi,
    ...visitorsApi,
    ...accountsApi,
    ...appointmentsApi,
    ...dashboardApi,
    ...reportsApi,
    ...governanceApi,
    ...employeesApi,
    ...organizationApi,
    ...profileApi,
    ...auditApi,
    ...settingsApi,
    ...notificationsApi,
    ...leaveApi,
    ...discussionsApi,
    ...workboardApi,
};
