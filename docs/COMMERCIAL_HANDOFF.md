# Commercial and support handoff

V2.0 uses a manual agreement and renewal process for one company per deployment. Prices, customer terms, support coverage and production approval have not been supplied. Fill this handoff with the customer and responsible operator before offering the release. The Release and support screen records agreed metadata; it does not collect payments, issue invoices or enforce commercial suspension.

## Quote and offering record

| Line | Record separately |
| --- | --- |
| Setup | Deployment/configuration/import scope, environment, training, acceptance and one-time price |
| Subscription | Agreement reference, covered company/deployment, included core/optional features, term dates and renewal price |
| Support | Named owner/contact, days/hours/time zone, severity response targets, escalation, maintenance windows and support price |
| External vendors | Hosting, domains, email, storage/backups, calendar/messaging accounts and hardware; customer/provider responsibility and separate charges |
| Limits | Measured supported workload/topology, retention, capacity/provider/device exclusions and fallback procedures |
| Exit | Notice/approval owner, authorized export scope, retention/hold/backup handling, connection/device revocation and handoff responsibilities |

Use the measured capacity/recovery report and agreed feature list. Do not advertise unmeasured SLA/RPO/RTO, universal printer support, multi-tenant SaaS, live provider success from simulators or WCAG certification from automated rules. Commercial metadata cannot grant roles, enable provider configuration or prevent security/visitor operations. Record term/renewal changes through fresh-MFA System Admin controls and reconcile disagreements against the original agreement.

## Support acceptance

Name the operations owner and backup contact, the support owner/contact, and who can approve provider/device changes. Agree severity definitions and coverage hours. Record acknowledgement and escalation targets, an out-of-hours/manual path and maintenance communication. Run an alert-delivery/escalation test with real named recipients and evidence; this document does not send notifications or establish an on-call team.

An incident record includes UTC time, immutable release SHA, environment, affected role/workflow, redacted reproduction, business impact and the support reference. Use **Support diagnostics** to preview the allowlisted fields before generating a bounded package. Review the package privately; do not request passwords, MFA/recovery codes, bearer tokens, provider secrets, documents or raw dumps in tickets. The existing [retention runbook](../ops/DATA_RETENTION_RUNBOOK.md), [PITR runbook](../ops/postgres/PITR_RUNBOOK.md) and [staging procedure](../ops/staging/README.md) own secured recovery steps.

For outages, preserve committed evidence and durable job IDs. Reconnect and reload current state before repeating a mutation; inspect uncertain provider delivery before an acknowledged manual retry. Agree who can authorize rollback, preserve the full secured backup/key/object bundle and prove the chosen previous release against an isolated restore. The short disposable rehearsal does not establish customer recovery targets.

## Cancellation, export and offboarding rehearsal

1. Confirm the request, authorized customer contact, required exports, contractual retention/holds, provider ownership and a dated handoff plan. Commercial `CANCELLED` status records that process; account/permission controls continue to determine access.
2. Use existing authorized reports, export jobs and governed data-lifecycle tools. Verify the exported scope, completeness and readable files with the customer. Protect the handoff and record its checksum/reference; avoid a raw all-data export through the commercial metadata API.
3. Agree a cutoff, stop new business changes under the operational process, reconcile pending approval/export/integration jobs and capture final backups, private objects, archives and required key versions. Preserve audit/retention obligations and any holds.
4. Revoke Google/Slack connections through the existing lifecycle and inspect remote revocation failures. Revoke visitor devices and staff sessions/accounts through their governed authorization flows. Transfer ownership of any remaining provider or infrastructure resources explicitly.
5. Record customer receipt, outstanding vendor/retention work, responsible owners and dates. Follow governed disposal when permitted, including backup expiry. Do not promise instant erasure of retained backups or delete migration/audit evidence to close a ticket.
6. Rehearse this sequence on a separate secured environment, including interrupted export/revocation and recovery. Reference reviewer signoff in the `offboarding` gate. Production execution requires the customer's actual authorized request; this sprint prepares the process.
