# Acceptance evidence recording gates

Scope: add a safe operator command for recording actual results for the existing acceptance gates. It does not run a pilot, authenticate a signature, appoint an owner or approve a release. Real acceptance execution remains pending without a dedicated environment, two pilot representatives and operations/support/approval owners. Security, functional, structural and scale audits follow the acceptance work, as requested.

Runtime validation uses the existing GitHub CI workflow. Local runtime/probe commands previously rejected for unexplained Cloudflare access are not retried or bypassed. The following gates use manual assessment of retrieved CI and source-review evidence; final receipts belong to this change's PR.

- [ ] E1: All fifteen existing gate IDs accept declared valid evidence for the matching candidate, while unrelated gates, owners and approval remain unchanged.
  EVIDENCE: pending final operational CI and actual CLI output checks.
- [ ] E2: Malformed, oversized, nonregular, unknown-gate, inconsistent and wrong-candidate inputs fail without creating an output; approved records cannot be edited.
  EVIDENCE: pending boundary tests and source review.
- [ ] E3: Output is a new private file and never overwrites an existing record or output symlink; original input bytes remain intact and no private evidence appears in logs.
  EVIDENCE: pending actual CLI permission/no-overwrite/log checks.
- [ ] E4: Documentation distinguishes recording supplied attestations from performing the fourteen pending customer/operator gates.
  EVIDENCE: pending final review of command/runbook and missing-input handoff.
- [ ] E5: Reviewed candidate and actual merged main pass all four existing CI jobs; their source trees match.
  EVIDENCE: pending retrieved final candidate/main workflow and source-tree results.

Real gate completion is a separate required handoff: dedicated staging and recovery environments, named operations/support/approval owners, two distinct pilots, measured results, selected live-provider/device access or reviewed disabled-feature exclusions, and authentic customer/reviewer sign-offs. No human gate is marked passed by this implementation.
