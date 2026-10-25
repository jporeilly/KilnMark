// Checks the documentation's section anchors (src/docs/slug.ts) against the cases the server's
// copy (DocSlugsTest, DocSectionsTest) is checked against too, so the assistant's links always
// land on a section. Runs before every build (npm "prebuild"); needs Node 22.18 or later, which
// runs TypeScript files directly.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { markdownHeadings, slug, uniqueSlug } from "../src/docs/slug.ts";

const dir = fileURLToPath(new URL("../../tests/docs/", import.meta.url));
const cases = JSON.parse(readFileSync(dir + "slug-cases.json", "utf8"));
const wrong = [];

for (const c of cases.slugs) {
  const got = slug(c.heading);
  if (got !== c.slug) wrong.push(`slug(${JSON.stringify(c.heading)}) is ${JSON.stringify(got)}, expected ${JSON.stringify(c.slug)}`);
}
for (const page of cases.pages) {
  const seen = new Map();
  const got = page.headings.map((h) => uniqueSlug(slug(h), seen));
  if (JSON.stringify(got) !== JSON.stringify(page.slugs)) wrong.push(`page ${JSON.stringify(page.headings)} gives ${JSON.stringify(got)}, expected ${JSON.stringify(page.slugs)}`);
}
const hc = cases.headingCases;
const got = markdownHeadings(readFileSync(dir + hc.file, "utf8")).map((h) => h.slug);
if (JSON.stringify(got) !== JSON.stringify(hc.slugs)) wrong.push(`${hc.file} gives ${JSON.stringify(got)}, expected ${JSON.stringify(hc.slugs)}`);

if (wrong.length) {
  console.error("Documentation anchors do not match tests/docs/slug-cases.json:\n- " + wrong.join("\n- "));
  process.exit(1);
}
console.log(`Documentation anchors: ${cases.slugs.length + cases.pages.length + 1} cases match.`);
