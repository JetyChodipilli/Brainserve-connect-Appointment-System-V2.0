import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) =>
    readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const auditService = read(
    "../backend/src/main/java/com/brainserve/appointment/audit/api/AuditService.java",
);
const realtimeController = read(
    "../backend/src/main/java/com/brainserve/appointment/realtime/api/RealtimeUpdateController.java",
);
const realtimeHub = read(
    "../backend/src/main/java/com/brainserve/appointment/realtime/application/RealtimeUpdateHub.java",
);
const realtimeListener = read(
    "../backend/src/main/java/com/brainserve/appointment/realtime/application/RealtimeWorkspaceListener.java",
);
const frontendApi = readApiSource();
const frontend = readFrontendSource();

test("audited service changes broadcast only after their transaction commits", () => {
    assert.ok(
        auditService.includes("WorkspaceChangeEvent"),
    );
    assert.ok(
        auditService.includes("eventPublisher.publishEvent"),
    );
    assert.ok(
        sourceIncludes(
            auditService,
            "@Transactional public void record",
        ),
    );
    assert.equal(
        auditService.includes("REQUIRES_NEW"),
        false,
        "audit events must join the business transaction so refreshes cannot race its commit",
    );
    assert.ok(
        realtimeListener.includes(
            "TransactionPhase.AFTER_COMMIT",
        ),
    );
    assert.ok(
        realtimeListener.includes("broadcastRefresh"),
    );
});

test("live workspace stream is authenticated, resilient and data-minimal", () => {
    assert.ok(
        realtimeController.includes(
            '@RequestMapping("/api/v1/realtime")',
        ),
    );
    assert.ok(
        realtimeController.includes(
            '@PreAuthorize("isAuthenticated()")',
        ),
    );
    assert.ok(
        realtimeController.includes(
            "produces = MediaType.TEXT_EVENT_STREAM_VALUE",
        ),
    );
    assert.ok(
        realtimeHub.includes(
            '"workspace-refresh", "refresh"',
        ),
    );
    assert.ok(
        realtimeHub.includes(
            "@Scheduled(fixedDelay = 25_000L)",
        ),
    );
    assert.equal(
        realtimeHub.includes("targetId"),
        false,
        "the broadcast must not expose record identifiers",
    );
});

test("frontend subscribes with the access token and coalesces role-scoped refreshes", () => {
    assert.ok(
        frontendApi.includes("subscribeToWorkspaceUpdates"),
    );
    assert.ok(
        frontendApi.includes(
            "Authorization: `Bearer ${accessToken}`",
        ),
    );
    assert.ok(
        frontendApi.includes(
            'eventName === "workspace-refresh"',
        ),
    );
    assert.ok(
        frontendApi.includes("scheduleReconnect"),
    );
    assert.ok(frontend.includes("setWorkspaceRevision"));
    assert.ok(frontend.includes("const minimumRefreshInterval = 15_000"));
    assert.ok(frontend.includes("queueSafeRefresh"));
    assert.equal(frontend.includes("key={`work:${workspaceRevision}`"), false);
    assert.equal(frontend.includes("key={`notifications:${workspaceRevision}`"), false);
    assert.ok(frontend.includes("className={`live-status"));
});

test("multiple tabs share one live backend stream through browser leader election", () => {
    assert.ok(frontendApi.includes("subscribeDirectlyToWorkspaceUpdates"));
    assert.ok(frontendApi.includes("WORKSPACE_UPDATE_LOCK"));
    assert.ok(frontendApi.includes("BroadcastChannel"));
    assert.ok(frontendApi.includes("WORKSPACE_UPDATE_LEASE_KEY"));
    assert.ok(frontendApi.includes("isWorkspaceUpdateLeader"));
    assert.ok(frontendApi.includes('type: "refresh"'));
    assert.ok(frontend.includes('document.visibilityState !== "visible"'));
    assert.ok(frontend.includes("if (isWorkspaceUpdateLeader())"));
});

test("public brand and internal-call language are product-facing", () => {
    assert.ok(
        frontend.includes(
            'productName="BrainServe Connect"',
        ),
    );
    assert.ok(
        frontend.includes(
            'eyebrow="BRAINSERVE INTERNAL DELIVERY"',
        ),
    );
    assert.equal(
        /kafka/i.test(frontend),
        false,
        "technical Kafka wording must not leak into the interface",
    );
});

