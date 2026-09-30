import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "brainserve-recovery-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const bin = join(root, "bin"), calls = join(root, "calls");
  mkdirSync(bin);
  for (const name of ["pg_dump", "pg_restore", "createdb"]) {
    writeFileSync(join(bin, name), `#!/bin/sh\necho '${name}' >> "$CALLS"\n` +
      (name === "pg_dump" ? 'printf "synthetic-dump\\n"\nexit "${DUMP_STATUS:-0}"\n' :
        name === "createdb" ? 'exit "${CREATE_STATUS:-0}"\n' : 'exit "${RESTORE_STATUS:-0}"\n'), { mode: 0o700 });
  }
  const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, CALLS: calls };
  const run = (script, args, extra = {}) => spawnSync("sh", [resolve(`ops/postgres/${script}.sh`), ...args], { env: { ...env, ...extra }, encoding: "utf8" });
  const backup = join(root, "backup");
  return { root, calls, backup, run };
}
test("verified backup and isolated restore exercise all client commands", t => {
  const f = fixture(t);
  assert.match(f.run("backup-logical", [f.backup]).stdout, /LOGICAL_BACKUP_VERIFIED/);
  const restored = f.run("restore-logical", [f.backup, "brainserve_restore_test"]);
  assert.equal(restored.status, 0, restored.stderr);
  assert.match(restored.stdout, /ISOLATED_RESTORE_COMPLETED/);
  assert.equal(readFileSync(f.calls, "utf8").trim(), "pg_dump\npg_restore\npg_restore\ncreatedb\npg_restore");
});
test("failed dump cannot publish a checksum or finished dump", t => {
  const f = fixture(t);
  assert.notEqual(f.run("backup-logical", [f.backup], { DUMP_STATUS: "1" }).status, 0);
  assert.equal(existsSync(join(f.backup, "database.dump")), false);
  assert.equal(existsSync(join(f.backup, "SHA256SUMS")), false);
  assert.equal(existsSync(join(f.backup, "database.dump.partial")), false);
});
test("corruption stops before any database is created", t => {
  const f = fixture(t);
  assert.equal(f.run("backup-logical", [f.backup]).status, 0);
  writeFileSync(join(f.backup, "database.dump"), "corrupt");
  rmSync(f.calls);
  const result = f.run("restore-logical", [f.backup, "brainserve_restore_test"]);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /checksum mismatch/);
  assert.equal(existsSync(f.calls), false);
});
test("unsafe and live database names stop before client execution", t => {
  const f = fixture(t);
  for (const target of ["brainserve", "postgres", "brainserve_restore_", "brainserve_restore_--x", "brainserve_restore_'x", `brainserve_restore_${"a".repeat(64)}`]) {
    assert.notEqual(f.run("restore-logical", [f.backup, target]).status, 0, target);
  }
  assert.equal(existsSync(f.calls), false);
});
test("an existing database is never overwritten and restore errors propagate", t => {
  const f = fixture(t);
  assert.equal(f.run("backup-logical", [f.backup]).status, 0);
  assert.notEqual(f.run("restore-logical", [f.backup, "brainserve_restore_test"], { CREATE_STATUS: "1" }).status, 0);
  assert.notEqual(f.run("restore-logical", [f.backup, "brainserve_restore_test"], { RESTORE_STATUS: "1" }).status, 0);
  assert.notEqual(f.run("backup-logical", [f.backup]).status, 0);
});
