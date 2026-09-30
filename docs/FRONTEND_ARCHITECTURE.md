# Frontend architecture and editing guide

BrainServe V2.0 has independent `frontend/` and `backend/` projects. The frontend follows [Client Onboarding](https://github.com/JetyChodipilli/Client-Onboarding/tree/main/frontend): thin `app/` route files, business features, shared components, hooks, libraries, and types. Vinext/Vite remains the Next.js-compatible runtime.

This prepares the codebase for V2.0 development. Existing role navigation, permissions, screens, styles, request bodies, storage keys, and workflows are preserved. Original baseline: [`d27eac1`](https://github.com/JetyChodipilli/Brainserve-connect-Appointment-System/commit/d27eac1106d114d36f6fd40be8074b55821bca8e).

## Folder map

| Folder | Owns |
| --- | --- |
| `frontend/app/` | Route entry, root layout, global styles |
| `frontend/features/` | Business screens, components, actions, API endpoints |
| `frontend/features/workspace/` | Session restoration and application screen selection |
| `frontend/components/layouts/` | Sidebar, header, screen composition, modal placement |
| `frontend/components/ui/` | Page titles and status pills |
| `frontend/components/shared/` | Logo, error boundary, connection recovery, report visualization |
| `frontend/hooks/workspace/` | Workspace state, data loading, realtime and synchronization |
| `frontend/hooks/` | Shared modal focus/keyboard behavior |
| `frontend/config/` | Navigation labels/icons, roles, permissions, identity |
| `frontend/lib/api-client.ts` | Shared HTTP transport, token lifecycle, pagination, realtime |
| `frontend/services/brainserve-api.ts` | Small compatibility facade composing feature APIs |
| `frontend/services/` | Notification sound and hosted identity integration |
| `frontend/types/`, `frontend/utils/` | Shared contracts and helpers |
| `frontend/preview/` | Browser preview persistence and fixtures |
| `frontend/public/` | Existing images and browser assets |
| `frontend/tests/`, `frontend/e2e/`, `frontend/e2e-backend/` | Source, utility, and browser regressions |
| `frontend/build/`, `frontend/worker/`, `frontend/.openai/` | Existing hosting integration |
| `backend/` | Java API, migrations, backend tests |
| `ops/`, `scripts/`, `docker-compose.yml` | Full-stack infrastructure and operations |
| `.github/` | CI and dependency updates |

Each application owns its build configuration and Dockerfile. Run npm in `frontend/` and Maven in `backend/`.

## Example: change Workboard only

Start in [`frontend/features/workboard/`](../frontend/features/workboard/). Its [local guide](../frontend/features/workboard/README.md) lists these files:

| Change | File inside the Workboard feature |
| --- | --- |
| Worksheet layout, controls, or copy | `pages/work-board.tsx` |
| Loading, filters, task actions, approval/rework | `hooks/use-workboard.ts` |
| Work-task/insight endpoint or payload | `api/workboard-api.ts` |
| Work-task and insight contracts | `types/workboard.ts` |
| Workspace props | `types/props.ts` |
| Task status presentation | `components/work-task-pill.tsx` |
| Date/status utilities | `utils/work-utils.ts` |
| Team Lead performance | `pages/team-lead-performance-view.tsx` |
| Work review/approval screen | `pages/work-insights-view.tsx` |
| Screen registration | `routes.ts` |
| Preview work records | `preview.ts` |

The connection is `app/page.tsx` → workspace session root → shared workspace layout → `workboard/routes.ts` → feature pages. Workboard uses its own hook and API, which call the shared transport. Department lookup explicitly uses `organization/api/organization-api.ts`.

The existing workspace view IDs remain `work`, `performance`, and `insights`. These are role-based screens; this refactor introduces no new browser URL paths. Editing an existing screen requires no changes to other features or the shared layout. Adding a new navigation destination also requires registration in `config/navigation.ts`, visibility in `config/roles.ts`, and screen composition in the workspace layout.

## Other feature entry points

Paths are relative to `frontend/features/`.

| Area | Start here |
| --- | --- |
| Public home | `public/welcome.tsx` |
| Booking and tracking | `appointments/booking-flow.tsx`, `appointments/track-appointment.tsx` |
| Login, registration, recovery | `auth/` |
| Appointment decisions | `appointments/appointments-view.tsx`, `appointments/actions/` |
| Visitor entry and occupancy | `visitors/`, `visitors/actions/` |
| Dashboard metrics | `dashboard/overview.tsx` |
| Employees and terminations | `employees/`, `employees/actions/` |
| Departments and leadership | `organization/`, `organization/actions/` |
| Provisioning and account lifecycle | `accounts/`, `accounts/actions/` |
| Reports and exports | `reports/` |
| Notifications and discussions | `notifications/`, `discussions/` |
| Settings and data governance | `settings/`, `governance/api/` |
| Profile and leave | `profile/`, `leave/` |
| Audit and essential logs | `audit/` |

Endpoint implementations live in each feature's `api/` folder. Shared session operations remain in the HTTP client.

## Dependency rules

- Route files and workspace composition import features. Features do not import the application entry or shared workspace layout.
- Feature APIs depend on transport and contract types. Transport does not import feature APIs.
- Workboard imports its own API directly. The compatibility facade supports consumers requiring several features without owning endpoint logic.
- Pass state and callbacks explicitly. Workspace actions use typed contexts; workspace type references use type-only imports.
- Shared UI stays independent of feature screens. Avoid application-wide export barrels; Workboard screen exports cover only Workboard.
- Preserve effect order, state ownership, events, and storage keys. Architecture regressions reject unresolved imports and runtime cycles.

## Running and checking

```sh
cd frontend
npm ci
npm run dev:backend
```

Validation:

```sh
npm audit --audit-level=high
npm run lint
npm run build
npm run test:regression
npx playwright install --with-deps chromium firefox webkit
npm run test:e2e
```

`npm test` builds before regressions. The rendered-HTML test needs the production build. Source contracts read actual modules through `tests/frontend-source.mjs`.

## CI and preservation review

Actions runs frontend checks in `frontend/`, caches `frontend/package-lock.json`, and retains the security audit, type checks, lint, build, regressions, and browser suites. Browser failures upload evidence from `frontend/test-results/`. Maven continues in `backend/`; Dependabot targets `/frontend` for npm.

Compose builds from `./frontend` with its own `Dockerfile`. Environment and infrastructure scripts resolve the repository directory, so npm helpers still reach root Compose and `backend/.env`.

Review compares extracted component logic and API method bodies against the original source. Global CSS, CSS modules, images, backend code, and migrations retain their exact contents. Transitive dependency security patches clear the inherited audit failure; direct runtime/framework pins remain. Browser regressions use controlled backend fixtures; live infrastructure deployment validation remains separate.

References: [Next.js project organization](https://nextjs.org/docs/app/getting-started/project-structure), [React custom hooks](https://react.dev/learn/reusing-logic-with-custom-hooks), [GitHub npm cache paths](https://github.com/actions/setup-node/blob/main/docs/advanced-usage.md#caching-packages-data).
