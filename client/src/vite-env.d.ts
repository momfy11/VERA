/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  readonly VITE_WS_BASE?: string;
  readonly VITE_WAKE_PHRASES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
