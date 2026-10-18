/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// In development the Vite server proxies /api to the gateway, so the browser talks to a single origin and the
// HttpOnly refresh cookie (path /api/v1/auth, SameSite=Strict) works without any CORS relaxation.
export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_GATEWAY ?? 'http://localhost:8080', changeOrigin: false },
      '/ws': { target: process.env.VITE_GATEWAY ?? 'http://localhost:8080', changeOrigin: false, ws: true },
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': { target: process.env.VITE_GATEWAY ?? 'http://localhost:8080', changeOrigin: false },
      '/ws': { target: process.env.VITE_GATEWAY ?? 'http://localhost:8080', changeOrigin: false, ws: true },
    },
  },
  build: {
    sourcemap: true,
    chunkSizeWarningLimit: 900,
    // charting is only used by the seller and admin dashboards; keep it out of the storefront bundle
    rollupOptions: { output: { manualChunks: { charts: ['recharts'] } } },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
