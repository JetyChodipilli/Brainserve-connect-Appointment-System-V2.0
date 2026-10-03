# Workboard

This folder owns worksheets, task delivery, performance, work insights, and approval/rework screens.

| File | Edit for |
| --- | --- |
| `pages/work-board.tsx` | Worksheet presentation |
| `hooks/use-workboard.ts` | State, loading, filters, actions, and rework |
| `api/workboard-api.ts` | Work-task and insight endpoints |
| `types/workboard.ts` | Work contracts |
| `types/props.ts` | Workspace inputs/callbacks |
| `components/work-task-pill.tsx` | Task status display |
| `components/work-task-drawer.tsx` | Current detail, updates, retained review events and allowed actions |
| `components/work-dialog.tsx` | Native modal focus, Escape and return focus |
| `utils/workboard-model.ts` | Demo-only count/page model and bounded owner preferences |
| `utils/work-utils.ts` | Date/status helpers |
| `pages/work-insights-view.tsx` | HR / Manager / CEO review |
| `pages/team-lead-performance-view.tsx` | Performance display |
| `routes.ts` | Feature screen exports used by the workspace |
| `preview.ts` | Browser preview work records |

The workspace supplies identity and directory data through typed props. This feature's API uses the shared `lib/api-client.ts` transport. Department lookup explicitly uses the Organization API. Existing view IDs (`work`, `performance`, `insights`) and role restrictions remain.

Keep existing-screen changes here. Sidebar labels and visibility live in `config/navigation.ts` and `config/roles.ts`; adding a destination requires connecting it in the workspace layout.

The production board uses `/workboard` for its page and counts, `/workboard/{id}` independently for the selected worksheet, and durable `/workboard/preferences` with an observed revision. List and stage board share the same filtered page. The drawer survives pagination and background refresh until current scope is denied or the selected task is unavailable. Session changes invalidate pending reads and mutations; only the latest page/detail request can paint. A version conflict keeps an edited note and requires an explicit reload before retrying. The original task and insight mutation endpoints remain the business authority.

Production task evidence and preferences are never stored locally. Demo preferences contain layout, density and bounded named criteria under a demo-account key; demo work records remain in the existing isolated preview fixture flow. `tests/sprint5.test.mjs` exercises transport, counts, preference boundaries and request races; `e2e-backend/sprint5-workboard.spec.ts` exercises real UI interactions against routed synthetic responses, not live-backend customer UAT.
