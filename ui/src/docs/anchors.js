// Section anchors for the bundled documentation. Plain JavaScript (types in anchors.d.ts), so the
// Node check run before every build (scripts/check-slugs.mjs) imports this very file on any Node
// that Vite supports, without TypeScript. The server makes the same anchors for the assistant's
// sections (DocSlugs.java and DocSections.java); both are checked against
// tests/docs/slug-cases.json and heading-cases.md, so change them together.
import { marked } from "marked";

/** Heading text without its Markdown: links keep their words, code and emphasis marks go. */
export function plainHeading(markdown) {
  return markdown
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/[`*]/g, "")
    .trim();
}

/**
 * The anchor for a heading, as GitHub makes it: lower case, punctuation dropped (letters, digits,
 * underscores and hyphens stay), spaces turned into hyphens. "Custom SQL (admins)" gives
 * "custom-sql-admins".
 */
export function slug(heading) {
  return plainHeading(heading)
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{N}\p{Pc} -]/gu, "")
    .replace(/ /g, "-");
}

/**
 * Gives repeated slugs on one page a -1, -2 ... suffix, as GitHub does, skipping any suffix that
 * another heading already has ("Details", "Details", "Details-1" give details, details-1,
 * details-1-1). Pass a fresh map per page.
 */
export function uniqueSlug(base, seen) {
  let result = base;
  while (seen.has(result)) {
    const n = (seen.get(base) ?? 0) + 1;
    seen.set(base, n);
    result = `${base}-${n}`;
  }
  seen.set(result, 0);
  return result;
}

/** The section headings of a page of Markdown (level 2 and below), each with its slug. */
export function markdownHeadings(markdown) {
  const seen = new Map();
  return marked
    .lexer(markdown)
    .filter((t) => t.type === "heading" && t.depth > 1)
    .map((t) => {
      const text = plainHeading(t.text);
      return { depth: t.depth, text, slug: uniqueSlug(slug(text), seen) };
    });
}
