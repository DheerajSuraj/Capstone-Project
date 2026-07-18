import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev proxy: the browser calls /api/* on the Vite origin (5173) and Vite
// forwards to Spring on 8080 — no CORS configuration needed anywhere, and
// the frontend code uses relative URLs that will work unchanged in
// production behind the same reverse proxy.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})