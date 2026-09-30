"use client";

import { type Role } from "../../../shared/types/workspace";
import { historyDatasetLabels, historyDatasetsByRole } from "../report-utils";

export function HistoryDatasetOptions({ role }: { role: Role }) {
    const allowed = historyDatasetsByRole[role];
    const otherRecords = allowed.filter((dataset) => dataset !== "VISITS");
    return <>
        {allowed.includes("VISITS") && <option value="VISITS">{historyDatasetLabels.VISITS}</option>}
        {otherRecords.length > 0 && <optgroup label="Other records">
            {otherRecords.map((dataset) =>
                <option key={dataset} value={dataset}>{historyDatasetLabels[dataset]}</option>)}
        </optgroup>}
    </>;
}

