/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** true = endpointy używają mocków, poza tymi z VITE_LIVE_FEATURES. */
  readonly VITE_USE_MOCKS?: string
  /** Lista funkcji wołających żywy gateway mimo VITE_USE_MOCKS=true, np. "chat". */
  readonly VITE_LIVE_FEATURES?: string
  /** Tagi modeli (po przecinku), dopóki gateway nie wystawi GET /api/models. */
  readonly VITE_MODELS?: string
}
