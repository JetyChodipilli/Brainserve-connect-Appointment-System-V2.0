import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) =>
    readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const properties = read(
    "backend/src/main/resources/application.properties",
);
const backendEnvExample = read("backend/.env.example");
const application = read(
    "backend/src/main/java/com/brainserve/appointment/BrainServeAppointmentApplication.java",
);
const asyncConfig = read(
    "backend/src/main/java/com/brainserve/appointment/notification/application/NotificationAsyncConfiguration.java",
);
const listener = read(
    "backend/src/main/java/com/brainserve/appointment/notification/application/SecurityArrivalNotificationListener.java",
);
const notifications = read(
    "backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java",
);
const authentication = read(
    "backend/src/main/java/com/brainserve/appointment/iam/application/AuthenticationService.java",
);
const appointments = read(
    "backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java",
);
const eventContract = read(
    "backend/src/main/java/com/brainserve/appointment/appointment/api/AppointmentEvents.java",
);
const leaveController = read(
    "backend/src/main/java/com/brainserve/appointment/employee/api/LeaveRequestController.java",
);
const indexes = read(
    "backend/src/main/resources/db/migration/V15__production_operational_indexes.sql",
);

test("production configuration never commits the bootstrap admin password", () => {
  assert.ok(properties.includes("SYSTEM_ADMIN_DEFAULT_PASSWORD:"));
  assert.equal(properties.includes("SystemAdmin@BrainServe#06"), false);

  assert.match(
      backendEnvExample,
      /^SYSTEM_ADMIN_DEFAULT_PASSWORD=\s*$/m,
  );

  assert.equal(
      backendEnvExample.includes("SystemAdmin@BrainServe#06"),
      false,
  );
});

test("transactional visitor and leave events run on the bounded notification executor", () => {
  assert.ok(application.includes("@EnableAsync"));
  assert.ok(asyncConfig.includes("notificationExecutor"));
  assert.ok(asyncConfig.includes("setQueueCapacity(250)"));
  assert.ok(listener.includes('@Async("notificationExecutor")'));
});

test("automatic Kafka notification text is bounded to its database column", () => {
  assert.ok(notifications.includes("normalized.length() <= 500"));
  assert.ok(notifications.includes("normalized.substring(0, 497)"));
  assert.ok(notifications.includes("systemMessage(message)"));
});

test("locked accounts cannot mint access tokens through refresh", () => {
  assert.ok(
      authentication.includes(".filter(account -> !account.isLocked())"),
  );
});

test("approved Employee accounts cannot authenticate before HR assigns an employee profile", () => {
  assert.ok(
      authentication.includes("EMPLOYEE_PROFILE_ASSIGNMENT_REQUIRED"),
  );

  assert.ok(authentication.includes("SystemRole.ROLE_EMPLOYEE"));
  assert.ok(authentication.includes("user.getEmployeeId() == null"));

  assert.ok(
      authentication.match(/requireOperationalProfile\(user\)/g)?.length >= 2,
      "login and refresh must enforce employee profile assignment",
  );
});

test("Reception forwarding carries and targets the selected host", () => {
  assert.ok(eventContract.includes("UUID hostEmployeeId"));
  assert.ok(eventContract.includes("String appointmentType"));
  assert.ok(
      notifications.includes("activeByEmployeeId(hostEmployeeId)"),
  );
});

test("special visits cannot check in until Reception forwards them", () => {
  assert.ok(appointments.includes("requiresCabinForward"));

  assert.ok(
      appointments.includes(
          "appointment.getReceptionForwardedAt() != null",
      ),
  );
});

test("invalid leave decisions produce a client-safe business error", () => {
  assert.ok(
      leaveController.includes('"INVALID_LEAVE_DECISION"'),
  );

  assert.ok(
      leaveController.includes("HttpStatus.UNPROCESSABLE_ENTITY"),
  );
});

test("operational visitor and notification queries have supporting indexes", () => {
  assert.ok(indexes.includes("reception_verified_at"));
  assert.ok(indexes.includes("delivery_status, sent_at"));
});