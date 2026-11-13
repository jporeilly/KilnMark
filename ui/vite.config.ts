import { fileURLToPath } from "node:url";
import { defineConfig, normalizePath, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import { assembleChangelog, FRAGMENT_DIR, readFragments } from "./scripts/changelog.mjs";

const repo = fileURLToPath(new URL("..", import.meta.url));
const changelogFile = normalizePath(fileURLToPath(new URL("../CHANGELOG.md", import.meta.url)));
const fragmentDir = normalizePath(fileURLToPath(new URL(`../${FRAGMENT_DIR}`, import.meta.url)));

// The changelog the app bundles as What's new (src/docs/index.ts imports CHANGELOG.md?raw) is
// CHANGELOG.md with the fragments in changelog.d merged into its Unreleased part, by the same
// script the server's build and the anchor check use (scripts/changelog.mjs). The import is
// answered by a virtual module, so it works alike in vite build, the dev server (which reloads
// when CHANGELOG.md or a fragment changes, is added or is removed, and whose file-serving rules
// refused CHANGELOG.md?raw) and vitest (never cached on disk, as its cache key is one file's text).
const ASSEMBLED = "\0kilnmark-assembled-changelog";
type VitestContext = { defineCacheKeyGenerator(generator: (c: { id: string }) => string | false | undefined): void };

function assembledChangelog(): Plugin & { configureVitest(context: VitestContext): void } {
  return {
    name: "kilnmark-assembled-changelog",
    enforce: "pre",
    configureVitest(context) {
      context.defineCacheKeyGenerator(({ id }) => (id === ASSEMBLED ? false : undefined));
    },
    async resolveId(source, importer) {
      const [file, query = ""] = source.split("?", 2);
      if (!file.endsWith("CHANGELOG.md") || !new URLSearchParams(query).has("raw")) return null;
      const resolved = await this.resolve(file, importer, { skipSelf: true });
      return resolved && normalizePath(resolved.id) === changelogFile ? ASSEMBLED : null;
    },
    load(id) {
      if (id !== ASSEMBLED) return null;
      this.addWatchFile(changelogFile);
      for (const f of readFragments(repo)) this.addWatchFile(`${fragmentDir}/${f.name}`);
      return `export default ${JSON.stringify(assembleChangelog(repo))};`;
    },
    configureServer(server) {
      server.watcher.add([changelogFile, fragmentDir]);
      server.watcher.on("all", (_event, path) => {
        const file = normalizePath(path);
        if (file !== changelogFile && !file.startsWith(fragmentDir + "/")) return;
        const assembled = server.moduleGraph.getModuleById(ASSEMBLED);
        if (!assembled) return;
        server.moduleGraph.invalidateModule(assembled);
        server.ws.send({ type: "full-reload" });
      });
    },
  };
}

// Built files go to the repository-wide dist folder (see docs/adr/0010-repository-layout.md).
// The in-app documentation bundles the guides in docs/guide and CHANGELOG.md at build time
// (src/docs), so the dev server may read them too.
export default defineConfig({
  plugins: [assembledChangelog(), react()],
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
