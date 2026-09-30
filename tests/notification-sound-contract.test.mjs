import { readFrontendSource } from "./frontend-source.mjs";
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const app = readFrontendSource();
const sounds = fs.readFileSync("app/lib/notification-sounds.ts", "utf8");

test("notification sounds cover messages and operational workflow changes", () => {
  for (const kind of ["message", "visitor", "appointment", "approval", "action"]) {
    assert.match(sounds, new RegExp(`${kind}:`));
  }
  assert.match(app, /unread > previousUnread/);
  assert.match(app, /playNotificationSound\("visitor"\)/);
  assert.match(app, /playNotificationSound\("approval"\)/);
  assert.match(app, /playNotificationSound\("appointment"\)/);
});

test("sound preference is persistent and user controllable", () => {
  assert.match(sounds, /localStorage\.setItem\(SOUND_ENABLED_KEY/);
  assert.match(app, /Notification sounds/);
  assert.match(app, /role="menuitemcheckbox"/);
});

