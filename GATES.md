# Gates: V2.0 engineering review and acceptance preparation

Scope: finish the engineering review, repair proven OpenAPI defects, generate a candidate-bound acceptance packet, and publish/merge only after CI succeeds. The user selected release-candidate preparation with operations/support owners not yet appointed. Real customer/operator approvals remain pending inputs; they are not software completion claims.

Verification uses the existing GitHub Actions workflow and its candidate-bound artifacts. Prior local runtime/gate attempts were rejected by automatic approval review for unexplained Cloudflare connections, including narrowed static checks. Local reruns are not credited or retried. All gates below are manual assessment of retrieved CI/reviewer evidence; no runnable oracle was replaced with a handwritten automatic receipt. Closing receipts are recorded on the acceptance-closure PR with the exact tested head and merged-main revision.

- [ ] G1: Generated OpenAPI matches the required nullable date fields and supported JSON body media types
  EVIDENCE: pending; test-first CI must reproduce the incorrect contract before repair, followed by successful real PostgreSQL API-documentation tests.

- [ ] G2: Acceptance packet generation preserves pending approval and rejects invalid or destructive preparation
  EVIDENCE: pending; the operational CI suite must verify candidate binding, pending human gates/owners, private output permissions, refusal to overwrite a packet, malformed candidate and incomplete/failed CI inputs.

- [ ] G3: CI publishes engineering evidence only after all required predecessor jobs succeed
  EVIDENCE: pending; review the needs graph and require the Release acceptance packet job to succeed with a SHA-bound artifact containing three successful predecessor results. Customer approval must remain pending.

- [ ] G4: Native final review covers Sprint 16 and the acceptance follow-up
  EVIDENCE: pending; all eight applicable specialists, the red team, the parent critical pass and the native adversarial pass must finish on the final source candidate. Record actual coverage, fixed findings and unavailable logger/outside-model tooling without fabricated certification.

- [ ] G5: The published follow-up and merged main pass the complete CI workflow
  EVIDENCE: pending; Backend, Frontend, Staging & recovery and Release acceptance packet jobs must succeed, including required real PostgreSQL/Redis coverage. Reconfirm immutable main SHA after merge.

- [ ] G6: Pilot, operations and support handoff is actionable and honestly incomplete
  EVIDENCE: pending; the generated candidate packet must cover all fifteen existing customer gates, two distinct pilots, measurements, operations/support/approval ownership, private evidence and final acceptance commands. No sign-off, named owner, provider/hardware success or customer capacity/recovery approval is invented.
