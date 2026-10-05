/// <reference types="vitest/config" />
import { fileURLToPath } from "node:url";

import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

import { buildProxy } from "./proxy.ts";

// e2e "fixture mode": SAIMAN_API_TARGET points every /api call at the fixture server instead of
// the real orchestrator/ledger (see e2e/fixture-server.ts). Unset in normal dev and preview.
const proxy = buildProxy(process.env.SAIMAN_API_TARGET);

// https://vite.dev/config/
export default defineConfig({
  plugins: [tanstackRouter({ target: "react", autoCodeSplitting: true }), react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  // Same-origin only. Never `allowedHosts: true` (DNS-rebinding protection stays on).
  server: { proxy },
  preview: { proxy },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
    exclude: ["**/node_modules/**", "**/dist/**", "e2e/**/*.spec.ts"],
  },
});
