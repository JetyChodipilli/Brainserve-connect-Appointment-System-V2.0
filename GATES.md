# Gates: Sprint 16 release preparation implementation

Scope: Protected manual subscription/support metadata, a System Admin release screen, compatibility and commercial handoff documents, and a release-acceptance record that exposes missing customer evidence. Customer pilot/UAT and production approval remain external release gates; no customer signoff is fabricated.

- [x] G1: Frontend types and lint accept the release screen and contracts
  CHECK: npm run typecheck && npm run lint
  EXPECT: eslint
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=ccb99f8d5a8e553dfc69bea0238417d558ce76474af9f4100cfea5841bd67a03; exit=0; EXPECT=matched; output-sha256=37cf85c381ffb08861b7903ed875c013e779d603cfdc412fdbe760204a58d54e; output-bytes=1154; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=f97ea638ccf4/13 entries

- [x] G2: Existing frontend behavior passes its regression suite
  CHECK: npm run test:regression
  EXPECT: fail 0
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=c96971679be28b4e98c62569104f4c5022cbb8248bdb6b4b30bafea3f1d09668; exit=0; EXPECT=matched; output-sha256=a59a1a7ee440afbb3a8bc638d3440d2f7227d521a99cc91a143b5d523aa07470; output-bytes=38245; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=f97ea638ccf4/13 entries

- [ ] G3: Release forms preserve keyboard, recovery and account boundaries in browser scenarios
  CHECK: npx playwright test -c playwright.backend.config.ts sprint16-release --retries=0
  EXPECT: /[1-9][0-9]* passed \(/
  CWD: frontend
  EVIDENCE: pending; local re-execution was blocked by automatic approval review over unexplained Cloudflare network access. Verify the published CI browser results and retain this local coverage limitation.

- [x] G4: Acceptance validation rejects missing evidence and inconsistent approval records
  CHECK: node --test --test-isolation=none scripts/tests/release-acceptance.test.mjs
  EXPECT: fail 0
  EVIDENCE: automatic-evidence=v1; definition-sha256=48acc6e6c1ff2d48bac946cfd62d9176c7098f1360a8ca3bf2703dfbe3e1d340; exit=0; EXPECT=matched; output-sha256=691074b8ff02632d5d500deea36bb23f5d441c411aaf4e4d43cc0a508fb7fd8d; output-bytes=891; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve; path=f97ea638ccf4/13 entries

- [ ] G5: Production frontend build succeeds in the Docker application's local backend mode
  CHECK: BRAINSERVE_LOCAL_BACKEND=1 npm run build
  EXPECT: built in
  CWD: frontend
  EVIDENCE: pending; local re-execution was blocked by automatic approval review over unexplained Cloudflare network access. Verify the published CI and staging build results and retain this local coverage limitation.

- [ ] G6: Published implementation passes all CI jobs with real database metadata, authorization and restore coverage
  EVIDENCE: pending

- [ ] G7: Final review verifies commercial metadata cannot grant access and handoff documents expose customer acceptance gaps
  EVIDENCE: pending
