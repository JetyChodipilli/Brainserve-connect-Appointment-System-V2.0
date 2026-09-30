import { apiRequest } from "../../../lib/api-client";
import type { ResourceDiscussion } from "../../../types/api";

export const discussionsApi = {
resourceDiscussions() { return apiRequest<ResourceDiscussion[]>("/resource-discussions"); },
createResourceDiscussion(payload: { hrRecipientUserId: string; projectName: string; requiredRoles: string;
    requestedHeadcount: number; priority: string; preferredAt: string; justification: string }) {
    return apiRequest<ResourceDiscussion>("/resource-discussions", {
      method: "POST", body: JSON.stringify(payload),
    });
  },
decideResourceDiscussion(id: string, action: "SCHEDULE" | "REQUEST_INFORMATION" | "DECLINE",
                           response: string, scheduledAt: string | null) {
    return apiRequest<ResourceDiscussion>(`/resource-discussions/${id}/hr-action`, {
      method: "POST", body: JSON.stringify({ action, response, scheduledAt }),
    });
  },
reviseResourceDiscussion(id: string, payload: { requiredRoles: string; requestedHeadcount: number;
    preferredAt: string; justification: string }) {
    return apiRequest<ResourceDiscussion>(`/resource-discussions/${id}/revise`, {
      method: "POST", body: JSON.stringify(payload),
    });
  },
completeResourceDiscussion(id: string) {
    return apiRequest<ResourceDiscussion>(`/resource-discussions/${id}/complete`, { method: "POST" });
  },
};
