// Writes the web UI's CycloneDX SBOM (docs/design/supply-chain.md): the packages its bundle is
// built from, chosen as ui-notices.mjs chooses them (in package-lock.json, not build tools, not types).
// npm's own `npm sbom --omit=dev` is not used: it drops runtime packages a build tool also names as
// an optional peer (yaml, through vite). So npm writes the whole tree, and this keeps what ships.
// Usage: npm run sbom -- <output file>. Needs no install; reads the lock file only.
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const out = process.argv[2];
if (!out) {
  console.error("UI SBOM: give the output file, e.g. npm run sbom -- ../dist/ui-sbom/kilnmark-ui.cdx.json");
  process.exit(1);
}
const ui = fileURLToPath(new URL("..", import.meta.url));
const lock = JSON.parse(readFileSync(join(ui, "package-lock.json"), "utf8"));

// name@version of every package the bundle is built from
const shipped = new Set();
for (const [path, p] of Object.entries(lock.packages ?? {})) {
  if (!path || p.dev || p.devOptional || path.includes("node_modules/@types/")) continue;
  const name = path.slice(path.lastIndexOf("node_modules/") + "node_modules/".length);
  shipped.add(`${name}@${p.version}`);
}

// npm itself, as `npm run sbom` names it: no shell, on every platform.
const npmCli = process.env.npm_execpath;
if (!npmCli) {
  console.error("UI SBOM: run it through npm: npm run sbom -- <output file>");
  process.exit(1);
}
const bom = JSON.parse(execFileSync(process.execPath, [npmCli, "sbom", "--sbom-format=cyclonedx", "--package-lock-only"], {
  cwd: ui, encoding: "utf8", maxBuffer: 64 * 1024 * 1024,
}));

const root = bom.metadata.component["bom-ref"];
bom.components = bom.components.filter((c) => shipped.has(c["bom-ref"]));
const kept = new Set([root, ...bom.components.map((c) => c["bom-ref"])]);
bom.dependencies = (bom.dependencies ?? [])
  .filter((d) => kept.has(d.ref))
  .map((d) => ({ ...d, dependsOn: (d.dependsOn ?? []).filter((r) => kept.has(r)) }));

const missing = [...shipped].filter((ref) => !kept.has(ref));
if (missing.length > 0) {
  console.error(`UI SBOM: npm's SBOM leaves out packages the UI ships: ${missing.join(", ")}`);
  process.exit(1);
}
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify(bom, null, 2) + "\n");
console.log(`UI SBOM: ${bom.components.length} packages written to ${out}`);
