import { ApiClient } from './client'

/** The one client for the whole app. Set VITE_API_URL to point at another gateway. */
export const api = new ApiClient({ baseUrl: import.meta.env.VITE_API_URL ?? 'http://localhost:8080' })
