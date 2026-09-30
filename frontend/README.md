# BrainServe frontend

Install and run from this directory:

```sh
npm ci
npm run dev:backend
```

The Java API defaults to `http://localhost:8080/api/v1`. Set `NEXT_PUBLIC_API_BASE_URL` when building for a deployed backend.

Feature code lives in `features/`; route files in `app/` stay small. Change worksheets in [`features/workboard/`](features/workboard/). See the [architecture and editing guide](../docs/FRONTEND_ARCHITECTURE.md) for every feature and dependency rules.

```sh
npm run lint
npm test
npx playwright install --with-deps chromium firefox webkit
npm run test:e2e
```

Full-stack helpers (`env:init`, `docker:full`, `verify:stack`) resolve the root infrastructure from here.
