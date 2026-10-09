# Gates: Sprint 15 implementation

Scope: Keyboard accessibility, protected monitoring, bounded synthetic load measurements and timed compatible-release recovery. Customer capacity and supervised acceptance remain release gates documented in docs/SPRINT_15.md.

- [x] G5: Production frontend build succeeds
  CHECK: npm run build
  EXPECT: built in
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=2cd968a1268d16cbbd7130efee5cda372ac20f4ab5d2df9f2067149f8c58c3a7; exit=0; EXPECT=matched; output-sha256=cbe8c233f3520d67e8604e6c9410006eafecb6b1476519dbc07ec2ac72ed1b05; output-bytes=18249; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=1aa3a2276e26/13 entries

- [x] G1: Frontend types and lint accept the accessibility changes
  CHECK: npm run typecheck && npm run lint
  EXPECT: eslint
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=ccb99f8d5a8e553dfc69bea0238417d558ce76474af9f4100cfea5841bd67a03; exit=0; EXPECT=matched; output-sha256=37cf85c381ffb08861b7903ed875c013e779d603cfdc412fdbe760204a58d54e; output-bytes=1154; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=1aa3a2276e26/13 entries

- [x] G2: Existing frontend behavior passes its source regression suite
  CHECK: npm run test:regression
  EXPECT: fail 0
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=c96971679be28b4e98c62569104f4c5022cbb8248bdb6b4b30bafea3f1d09668; exit=0; EXPECT=matched; output-sha256=52992b398524f20cff19cdfca90b6584da14daebd1dcc83fefb5f942526f4df4; output-bytes=38047; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=1aa3a2276e26/13 entries

- [x] G3: Keyboard and accessibility browser scenarios pass without retries
  CHECK: npx playwright test -c playwright.backend.config.ts sprint15-accessibility --retries=0
  EXPECT: 16 passed
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=0fd527384bb525dfb0a61e9aa873ae693dc5f61e7bfb824c6ed28451d5d70cf6; exit=0; EXPECT=matched; output-sha256=516d8dc0e5c92a8dfcb1dd330366b9f3f3642787f0027d9c4262acaa6c145d8f; output-bytes=4532; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=1aa3a2276e26/13 entries

- [x] G4: Load accounting, bounded monitoring responses and recovery rejection checks pass
  CHECK: node --test --test-isolation=none scripts/tests/*.test.mjs
  EXPECT: fail 0
  EVIDENCE: automatic-evidence=v1; definition-sha256=95f123f3cb3a2c6cb8f80980c57d042786e92cbc1a6f30552ada1ba8fa8295a4; exit=0; EXPECT=matched; output-sha256=59736f5ccbe6dc81688e388d31c4577f4e46b28e421e0350d48931b15902434a; output-bytes=1525; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve; path=1aa3a2276e26/13 entries

- [ ] G6: Published head passes Frontend, Backend and Staging & recovery CI, including measured load and timed recovery artifacts
  EVIDENCE: pending

- [x] G7: Final diff preserves role, data and credential boundaries and accurately documents outstanding acceptance
  EVIDENCE: Native Testing, Maintainability, Security, Performance, Design and adversarial re-reviews returned no remaining concrete findings after repairs; Simplification returned no findings. Reviewed synthetic-only credentials, restricted Actuator, aggregate artifacts, retained authenticated recovery reads and explicit customer/provider/hardware acceptance exclusions. gstack launcher and outside-provider coverage unavailable; no full gstack certificate claimed. Security re-review of bounded streaming metrics repair returned NO FINDINGS. CI selector repair re-reviewed by Testing with NO FINDINGS; all 22 affected/neighbor browser scenarios pass without retries, types/lint and diff checks pass. git diff --check and operational syntax checks passed.
