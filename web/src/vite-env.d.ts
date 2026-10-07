/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the api-gateway. Default: http://localhost:8080 */
  readonly VITE_API_URL?: string
  /** Commit the bundle was built from (Docker build arg GIT_SHA). Unset in `npm run dev`. */
  readonly VITE_GIT_SHA?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
