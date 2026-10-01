import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174, // fixed: the backend's CORS allowlist (app.cors.allowed-origins) must name this exact origin
    strictPort: true,
  },
})
