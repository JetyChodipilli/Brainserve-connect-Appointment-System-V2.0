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
| `utils/work-utils.ts` | Date/status helpers |
| `pages/work-insights-view.tsx` | HR / Manager / CEO review |
| `pages/team-lead-performance-view.tsx` | Performance display |
| `routes.ts` | Feature screen exports used by the workspace |
| `preview.ts` | Browser preview work records |

The workspace supplies identity and directory data through typed props. This feature's API uses the shared `lib/api-client.ts` transport. Department lookup explicitly uses the Organization API. Existing view IDs (`work`, `performance`, `insights`) and role restrictions remain.

Keep existing-screen changes here. Sidebar labels and visibility live in `config/navigation.ts` and `config/roles.ts`; adding a destination requires connecting it in the workspace layout.
