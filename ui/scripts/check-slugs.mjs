// Checks the documentation's section anchors (src/docs/anchors.js), which the assistant's links on
// the server (DocSlugs.java, DocSections.java) must match:
// 1. the shared cases in tests/docs (also checked by DocSlugsTest and DocSectionsTest);
// 2. the real guides and changelog use no Markdown the two could read differently (underlined
//    headings, HTML blocks, headings inside lists or quotes), and the viewer's headings there are
//    exactly those the server's line-by-line reading finds (a copy of DocSections.headingSlugs).
// Runs before every build (npm "prebuild"), on any Node that Vite supports: plain JavaScript only.
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { marked } from "marked";
import { markdownHeadings, plainHeading, slug, uniqueSlug } from "../src/docs/anchors.js";
import { assembleChangelog } from "./changelog.mjs";

const root = fileURLToPath(new URL("../../", import.meta.url));
const cases = JSON.parse(readFileSync(root + "tests/docs/slug-cases.json", "utf8"));
const wrong = [];
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

for (const c of cases.slugs) {
  const got = slug(c.heading);
  if (got !== c.slug) wrong.push(`slug(${JSON.stringify(c.heading)}) is ${JSON.stringify(got)}, expected ${JSON.stringify(c.slug)}`);
}
for (const page of cases.pages) {
  const seen = new Map();
  const got = page.headings.map((h) => uniqueSlug(slug(h), seen));
  if (!same(got, page.slugs)) wrong.push(`page ${JSON.stringify(page.headings)} gives ${JSON.stringify(got)}, expected ${JSON.stringify(page.slugs)}`);
}
const hc = cases.headingCases;
const hcMarkdown = readFileSync(root + "tests/docs/" + hc.file, "utf8");
for (const [who, got] of [["viewer", markdownHeadings(hcMarkdown).map((h) => h.slug)], ["server's reading", lineHeadings(hcMarkdown)]]) {
  if (!same(got, hc.slugs)) wrong.push(`${hc.file} (${who}) gives ${JSON.stringify(got)}, expected ${JSON.stringify(hc.slugs)}`);
}

/** The server's reading of headings (DocSections.headingSlugs): # lines with up to 3 spaces, outside fences. */
function lineHeadings(markdown) {
  const out = [];
  const seen = new Map();
  let fence = null;
  for (const line of markdown.replace(/\r\n?/g, "\n").split("\n")) {
    const f = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
    if (!fence) {
      if (f && !(f[1][0] === "`" && f[2].includes("`"))) {
        fence = { ch: f[1][0], len: f[1].length };
        continue;
      }
    } else {
      if (f && f[1][0] === fence.ch && f[1].length >= fence.len && !f[2].trim()) fence = null;
      continue;
    }
    const h = /^ {0,3}(#{1,6})(?=[ \t]|$)(.*)$/.exec(line);
    if (!h || h[1].length < 2) continue;
    let text = h[2].trim();
    if (text.endsWith("#")) {
      const trimmed = text.replace(/#+$/, "");
      if (!trimmed || trimmed.endsWith(" ")) text = trimmed.trim();
    }
    out.push(uniqueSlug(slug(plainHeading(text)), seen));
  }
  return out;
}

/** Markdown in a page that the viewer and the server could read differently. */
function risky(markdown) {
  const found = [];
  const walk = (tokens, nested) => {
    for (const t of tokens) {
      if (t.type === "heading" && nested) found.push(`a heading inside a list or quote: ${t.raw.trim()}`);
      if (t.type === "heading" && !nested && !/^ {0,3}#/.test(t.raw)) found.push(`an underlined heading: ${t.text}`);
      if (t.type === "html") found.push(`an HTML block: ${t.raw.trim().slice(0, 60)}`);
      if (t.type === "blockquote") walk(t.tokens, true);
      if (t.type === "list") for (const item of t.items) walk(item.tokens, true);
    }
  };
  walk(marked.lexer(markdown), false);
  return found;
}

// The guides, and the install guides in docs/guide/install/ when there are any.
const pages = ["docs/guide/", "docs/guide/install/"]
  .filter((dir) => existsSync(root + dir))
  .flatMap((dir) => readdirSync(root + dir).filter((f) => f.endsWith(".md")).map((f) => dir + f))
  .map((f) => [f, readFileSync(root + f, "utf8")]);
// The changelog as the app and the server bundle it: with the fragments in changelog.d merged in.
pages.push(["CHANGELOG.md (with changelog.d)", assembleChangelog(root)]);
for (const [file, markdown] of pages) {
  for (const r of risky(markdown)) wrong.push(`${file} has ${r}; use # headings and plain Markdown`);
  const viewer = markdownHeadings(markdown).map((h) => h.slug);
  const server = lineHeadings(markdown);
  if (!same(viewer, server)) wrong.push(`${file}: the viewer finds headings ${JSON.stringify(viewer)} but the server ${JSON.stringify(server)}`);
}

if (wrong.length) {
  console.error("Documentation anchors would not match between the viewer and the assistant:\n- " + wrong.join("\n- "));
  process.exit(1);
}
console.log(`Documentation anchors: ${cases.slugs.length + cases.pages.length + 2} cases and ${pages.length} pages match.`);
