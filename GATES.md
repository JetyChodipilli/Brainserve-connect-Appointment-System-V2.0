# Gates: Sprint 16 release preparation implementation

Scope: Protected manual subscription/support metadata, a System Admin release screen, compatibility and commercial handoff documents, and a release-acceptance record that exposes missing customer evidence. Customer pilot/UAT and production approval remain external release gates; no customer signoff is fabricated.

Verification source: the existing BrainServe CI workflow and its synthetic screenshots/recovery artifacts, bound to the published implementation revision. Automatic approval review blocked local gate execution for unexplained Cloudflare network access, including an attempt limited to type/lint/regression/acceptance checks. Those local reruns are not credited. Closing receipts for this ledger are recorded on Sprint 16 PR #39, so publishing a receipt does not substitute for verification of the implementation it identifies.

- [ ] G1: Frontend types and lint accept the release screen and contracts
  EVIDENCE: pending; published-head CI Type-check and Run lint steps must both succeed.

- [ ] G2: Existing frontend behavior passes its regression suite
  EVIDENCE: pending; published-head CI source/regression suite must pass all 400 tests with zero failures.

- [ ] G3: Release forms preserve keyboard, recovery and account boundaries in browser scenarios
  EVIDENCE: pending; require published-head CI browser success without flaky outcomes, all 22 Sprint 16 cases, and visual review of its synthetic narrow/tablet/desktop and recovery screenshots. Local re-execution was blocked by automatic approval review over unexplained Cloudflare network access; the existing CI run supplies the browser evidence instead.

- [ ] G4: Acceptance validation rejects missing evidence and inconsistent approval records
  EVIDENCE: pending; published-head CI operational tests must pass all eight release-acceptance tests within the 24-test operational suite, including invalid/oversized/approval-negative cases.

- [ ] G5: Production frontend build succeeds in the Docker application's local backend mode
  EVIDENCE: pending; require published-head CI frontend build success and the staging application's successful Docker build in BRAINSERVE_LOCAL_BACKEND=1 mode. Automatic approval review blocked local build re-execution for unexplained Cloudflare network access; only these existing CI results may close this gate.

- [ ] G6: Published implementation passes all CI jobs with real database metadata, authorization and restore coverage
  EVIDENCE: pending

- [ ] G7: Final review verifies commercial metadata cannot grant access and handoff documents expose customer acceptance gaps
  EVIDENCE: pending
