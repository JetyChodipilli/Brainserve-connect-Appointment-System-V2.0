import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import test from "node:test";

function uniqueVersions(names) {
  const seen = new Set();
  for (const name of names) {
    const version = name.match(/^V(\d+)__/)?.[1];
    if (!version) continue;
    assert.ok(!seen.has(version), `Duplicate Flyway version ${version}: ${name}`);
    seen.add(version);
  }
  assert.ok(seen.size > 0, "No versioned migrations found");
}
test("migration uniqueness check rejects a known duplicate", () => {
  assert.throws(() => uniqueVersions(["V50__a.sql", "V50__b.sql"]), /Duplicate Flyway version 50/);
});
test("all repository Flyway migrations have unique versions", () => {
  uniqueVersions(readdirSync("backend/src/main/resources/db/migration"));
});
