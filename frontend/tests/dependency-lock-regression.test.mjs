import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const packageJson = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
const lockfile = JSON.parse(readFileSync(new URL("../package-lock.json", import.meta.url), "utf8"));

test("audited runtime pins are represented by the committed npm lockfile", () => {
    const directPins = {
        "@cloudflare/vite-plugin": "1.54.9",
        next: "16.3.8",
        vinext: "1.0.0-beta.13",
        "@vitejs/plugin-rsc": "0.5.35",
        wrangler: "4.131.2",
    };
    for (const [name, version] of Object.entries(directPins)) {
        assert.equal(packageJson.dependencies[name], version, `${name} manifest pin`);
        assert.equal(lockfile.packages[""].dependencies[name], version, `${name} root lock pin`);
        assert.equal(lockfile.packages[`node_modules/${name}`].version, version, `${name} lock entry`);
    }
    assert.equal(lockfile.packages["node_modules/js-yaml"].version, packageJson.overrides["js-yaml"]);
    assert.equal(lockfile.packages["node_modules/sharp"].version, packageJson.overrides.sharp);
    assert.equal(lockfile.packages["node_modules/miniflare"].version, "5.20260911.1-alpha");
    assert.equal(lockfile.packages["node_modules/workerd"].version, "1.20260911.1");
});
