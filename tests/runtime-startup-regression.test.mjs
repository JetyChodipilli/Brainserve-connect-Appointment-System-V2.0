import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("the verified build cannot bypass TypeScript validation", () => {
  const build = read("scripts/build-verified.sh");
  assert.match(build, /typescript=.*node_modules\/\.bin\/tsc/);
  assert.match(build, /"\$\{typescript\}" --noEmit/);
  assert.match(build, /bash "\$\{script_dir\}\/validate-artifact\.sh"/);
  assert.match(read("scripts/install-ci.sh"), /exec bash "\$\{script_dir\}\/sites-env\.sh" -- bash "\$0"/);
  assert.match(read("scripts/validate-artifact.sh"), /exec bash "\$\{script_dir\}\/sites-env\.sh" -- bash "\$0"/);
});

test("V45 uses only columns present in the canonical account schema", () => {
  const migration = read("backend/src/main/resources/db/migration/V45__reconcile_ceo_and_account_lifecycle.sql");
  assert.doesNotMatch(migration, /\brejection_reason\b/);
  assert.match(migration, /rejected_by_user_id = null/);
});

test("presigned object links use a browser-reachable endpoint", () => {
  const storage = read("backend/src/main/java/com/brainserve/appointment/document/infrastructure/ObjectStorageConfiguration.java");
  const compose = read("docker-compose.yml");
  assert.match(storage, /@Value\("\$\{aws\.s3\.public-endpoint\}"\) URI publicEndpoint/);
  assert.match(storage, /S3Presigner\.builder\(\)\.endpointOverride\(publicEndpoint\)/);
  assert.match(compose, /S3_PUBLIC_ENDPOINT: "\$\{S3_PUBLIC_ENDPOINT:-http:\/\/localhost:9000\}"/);
});

test("Docker builds exclude local secret files and accept a configurable public API URL", () => {
  const dockerIgnore = read(".dockerignore");
  const backendDockerIgnore = read("backend/.dockerignore");
  const compose = read("docker-compose.yml");
  assert.match(dockerIgnore, /^\.env\*$/m);
  assert.match(backendDockerIgnore, /^src\/main\/resources\/application-local\.properties$/m);
  assert.match(compose, /NEXT_PUBLIC_API_BASE_URL: "\$\{NEXT_PUBLIC_API_BASE_URL:-http:\/\/localhost:8080\/api\/v1\}"/);
});

test("ClamAV starts with signatures and preserves database updates", () => {
  const compose = read("docker-compose.yml");
  assert.match(compose, /image: clamav\/clamav:1\.4\b/);
  assert.doesNotMatch(compose, /image: clamav\/clamav:[^\n]*_base/);
  assert.match(compose, /clamav_data:\/var\/lib\/clamav/);
});
