import { Marked } from "marked";
import { docHref, docPage, plainHeading, slug, uniqueSlug, type DocPage } from "./index";

const ENTITIES: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ENTITIES[c]);

/**
 * Where a link in a guide should point inside the app, or null to show its words without a link.
 * In-page anchors (#thresholds) and links to other guides (other.md#part) become hash routes, so
 * they never break the router; web and mail addresses and paths on this server (/api/docs) stay.
 */
function target(page: DocPage, href: string): string | null {
  // Backslashes and control characters can make a browser read a path as another host.
  if (/[\u0000-\u001f\u007f\\]/.test(href)) return null;
  if (href.startsWith("#")) return docHref(page.id, decode(href.slice(1)) || null);
  const other = /^(?:\.\/)?([\w-]+)\.md(?:#(.*))?$/.exec(href);
  if (other) return docPage(other[1]) ? docHref(other[1], other[2] ? decode(other[2]) : null) : null;
  if (/^(?:https?:|mailto:)/i.test(href)) return href;
  if (href.startsWith("/")) {
    try {
      return new URL(href, window.location.origin).origin === window.location.origin ? href : null;
    } catch {
      return null;
    }
  }
  return null;
}

function decode(part: string): string {
  try {
    return decodeURIComponent(part);
  } catch {
    return part;
  }
}

/**
 * A guide as HTML. Raw HTML in the Markdown is shown as text, never rendered; images show their
 * description only; links are limited as in {@link target}. Section headings get the id
 * doc-<slug> (see docHeadings) and can take focus; level 1 headings are left out, as the page
 * shows the title (DocPage.title) itself.
 */
export function renderDoc(page: DocPage): string {
  const seen = new Map<string, number>();
  const md = new Marked({
    gfm: true,
    async: false,
    renderer: {
      html({ text }) {
        return escapeHtml(text);
      },
      heading({ tokens, depth, text }) {
        if (depth === 1) return "";
        const inner = this.parser.parseInline(tokens);
        const s = uniqueSlug(slug(plainHeading(text)), seen);
        return `<h${depth} id="doc-${escapeHtml(s)}" tabindex="-1">${inner}</h${depth}>\n`;
      },
      link({ href, title, tokens }) {
        const inner = this.parser.parseInline(tokens);
        const to = target(page, href);
        if (to == null) return inner;
        const external = /^(?:https?:|mailto:)/i.test(to);
        return `<a href="${escapeHtml(to)}"${title ? ` title="${escapeHtml(title)}"` : ""}${
          external ? ' rel="noopener noreferrer"' : ""
        }>${inner}</a>`;
      },
      image({ text }) {
        return escapeHtml(text);
      },
    },
  });
  const html = md.parse(page.markdown, { async: false });
  // Wide tables scroll sideways on their own instead of widening the page.
  return html
    .replace(/<table>/g, '<div class="table-scroll" role="region" aria-label="Table (scrolls sideways on small screens)" tabindex="0"><table>')
    .replace(/<\/table>/g, "</table></div>");
}
