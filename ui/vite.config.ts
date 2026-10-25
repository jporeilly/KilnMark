import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Built files go to the repository-wide dist folder (see docs/adr/0010-repository-layout.md).
// The in-app documentation bundles the guides in docs/guide and CHANGELOG.md at build time
// (src/docs), so the dev server may read them too.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../dist/ui",
    emptyOutDir: true,
  },
  server: {
    fs: {
      allow: [
        fileURLToPath(new URL(".", import.meta.url)),
        fileURLToPath(new URL("../docs/guide", import.meta.url)),
        fileURLToPath(new URL("../CHANGELOG.md", import.meta.url)),
      ],
    },
    proxy: {
      "/api": "http://localhost:8780",
      "/q": "http://localhost:8780",
    },
  },
});
