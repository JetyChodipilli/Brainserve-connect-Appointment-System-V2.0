import BrainServeApp from "../features/workspace/brainserve-app";
import AppErrorBoundary from "../components/shared/app-error-boundary";
import ConnectionRecovery from "../components/shared/connection-recovery";

export default function Home() {
  const backendConfigured = Boolean(
      process.env.NEXT_PUBLIC_API_BASE_URL?.trim(),
  );

  const browserPreviewEnabled =
      !backendConfigured &&
      process.env.NEXT_PUBLIC_BROWSER_PREVIEW === "true" &&
      import.meta.env.VITE_BRAINSERVE_LOCKED !== "1";

  if (!backendConfigured && !browserPreviewEnabled) {
    return <ConnectionRecovery mode="unavailable" />;
  }

  return (
      <AppErrorBoundary>
        <BrainServeApp browserPreviewEnabled={browserPreviewEnabled} />
      </AppErrorBoundary>
  );
}

