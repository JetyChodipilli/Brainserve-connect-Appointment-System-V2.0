# BrainServe Connect frontend architecture

This refactor prepares the existing application for development in the V2.0 repository. It separates responsibilities while preserving the current screens, role permissions, workflows, API requests, storage keys, and visual presentation. It does not introduce new product features or declare the complete V2.0 feature release finished.

Baseline: original repository main commit [`d27eac1106d114d36f6fd40be8074b55821bca8e`](https://github.com/JetyChodipilli/Brainserve-connect-Appointment-System/commit/d27eac1106d114d36f6fd40be8074b55821bca8e), reviewed on 30 September 2026.

## Folder map

| Location | Responsibility |
| --- | --- |
| `app/page.tsx` | Route entry, preview configuration, and error boundary |
| `app/brainserve-app.tsx` | Small compatibility entry; keeps the existing named and default exports |
| `app/application/brainserve-root.tsx` | Session restoration, password-change gate, and public/workspace screen selection |
| `app/workspace/dashboard-app.tsx` | Sidebar, header, feature composition, and modal placement |
| `app/workspace/navigation.ts` | Navigation labels and icons |
| `app/workspace/hooks/` | Workspace state, selectors, data loading, live updates, notification counts/sounds, profile synchronization, and preview session coordination |
| `app/features/` | Screens and components grouped by business feature |
| `app/features/*/actions/` | Appointment, visitor, employee, organization, and staff account action handlers |
| `app/shared/components/` | Logo, page title, and status pill |
| `app/shared/hooks/` | Shared modal focus/keyboard behavior |
| `app/shared/types/` | Frontend domain and navigation types |
| `app/shared/config/` | Role mappings, role permissions, and identity constants |
| `app/shared/utils/` | Error and client identifier helpers |
| `app/preview/` | Browser preview persistence, fixtures, recovery, and governed demo transitions |
| `app/lib/` | Existing API transport, appointment/date helpers, and notification services |

The global stylesheets, connection recovery component, reports overview component, and their CSS modules keep their existing locations and import order. This avoids changing the CSS cascade or routing conventions during the structural refactor.

## Where to make changes

| Change | Start here |
| --- | --- |
| Public home | `app/features/public/welcome.tsx` |
| Booking or tracking | `app/features/appointments/booking-flow.tsx`, `track-appointment.tsx` |
| Staff login, registration, recovery | `app/features/auth/` |
| Appointment queue or decisions | `app/features/appointments/appointments-view.tsx`, `actions/workspace-appointment-actions.ts` |
| Visitor entry, verification, occupancy | `app/features/visitors/`, `actions/workspace-visitor-actions.ts` |
| CEO/dashboard metrics | `app/features/dashboard/overview.tsx`, `app/workspace/hooks/use-workspace-data.ts` |
| Employee directory, status, termination | `app/features/employees/` |
| Departments and leadership assignments | `app/features/organization/` |
| Worksheets, performance, work insights | `app/features/work/` |
| Account provisioning and lifecycle | `app/features/accounts/` |
| Reports and CSV exports | `app/features/reports/`, `app/reports-overview.tsx` |
| Notifications and discussions | `app/features/notifications/`, `app/features/discussions/` |
| Company policies, permission overrides, retention | `app/features/settings/` |
| Profile or leave | `app/features/profile/`, `app/features/leave/` |
| Audit and essential logs | `app/features/audit/` |
| New navigation item or role visibility | `app/workspace/navigation.ts`, `app/shared/config/roles.ts`, workspace composition |
| API endpoint or request/session transport | `app/lib/api.ts` |

## Dependency rules

- Application composition imports feature screens; feature screens do not import the application entry or dashboard layout.
- Pass state and callbacks explicitly. Workspace action factories accept typed contexts and return their handlers.
- Type-only references to workspace context types do not introduce runtime dependencies.
- Shared components and helpers stay independent of feature screens.
- Preview persistence is grouped by domain and retains the existing keys and custom events.
- Use explicit module imports. Avoid a global barrel that reconnects unrelated features or creates cycles.
- Preserve the placement of state ownership and effect subscriptions when extending the workspace. Each coordination hook receives the values it needs; stable setters and refs are explicit effect dependencies.

## Validation

The refactor was reviewed against the current original repository before publication:

- Existing screen/function bodies and constants were checked against the pre-refactor source. The dashboard JSX was checked separately and is unchanged.
- TypeScript validation and the production build pass.
- ESLint passes with the same two pre-existing image warnings in the recovery component.
- All 263 existing source regression assertions pass. Three additional architecture checks enforce a small entry point, resolved imports without runtime cycles, and feature dependency boundaries.
- The authenticated browser suite checks appointment cancellation, HR/CEO lifecycle protection, reports and CSV exports, CEO visitor occupancy, session restoration/revocation, token renewal, and recovery/retry behavior.

The existing backend, migrations, stylesheets, assets, API code, dependency versions, and GitHub Actions workflow are retained. Browser tests use fixtures; deployment against the real database and infrastructure remains a separate integration check.

## Running checks

Use the existing commands from the repository root:

```sh
npm ci
npm run lint
npm test
npm run test:e2e
```

Source contracts read the extracted frontend through `tests/frontend-source.mjs`. Feature-specific contracts can use `readFrontendModule()` instead of relying on neighboring functions appearing in one file.
