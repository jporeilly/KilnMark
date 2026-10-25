/**
 * The user guides in docs/guide, and the changelog as "What's new", bundled into the app at build
 * time so they work offline. Each
 * page is addressed as #/docs/<id>, and each section as #/docs/<id>/<slug>, where the slug is made
 * from the heading text the way GitHub makes heading anchors. Anything that wants to link into
 * the documentation (the docs viewer, an assistant answering from it) uses this module.
 */
import { marked } from "marked";
import changelog from "../../../CHANGELOG.md?raw";
import { WHATS_NEW } from "./links";

export { docHref, WHATS_NEW } from "./links";

/** A bundled guide: id is its file name without .md, title its first heading. */
export type DocPage = { id: string; title: string; markdown: string };

/** A section heading of a page, with the slug that addresses it. */
export type DocHeading = { depth: number; text: string; slug: string };

const files = import.meta.glob("../../../docs/guide/*.md", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;

/** Every bundled page: the guides in title order, then What's new. */
export const DOC_PAGES: DocPage[] = [
  ...Object.entries(files)
    .map(([path, markdown]) => {
      const id = path.replace(/^.*\//, "").replace(/\.md$/, "");
      const first = /^#\s+(.+)$/m.exec(markdown)?.[1];
      return { id, title: first ? plainHeading(first) : id, markdown };
    })
    .sort((a, b) => a.title.localeCompare(b.title, "en-GB")),
  { id: WHATS_NEW, title: "What's new", markdown: changelog },
];

export const docPage = (id: string): DocPage | undefined => DOC_PAGES.find((p) => p.id === id);

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

/** Gives repeated slugs on one page a -1, -2 ... suffix, as GitHub does. Pass a fresh map per page. */
export function uniqueSlug(base: string, seen: Map<string, number>): string {
  const n = seen.get(base);
  seen.set(base, (n ?? -1) + 1);
  return n == null ? base : `${base}-${n + 1}`;
}

/** The section headings of a page (level 2 and below), each with its slug. */
export function docHeadings(page: DocPage): DocHeading[] {
  const seen = new Map<string, number>();
  return marked
    .lexer(page.markdown)
    .filter((t) => t.type === "heading" && t.depth > 1)
    .map((t) => {
      const text = plainHeading((t as { text: string }).text);
      return { depth: (t as { depth: number }).depth, text, slug: uniqueSlug(slug(text), seen) };
    });
}
