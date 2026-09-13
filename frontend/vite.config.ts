import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Dev-only convenience: the api client defaults to same-origin "/api",
    // this proxies that to the backend so `npm run dev` works without CORS
    // setup. In prod (Docker Compose, section 12) nginx serves both.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
