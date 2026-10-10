# Gates: Sprint 16 release preparation implementation

Scope: Protected manual subscription/support metadata, a System Admin release screen, compatibility and commercial handoff documents, and a release-acceptance record that exposes missing customer evidence. Customer pilot/UAT and production approval remain external release gates; no customer signoff is fabricated.

- [x] G1: Frontend types and lint accept the release screen and contracts
  CHECK: npm run typecheck && npm run lint
  EXPECT: eslint
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=ccb99f8d5a8e553dfc69bea0238417d558ce76474af9f4100cfea5841bd67a03; exit=0; EXPECT=matched; output-sha256=37cf85c381ffb08861b7903ed875c013e779d603cfdc412fdbe760204a58d54e; output-bytes=1154; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=299cbebcc869/13 entries

- [x] G2: Existing frontend behavior passes its regression suite
  CHECK: npm run test:regression
  EXPECT: fail 0
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=c96971679be28b4e98c62569104f4c5022cbb8248bdb6b4b30bafea3f1d09668; exit=0; EXPECT=matched; output-sha256=301db7d3ee1532b0d8e0a0af77bc875b71059871741bc237a9af2bfaf12e57b0; output-bytes=38243; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=299cbebcc869/13 entries

- [x] G3: Release forms preserve keyboard, recovery and account boundaries in browser scenarios
  CHECK: npx playwright test -c playwright.backend.config.ts sprint16-release --retries=0
  EXPECT: /[1-9][0-9]* passed \(/
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=6a0aef4f384405102008d2cdfc6939994d330de9334551c76f20135c54904b44; exit=0; EXPECT=matched; output-sha256=49acf49bbadfce0298e8504a453ce4c428a3664cb4d55c598386351d197c073d; output-bytes=5256; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=299cbebcc869/13 entries

- [x] G4: Acceptance validation rejects missing evidence and inconsistent approval records
  CHECK: node --test --test-isolation=none scripts/tests/release-acceptance.test.mjs
  EXPECT: fail 0
  EVIDENCE: automatic-evidence=v1; definition-sha256=48acc6e6c1ff2d48bac946cfd62d9176c7098f1360a8ca3bf2703dfbe3e1d340; exit=0; EXPECT=matched; output-sha256=f8243cfb1debef2b23a370cb9919881a4730b7a85d6c936e8fd6cdcc40d4c68f; output-bytes=891; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve; path=299cbebcc869/13 entries

- [x] G5: Production frontend build succeeds
  CHECK: npm run build
  EXPECT: built in
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=2cd968a1268d16cbbd7130efee5cda372ac20f4ab5d2df9f2067149f8c58c3a7; exit=0; EXPECT=matched; output-sha256=6cb31cc1a2f6428c598be9071ec00699e114a36a909ae027fb72765f9459bf33; output-bytes=18249; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=299cbebcc869/13 entries

- [ ] G6: Published implementation passes all CI jobs with real database metadata, authorization and restore coverage
  EVIDENCE: pending

- [ ] G7: Final review verifies commercial metadata cannot grant access and handoff documents expose customer acceptance gaps
  EVIDENCE: pending
