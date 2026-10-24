import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Built files go to the repository-wide dist folder (see docs/adr/0010-repository-layout.md).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../dist/ui",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/q": "http://localhost:8080",
    },
  },
});
