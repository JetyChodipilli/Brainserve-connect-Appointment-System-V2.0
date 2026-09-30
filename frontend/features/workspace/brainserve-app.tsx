"use client";

import { BackendBrainServeApp } from "./brainserve-root";
import ConnectionRecovery from "../../components/shared/connection-recovery";
import { isBackendConfigured } from "../../services/brainserve-api";

export function BrainServeApp({ browserPreviewEnabled = false }: {
    browserPreviewEnabled?: boolean;
}) {
    if (!isBackendConfigured && !browserPreviewEnabled) {
        return <ConnectionRecovery mode="unavailable" />;
    }
    return <BackendBrainServeApp browserPreviewEnabled={browserPreviewEnabled} />;
}

export default BrainServeApp;
