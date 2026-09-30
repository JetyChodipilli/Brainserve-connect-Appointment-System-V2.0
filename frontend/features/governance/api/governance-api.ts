import { apiRequest } from "../../../lib/api-client";
import type { RetentionPolicy, ArchiveManifest, DataLegalHold, GovernanceLedgerPage, GovernanceOverview } from "../../../types/api";

export const governanceApi = {
retentionPolicies() { return apiRequest<RetentionPolicy[]>("/admin/data-governance/retention-policies"); },
updateRetentionPolicy(dataset: string, payload: Pick<RetentionPolicy,
      "hotDays" | "warmMonths" | "archiveYears" | "disposalAction" | "enabled">) {
    return apiRequest<RetentionPolicy>(`/admin/data-governance/retention-policies/${encodeURIComponent(dataset)}`,
        { method: "PUT", body: JSON.stringify(payload) });
  },
archiveManifests() { return apiRequest<ArchiveManifest[]>("/admin/data-governance/archive-manifests"); },
governanceOverview() { return apiRequest<GovernanceOverview>("/admin/data-governance/overview"); },
dataLegalHolds() { return apiRequest<DataLegalHold[]>("/admin/data-governance/legal-holds"); },
createDataLegalHold(payload: { dataset: string; holdKind: DataLegalHold["holdKind"];
    scopeType: DataLegalHold["scopeType"]; scopeRef: string | null; caseReference: string;
    reason: string; reviewOn: string | null }) {
    return apiRequest<DataLegalHold>("/admin/data-governance/legal-holds",
        { method: "POST", body: JSON.stringify(payload) });
  },
releaseDataLegalHold(holdId: string, reason: string) {
    return apiRequest<DataLegalHold>(`/admin/data-governance/legal-holds/${encodeURIComponent(holdId)}/release`,
        { method: "POST", body: JSON.stringify({ reason }) });
  },
governanceLedger(size = 50) {
    return apiRequest<GovernanceLedgerPage>(`/admin/data-governance/ledger?size=${size}`);
  },
};
