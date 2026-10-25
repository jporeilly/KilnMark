// Writes dist/ui/third-party-notices.txt: the licence of every package the web UI's bundle is built
// from (the runtime packages in package-lock.json, not build tools), so their copyright notices
// ship with the UI as their licences require. Run by `npm run build` after vite.
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ui = fileURLToPath(new URL("..", import.meta.url));
const lock = JSON.parse(readFileSync(join(ui, "package-lock.json"), "utf8"));
const out = [
  "Third-party software in the Kilnmark web UI",
  "",
  "Each package below keeps its own licence; the full text of each follows its name.",
  "",
];
let count = 0;
for (const [path, p] of Object.entries(lock.packages ?? {})) {
  if (!path || p.dev || p.devOptional) continue;
  const dir = join(ui, path);
  const pkg = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
  const file = existsSync(dir) ? readdirSync(dir).find((f) => /^(licen[cs]e|copying)(\.|$)/i.test(f)) : undefined;
  out.push("=".repeat(78), `${pkg.name} ${pkg.version} (${pkg.license ?? p.license ?? "see below"})`, "=".repeat(78));
  out.push(file ? readFileSync(join(dir, file), "utf8").trim() : `Licence: ${pkg.license ?? "unknown"}`, "");
  count++;
}
writeFileSync(join(ui, "../dist/ui/third-party-notices.txt"), out.join("\n") + "\n");
console.log(`Third-party notices: ${count} packages written to dist/ui/third-party-notices.txt`);
