/**
 * Section anchors for the bundled documentation, kept free of the bundler (no ?raw imports) so
 * that a plain Node check (scripts/check-slugs.mjs, run before every build) can run them. The
 * server makes the same anchors for the assistant's sections (DocSlugs.java and DocSections.java);
 * both are checked against tests/docs/slug-cases.json and heading-cases.md, so change them
 * together.
 */
import { marked } from "marked";

/** A section heading of a page, with the slug that addresses it. */
export type DocHeading = { depth: number; text: string; slug: string };

/** Heading text without its Markdown: links keep their words, code and emphasis marks go. */
export function plainHeading(markdown: string): string {
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
export function slug(heading: string): string {
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
export function uniqueSlug(base: string, seen: Map<string, number>): string {
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
export function markdownHeadings(markdown: string): DocHeading[] {
  const seen = new Map<string, number>();
  return marked
    .lexer(markdown)
    .filter((t) => t.type === "heading" && t.depth > 1)
    .map((t) => {
      const text = plainHeading((t as { text: string }).text);
      return { depth: (t as { depth: number }).depth, text, slug: uniqueSlug(slug(text), seen) };
    });
}
