import { apiRequest } from "../../../lib/api-client";
import type { WorkspaceSetting, RoleDefinition, IntegrationOverview } from "../../../types/api";

export const settingsApi = {
roleDefinitions() {
    return apiRequest<RoleDefinition[]>("/admin/roles");
  },
workspaceSettings() {
    return apiRequest<WorkspaceSetting[]>("/workspace-settings");
  },
updateWorkspaceSetting(key: string, value: string) {
    return apiRequest<WorkspaceSetting>(`/workspace-settings/${encodeURIComponent(key)}`, {
      method: "PUT", body: JSON.stringify({ value }),
    });
  },
systemSettings() {
    return apiRequest<WorkspaceSetting[]>("/system-settings");
  },
updateSystemSetting(key: string, value: string) {
    return apiRequest<WorkspaceSetting>(`/system-settings/${encodeURIComponent(key)}`, {
      method: "PUT", body: JSON.stringify({ value }),
    });
  },
integrationHealth() {
    return apiRequest<IntegrationOverview>("/admin/integrations", { cache: "no-store" });
  },
};
