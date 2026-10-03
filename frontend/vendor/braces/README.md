# Temporary guarded braces fork

This private MIT-licensed copy of `braces@3.0.3` applies the reviewed source patch
from [upstream PR 72](https://github.com/micromatch/braces/pull/72), pinned to
`d0d575e55e74a4e0218e5248fafb79efc3e54ebb`.

The upstream advisory [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)
has no patched npm release as of 2026-10-03. Parser and compile/expand/stringify
walkers now reject nesting beyond 100 levels, including direct AST input.
Callers may request a stricter bound but cannot raise that ceiling.

The root dependency and `$braces` override route every consumer to this actual
patched code. Its private package identity distinguishes the fork from an
unmodified registry release. npm audit cannot independently assess this private
copy; `tests/brace-security.test.mjs` verifies the guard and its installed
transitive resolution. No audit exclusions or severity threshold changes are used.

Original runtime files and license are retained. `lib/compile.js`, `constants.js`,
`expand.js`, `parse.js` and `stringify.js` carry the upstream depth changes;
`package.json` is private fork metadata. Existing `fill-range` is pinned to 7.1.1.
Docker copies the source before installation and into the final image because
the npm link must resolve at runtime.

Remove this override and fork when a released upstream fix passes the same
regressions and the normal frontend build, lint, browser and audit checks.
