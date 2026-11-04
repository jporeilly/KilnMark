/**
 * The user guides in docs/guide, and the changelog as "What's new", bundled into the app at build
 * time so they work offline. Each
 * page is addressed as #/docs/<id>, and each section as #/docs/<id>/<slug>, where the slug is made
 * from the heading text the way GitHub makes heading anchors. Anything that wants to link into
 * the documentation (the docs viewer, an assistant answering from it) uses this module.
 */
import changelog from "../../../CHANGELOG.md?raw";
import { WHATS_NEW } from "./links";
import { markdownHeadings, plainHeading, type DocHeading } from "./anchors";

export { docHref, WHATS_NEW } from "./links";
export { markdownHeadings, plainHeading, slug, uniqueSlug, type DocHeading } from "./anchors";

/** A bundled guide: id is its file name without .md, title its first heading. */
export type DocPage = { id: string; title: string; markdown: string };

const files = import.meta.glob("../../../docs/guide/*.md", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;

/** Every bundled page: What's new first, then the guides in title order. */
export const DOC_PAGES: DocPage[] = [
  { id: WHATS_NEW, title: "What's new", markdown: changelog },
  ...Object.entries(files)
    .map(([path, markdown]) => {
      const id = path.replace(/^.*\//, "").replace(/\.md$/, "");
      const first = /^#\s+(.+)$/m.exec(markdown)?.[1];
      return { id, title: first ? plainHeading(first) : id, markdown };
    })
    .sort((a, b) => a.title.localeCompare(b.title, "en-GB")),
];

export const docPage = (id: string): DocPage | undefined => DOC_PAGES.find((p) => p.id === id);

/** The guides' pictures (diagrams and screenshots), bundled with the app: file name → URL. */
const pictures = import.meta.glob("../../../docs/guide/images/*.{png,svg}", { query: "?url", import: "default", eager: true }) as Record<
  string,
  string
>;

/**
 * Where a guide's picture is served from: `images/<name>.png` or `.svg` beside the guides, as GitHub shows it
 * too. Null for any other image, which the guide then shows as its description: nothing is ever
 * loaded from elsewhere.
 */
export function docPicture(src: string): string | null {
  const m = /^(?:\.\/)?images\/([\w-]+\.(?:png|svg))$/.exec(src);
  return m ? (pictures[`../../../docs/guide/images/${m[1]}`] ?? null) : null;
}

/** The section headings of a page (level 2 and below), each with its slug. */
export const docHeadings = (page: DocPage): DocHeading[] => markdownHeadings(page.markdown);
