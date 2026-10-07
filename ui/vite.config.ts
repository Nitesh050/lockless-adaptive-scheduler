import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev:   npm run dev       -> http://localhost:5173, API calls proxied to the Java backend on :8080
// Build: npm run build     -> compiled into the scheduler jar's resources, served by `--ui`
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { "/api": "http://127.0.0.1:8080" }, // IPv4: the backend binds 127.0.0.1
  },
  build: {
    outDir: "../sched-cli/src/main/resources/ui",
    emptyOutDir: true,
    // Served from localhost by the scheduler, never downloaded over a network: one ~190 kB
    // (gzipped) bundle is fine, so don't warn about it.
    chunkSizeWarningLimit: 1000,
  },
});
