export type DiagnosticPreview = {
    schemaVersion: number; supportReference: string; generatedAt: string; windowStart: string; windowEnd: string;
    environment: string; releaseVersion: string; buildRevision: string; databaseStatus: string; migrationVersion: number;
    connections: { active: number; needsReconnect: number; revoked: number };
    deliveries: { pending: number; running: number; delivered: number; failed: number; needsReconnect: number; cancelled: number; superseded: number };
};
export type DiagnosticPackage = { id: string; createdAt: string; expiresAt: string; sizeBytes: number; downloadCount: number; windowStart: string; windowEnd: string };
