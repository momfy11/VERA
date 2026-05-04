/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  readonly VITE_WS_BASE?: string;
  readonly VITE_PICOVOICE_KEY?: string;
  readonly VITE_WAKE_WORD?: string;
  readonly VITE_WAKE_WORD_PATH?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
