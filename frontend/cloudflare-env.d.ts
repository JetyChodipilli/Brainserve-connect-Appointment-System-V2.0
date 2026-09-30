/// <reference types="@cloudflare/workers-types" />

interface ImportMetaEnv {
  readonly VITE_BRAINSERVE_LOCKED?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
