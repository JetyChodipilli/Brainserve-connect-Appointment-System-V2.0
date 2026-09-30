"use client";

import { BackendBrainServeApp } from "./application/brainserve-root";
import ConnectionRecovery from "./connection-recovery";
import { isBackendConfigured } from "./lib/api";

export function BrainServeApp({ browserPreviewEnabled = false }: {
    browserPreviewEnabled?: boolean;
}) {
    if (!isBackendConfigured && !browserPreviewEnabled) {
        return <ConnectionRecovery mode="unavailable" />;
    }
    return <BackendBrainServeApp browserPreviewEnabled={browserPreviewEnabled} />;
}

export default BrainServeApp;
