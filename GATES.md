# Gates: Sprint 16 release preparation implementation

Scope: Protected manual subscription/support metadata, a System Admin release screen, compatibility and commercial handoff documents, and a release-acceptance record that exposes missing customer evidence. Customer pilot/UAT and production approval remain external release gates; no customer signoff is fabricated.

- [x] G1: Frontend types and lint accept the release screen and contracts
  CHECK: npm run typecheck && npm run lint
  EXPECT: eslint
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=ccb99f8d5a8e553dfc69bea0238417d558ce76474af9f4100cfea5841bd67a03; exit=0; EXPECT=matched; output-sha256=37cf85c381ffb08861b7903ed875c013e779d603cfdc412fdbe760204a58d54e; output-bytes=1154; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=cad8d573c828/13 entries

- [x] G2: Existing frontend behavior passes its regression suite
  CHECK: npm run test:regression
  EXPECT: fail 0
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=c96971679be28b4e98c62569104f4c5022cbb8248bdb6b4b30bafea3f1d09668; exit=0; EXPECT=matched; output-sha256=6315e5342e6c6cc274cadb118d539aee8e06480a47d75d7de2d12db604137a74; output-bytes=38242; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=cad8d573c828/13 entries

- [x] G3: Release forms preserve keyboard, recovery and account boundaries in browser scenarios
  CHECK: npx playwright test -c playwright.backend.config.ts sprint16-release --retries=0
  EXPECT: /[1-9][0-9]* passed \(/
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=6a0aef4f384405102008d2cdfc6939994d330de9334551c76f20135c54904b44; exit=0; EXPECT=matched; output-sha256=6f2d6e073de54d3cd9980f9e4e1e2b7a79c0b969b838e33554e254a8a7f71927; output-bytes=5081; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=cad8d573c828/13 entries

- [x] G4: Acceptance validation rejects missing evidence and inconsistent approval records
  CHECK: node --test --test-isolation=none scripts/tests/release-acceptance.test.mjs
  EXPECT: fail 0
  EVIDENCE: automatic-evidence=v1; definition-sha256=48acc6e6c1ff2d48bac946cfd62d9176c7098f1360a8ca3bf2703dfbe3e1d340; exit=0; EXPECT=matched; output-sha256=586d5baa3e1ede552dc4a46d01c27b6f94d0251a1f7229a5cd28a24f0968d50c; output-bytes=891; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve; path=cad8d573c828/13 entries

- [x] G5: Production frontend build succeeds
  CHECK: npm run build
  EXPECT: built in
  CWD: frontend
  EVIDENCE: automatic-evidence=v1; definition-sha256=2cd968a1268d16cbbd7130efee5cda372ac20f4ab5d2df9f2067149f8c58c3a7; exit=0; EXPECT=matched; output-sha256=cbf7d14f6acd9b6e945a453deaed1a6c6d9f7f51b3c002dea1f4ac89bbc00c26; output-bytes=18249; shell=/bin/bash; cwd=/workspace/scratch/238df2ad9068/brainserve/frontend; path=cad8d573c828/13 entries

- [ ] G6: Published implementation passes all CI jobs with real database metadata, authorization and restore coverage
  EVIDENCE: pending

- [ ] G7: Final review verifies commercial metadata cannot grant access and handoff documents expose customer acceptance gaps
  EVIDENCE: pending
