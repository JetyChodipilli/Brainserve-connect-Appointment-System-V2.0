import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) =>
    readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("browser preview requires explicit opt-in and lock mode exposes no fixed OTP", () => {
  const page = read("app/page.tsx");
  const api = readApiSource();
  const app = readFrontendSource();
  const frontendImage = read("Dockerfile");
  const packageJson = JSON.parse(read("package.json"));
  const playwrightConfig = read("playwright.config.ts");

  assert.ok(
      sourceIncludes(
          page,
          "const backendConfigured = Boolean(process.env.NEXT_PUBLIC_API_BASE_URL?.trim())",
      ),
  );
  assert.match(page, /!backendConfigured/);
  assert.match(
      page,
      /process\.env\.NEXT_PUBLIC_BROWSER_PREVIEW === "true"/,
  );
  assert.match(
      page,
      /import\.meta\.env\.VITE_BRAINSERVE_LOCKED !== "1"/,
  );
  assert.match(
      page,
      /if \(!backendConfigured && !browserPreviewEnabled\)/,
  );
  assert.match(
      page,
      /return <ConnectionRecovery mode="unavailable" \/>/,
  );
  assert.match(
      api,
      /const API_BASE_URL = configuredApiBaseUrl \?\? ""/,
  );
  assert.doesNotMatch(api, /localhost:8080/);
  assert.doesNotMatch(app, /123456/);
  assert.match(
      app,
      /const previewOtpIsValid = \(otp: string\)[\s\S]*return false/,
  );
  assert.match(
      frontendImage,
      /^ARG NEXT_PUBLIC_API_BASE_URL$/m,
  );
  assert.doesNotMatch(
      frontendImage,
      /ARG NEXT_PUBLIC_API_BASE_URL=/,
  );
  assert.match(
      packageJson.scripts["dev:locked"],
      /VITE_BRAINSERVE_LOCKED=1 NEXT_PUBLIC_API_BASE_URL= vite/,
  );
  assert.doesNotMatch(
      packageJson.scripts["dev:locked"],
      /NEXT_PUBLIC_API_BASE_URL=https?:\/\//,
  );
  assert.match(
      playwrightConfig,
      /env: \{ NEXT_PUBLIC_API_BASE_URL: "" \}/,
  );
});

test("browser preview exposes role workspaces without adding a hosted credential", () => {
  const app = readFrontendSource();

  assert.match(app, /BROWSER PREVIEW/);
  assert.match(app, /BROWSER_PREVIEW_ROLE_ORDER/);
  assert.match(
      app,
      /if \(!isBackendConfigured && !browserPreviewEnabled\)/,
  );
  assert.match(
      app,
      /startBrowserPreviewRole\(previewRole\)/,
  );
  assert.doesNotMatch(app, /browserPreviewPassword/);
});

test("Kafka delivery polls committed rows and retries until the consumer acknowledges them", () => {
  const service = read(
      "../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java",
  );
  const repository = read(
      "../backend/src/main/java/com/brainserve/appointment/notification/infrastructure/InternalCallNotificationRepository.java",
  );
  const consumer = read(
      "../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationConsumer.java",
  );

  assert.match(
      service,
      /@Scheduled[\s\S]*dispatchPending\(\)/,
  );
  assert.match(service, /lockReadyForDelivery/);
  assert.doesNotMatch(
      service,
      /TransactionSynchronizationManager/,
  );
  assert.match(
      repository,
      /LockModeType\.PESSIMISTIC_WRITE/,
  );
  assert.match(
      consumer,
      /INTERNAL_NOTIFICATION_NOT_COMMITTED/,
  );
  assert.match(
      consumer,
      /INTERNAL_NOTIFICATION_EVENT_MISMATCH/,
  );
});

test("CEO succession is one locked transaction and V45 revokes inferred V43 authority", () => {
  const service = read(
      "../backend/src/main/java/com/brainserve/appointment/iam/application/OperationalRoleTransitionService.java",
  );
  const controller = read(
      "../backend/src/main/java/com/brainserve/appointment/iam/api/OperationalRoleTransitionController.java",
  );
  const migration = read(
      "../backend/src/main/resources/db/migration/V45__reconcile_ceo_and_account_lifecycle.sql",
  );

  assert.match(
      controller,
      /@PostMapping\("\/ceo-succession"\)[\s\S]*hasRole\('SYSTEM_ADMIN'\)/,
  );
  assert.match(
      service,
      /@Transactional\s+public SuccessionResult succeedChiefExecutive/,
  );
  assert.match(
      service,
      /findGoverningRoleAccountsForUpdate/,
  );
  assert.match(
      service,
      /replaceFormerChiefExecutiveWithManager\(\)/,
  );
  assert.match(
      service,
      /successor\.appointChiefExecutive\(\)/,
  );
  assert.match(
      migration,
      /updated_by = 'flyway-v45-review'/,
  );
  assert.match(
      migration,
      /account_status = 'PENDING_APPROVAL'/,
  );
  assert.match(
      migration,
      /UPDATE iam_refresh_token_session/,
  );
});

test("CEO and Manager no longer inherit HR, Reception or Security mutation permissions", () => {
  const roles = read(
      "../backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java",
  );

  const ceo =
      roles.match(
          /ROLE_CEO\(EnumSet\.of\(([\s\S]*?)\)\),\s+ROLE_HR_ADMIN/,
      )?.[1] ?? "";

  const manager =
      roles.match(
          /ROLE_MANAGER\(EnumSet\.of\(([\s\S]*?)\)\),\s+ROLE_TEAM_LEAD/,
      )?.[1] ?? "";

  for (const permission of [
    "EMPLOYEE_CREATE",
    "EMPLOYEE_UPDATE",
    "EMPLOYEE_STATUS_CHANGE",
    "VISITOR_REGISTER",
    "VISITOR_VERIFY",
    "VISITOR_CHECK_IN",
    "VISITOR_CHECK_OUT",
    "QR_PASS_VERIFY",
  ]) {
    assert.doesNotMatch(
        ceo,
        new RegExp(`\\b${permission}\\b`),
    );
    assert.doesNotMatch(
        manager,
        new RegExp(`\\b${permission}\\b`),
    );
  }
});

test("archive and recovery reconcile the linked employee and leadership ownership", () => {
  const closure = read(
      "../backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java",
  );
  const transitions = read(
      "../backend/src/main/java/com/brainserve/appointment/iam/application/OperationalRoleTransitionService.java",
  );
  const migration = read(
      "../backend/src/main/resources/db/migration/V45__reconcile_ceo_and_account_lifecycle.sql",
  );

  assert.match(
      closure,
      /employees\.deactivateForAccountArchive\(target\.getEmployeeId\(\)\)/,
  );
  assert.match(
      transitions,
      /employees\.restoreAfterAccountRecovery/,
  );
  assert.match(
      migration,
      /UPDATE department_manager_assignment/,
  );
  assert.match(
      migration,
      /UPDATE department_hr_assignment/,
  );
  assert.match(
      migration,
      /UPDATE department_team_lead/,
  );
});

test("public visitor creation is idempotent and appointment cancellation is OTP protected", () => {
  const visitor = read(
      "../backend/src/main/java/com/brainserve/appointment/visitor/api/VisitorController.java",
  );
  const appointments = read(
      "../backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java",
  );
  const migration = read(
      "../backend/src/main/resources/db/migration/V45__reconcile_ceo_and_account_lifecycle.sql",
  );

  assert.match(
      visitor,
      /@RequestHeader\("Idempotency-Key"\)/,
  );
  assert.match(visitor, /findByIdempotencyKey/);
  assert.match(
      migration,
      /uk_visitor_idempotency_key/,
  );
  assert.match(appointments, /requestCancellationOtp/);
  assert.match(
      appointments,
      /cancellationOtpAttemptsKey/,
  );
  assert.match(
      appointments,
      /cancellationOtpCooldownKey/,
  );
  assert.match(appointments, /MessageDigest\.isEqual/);
});

test("browser tests cover explicit lock mode and backend-backed OTP cancellation", () => {
  const failClosed = read(
      "e2e/production-security.spec.ts",
  );
  const cancellation = read(
      "e2e-backend/appointment-cancellation.spec.ts",
  );

  assert.match(
      failClosed,
      /explicit lock mode fails closed when the backend URL is absent/,
  );
  assert.match(
      cancellation,
      /Cancellation code sent/,
  );
  assert.match(cancellation, /submittedOtp/);
});

