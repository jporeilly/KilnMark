import { useEffect, useMemo, useRef } from "react";
import { useTitle } from "../components/useTitle";
import { DOC_PAGES, docHeadings, docHref, docPage } from "../docs";
import { renderDoc } from "../docs/render";

/**
 * The user guides, bundled with the app. #/docs lists them, #/docs/<page> shows one, and
 * #/docs/<page>/<section> shows it scrolled to that section, with focus on its heading.
 */
export default function DocsPage({ doc, section }: { doc: string | null; section: string | null }) {
  const page = doc ? docPage(doc) : undefined;
  useTitle(page?.title ?? "Documentation");
  const html = useMemo(() => (page ? renderDoc(page) : ""), [page]);
  const contents = useMemo(() => (page ? docHeadings(page).filter((h) => h.depth === 2) : []), [page]);
  const article = useRef<HTMLDivElement>(null);

  // Runs before the shell moves focus to the h1 after a route change. Focus inside the
  // data-keeps-focus article tells the shell to leave it on the section heading.
  useEffect(() => {
    if (!section) return;
    const heading = article.current?.querySelector<HTMLElement>(`#doc-${CSS.escape(section)}`);
    if (heading) {
      heading.focus();
      heading.scrollIntoView({ block: "start" });
    }
  }, [html, section]);

  // The first visit loads this page lazily, after the shell has looked for an h1 to focus: take
  // focus to the heading unless it is already somewhere in the page.
  useEffect(() => {
    if (section) return;
    if (!document.querySelector("main")?.contains(document.activeElement)) {
      document.querySelector<HTMLElement>("main h1")?.focus();
    }
    // Only on first showing; later moves are the shell's.
  }, []);

  if (!page) {
    return (
      <>
        <h1 tabIndex={-1}>Documentation</h1>
        {doc && (
          <p role="alert" className="error">
            There is no guide called “{doc}”. Choose one below.
          </p>
        )}
        <ul>
          {DOC_PAGES.map((p) => (
            <li key={p.id}>
              <a href={docHref(p.id)}>{p.title}</a>
            </li>
          ))}
        </ul>
      </>
    );
  }

  return (
    <>
      <p className="small">
        <a href={docHref()}>Documentation</a>
      </p>
      <h1 tabIndex={-1}>{page.title}</h1>
      <div ref={article} data-keeps-focus>
        {contents.length > 2 && (
          <nav aria-labelledby="docs-on-this-page" className="panel">
            <p id="docs-on-this-page" className="small muted">On this page</p>
            <ul>
              {contents.map((h) => (
                <li key={h.slug}>
                  <a href={docHref(page.id, h.slug)}>{h.text}</a>
                </li>
              ))}
            </ul>
          </nav>
        )}
        {/* Rendered from a guide bundled at build time; raw HTML in it is escaped (see renderDoc). */}
        <div className="doc-body" dangerouslySetInnerHTML={{ __html: html }} />
      </div>
    </>
  );
}
