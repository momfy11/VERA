import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["favicon.svg", "icons/icon-192.png", "icons/icon-512.png"],
      manifest: {
        name: "VERA — Voice-Enabled Reasoning Assistant",
        short_name: "VERA",
        description: "Always-on personal assistant. Voice-first, memory-aware, tool-capable.",
        theme_color: "#d66b3f",
        background_color: "#f7f2ec",
        display: "standalone",
        orientation: "portrait",
        scope: "/",
        start_url: "/",
        icons: [
          { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "/icons/icon-512-maskable.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        // Don't cache API/WS — they need fresh data
        navigateFallbackDenylist: [/^\/api\//, /^\/ws/],
        runtimeCaching: [
          {
            urlPattern: /^https:\/\/fonts\.googleapis\.com\//,
            handler: "StaleWhileRevalidate",
            options: { cacheName: "google-fonts-css" },
          },
          {
            urlPattern: /^https:\/\/fonts\.gstatic\.com\//,
            handler: "CacheFirst",
            options: {
              cacheName: "google-fonts-files",
              expiration: { maxEntries: 30, maxAgeSeconds: 60 * 60 * 24 * 365 },
            },
          },
        ],
      },
    }),
  ],
  server: {
    // host: true binds to 0.0.0.0 so the dev server is reachable from
    // other devices on the LAN (e.g. your phone). Without this Vite only
    // listens on 127.0.0.1 and the phone gets ECONNREFUSED.
    host: true,
    port: 5173,
  },
  preview: {
    // Same reasoning for `npm run preview` (built bundle).
    host: true,
    port: 4173,
  },
});
