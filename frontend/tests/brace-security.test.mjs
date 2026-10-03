import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const braces = require("braces");
const guarded = (error) => /exceeds max depth/.test(error.message) && !/Maximum call stack/.test(error.message);

test("every installed micromatch consumer resolves the guarded private source", () => {
  assert.equal(require("braces/package.json").name, "@brainserve/braces");
  const transitive = createRequire(require.resolve("micromatch"));
  assert.equal(transitive("braces"), braces);
  assert.equal(transitive("braces/package.json").version, "3.0.3-brainserve.1");
});

test("ordinary brace patterns retain compile, expand and stringify behavior", () => {
  assert.deepEqual(braces("src/{api,ui}/*.ts", { expand: true }), ["src/api/*.ts", "src/ui/*.ts"]);
  assert.equal(braces.compile("x/{a,b}"), "x/(a|b)");
  assert.deepEqual(braces.expand("{1..3}"), ["1", "2", "3"]);
  assert.equal(braces.stringify(braces.parse("x/{a,b}")), "x/{a,b}");
});

test("all string entry points reject exploit-size brace and parenthesis nesting", () => {
  for (const [open, close] of [["{", "}"], ["(", ")"]]) {
    const pattern = open.repeat(4_000) + "x" + close.repeat(4_000);
    for (const operation of [braces, braces.create, braces.parse, braces.compile, braces.expand, braces.stringify]) {
      assert.throws(() => operation(pattern), guarded);
      assert.throws(() => operation(pattern, { maxDepth: Infinity }), guarded);
      assert.throws(() => operation(pattern, { maxDepth: 100_000 }), guarded);
    }
  }
});

test("safe boundary remains accepted and stricter caller limits are enforced", () => {
  const pattern = "{".repeat(100) + "x" + "}".repeat(100);
  for (const operation of [braces.parse, braces.compile, braces.expand, braces.stringify]) {
    assert.doesNotThrow(() => operation(pattern));
    assert.throws(() => operation(pattern, { maxDepth: 10 }), guarded);
  }
});

test("caller-provided ASTs cannot bypass depth guards", () => {
  let node = { type: "text", value: "x" };
  for (let depth = 0; depth < 2_000; depth++) {
    const parent = { type: "paren", nodes: [node] };
    node.parent = parent;
    node = parent;
  }
  const root = { type: "root", nodes: [node] };
  node.parent = root;
  for (const operation of [braces.compile, braces.expand, braces.stringify]) {
    assert.throws(() => operation(root), guarded);
    assert.throws(() => operation(root, { maxDepth: 100_000 }), guarded);
  }
});
