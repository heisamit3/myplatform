import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The gateway's CORS allows exactly this origin (CORS_ALLOWED_ORIGINS), so never drift to 5174.
    port: 5173,
    strictPort: true,
  },
})
