import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const api = readFileSync(
    new URL("../app/lib/api.ts", import.meta.url),
    "utf8",
);

const app = readFrontendSource();

const styles = readFileSync(
    new URL("../app/globals.css", import.meta.url),
    "utf8",
);

const service = readFileSync(
    new URL(
        "../backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java",
        import.meta.url,
    ),
    "utf8",
);

test(
    "appointment loading follows every page so new Security walk-ins reach Reception",
    () => {
      assert.match(
          api,
          /allSpringPageContent<ManagedAppointment>\("\/appointments\?sort=slotStart,asc"\)/,
      );
      assert.match(api, /page=\$\{page\}&size=\$\{pageSize\}/);
      assert.match(api, /cache:\s*"no-store"/);
      assert.doesNotMatch(api, /appointments\?size=100/);
    },
);

test(
    "Reception accepts only today's security queue while backend persists the handoff status",
    () => {
      assert.match(app, /item\.status === "Awaiting Reception"/);
      assert.match(
          app,
          /officeToday\(new Date\(item\.slotStart\)\) === officeToday\(\)/,
      );
      assert.match(
          service,
          /appointment\.recordSecurityIntake\(securityUserId/,
      );
      assert.match(service, /AppointmentEvents\.SecurityIntakeRecorded/);
      assert.match(service, /publishStatus\(appointment\)/);
    },
);

test(
    "shared Security and Reception walk-in modal keeps a scrollable form body",
    () => {
      assert.match(app, /className="modal glass-panel visit-modal"/);
      assert.match(app, /className="visit-modal-body"/);
      assert.match(
          styles,
          /\.modal\.visit-modal\s*\{[^}]*max-height:[^}]*overflow:\s*hidden/s,
      );
      assert.match(
          styles,
          /\.visit-modal-body\s*\{[^}]*overflow-y:\s*auto/s,
      );
      assert.match(styles, /\.visit-modal \.modal-actions\s*\{/);
    },
);
