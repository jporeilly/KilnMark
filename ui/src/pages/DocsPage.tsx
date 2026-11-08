import { useEffect, useMemo, useRef } from "react";
import { IconTile, PageHeader } from "../components/ui";
import { Icon } from "../components/icons";
import { useTitle } from "../components/useTitle";
import { DOC_PAGES, docHeadings, docHref, docPage, WHATS_NEW, type DocPage } from "../docs";
import { DOC_GROUPS, INSTALL_GROUP, movedDoc } from "../docs/groups";
import { renderDoc } from "../docs/render";

/** The guides of each group, in its order; the install group takes the guides in docs/guide/install/. */
function groupPages(id: string, pages: string[]): DocPage[] {
  if (id === INSTALL_GROUP) return DOC_PAGES.filter((p) => p.folder === "install");
  return pages.map((p) => docPage(p)).filter((p): p is DocPage => p !== undefined);
}

/**
 * The user guides, bundled with the app. #/docs lists them by group, #/docs/<page> shows one, and
 * #/docs/<page>/<section> shows it scrolled to that section, with focus on its heading. A link to a
 * page or section that moved is sent on to where it is now.
 */
export default function DocsPage({ doc, section }: { doc: string | null; section: string | null }) {
  const moved = doc ? movedDoc(doc, section) : null;
  const goesElsewhere = moved !== null && (moved.id !== doc || moved.section !== section) && docPage(moved.id) !== undefined;
  useEffect(() => {
    if (goesElsewhere && moved) window.location.replace(docHref(moved.id, moved.section));
  }, [goesElsewhere, moved?.id, moved?.section]);
  const page = doc && !goesElsewhere ? docPage(doc) : undefined;
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
        <PageHeader title="Documentation" meta="Guides bundled with this version of Kilnmark, readable offline" />
        {doc && !goesElsewhere && (
          <p role="alert" className="error">
            There is no guide called “{doc}”. Choose one below.
          </p>
        )}
        <ul className="km-gallery" aria-label="What's new">
          <li>
            <DocCard page={docPage(WHATS_NEW)!} />
          </li>
        </ul>
        {DOC_GROUPS.map((g) => {
          const pages = groupPages(g.id, g.pages);
          return (
            <section key={g.id} className="docs-group" aria-labelledby={`docs-group-${g.id}`}>
              <h2 id={`docs-group-${g.id}`}>{g.title}</h2>
              <p className="hint">{g.about}</p>
              {pages.length > 0 ? (
                <ul className="km-gallery" aria-label={g.title}>
                  {pages.map((p) => (
                    <li key={p.id}>
                      <DocCard page={p} />
                    </li>
                  ))}
                </ul>
              ) : (
                <p>How to choose a deployment, install, upgrade and remove Kilnmark is in INSTALL.md, in the release and the repository.</p>
              )}
            </section>
          );
        })}
      </>
    );
  }

  return (
    <>
      <PageHeader crumbs={[{ label: "Documentation", href: docHref() }, { label: page.title }]} title={page.title} />
      <div ref={article} data-keeps-focus className={contents.length > 2 ? "km-article km-article--toc" : "km-article"}>
        {contents.length > 2 && (
          <nav aria-labelledby="docs-on-this-page" className="km-card km-toc">
            <p id="docs-on-this-page" className="km-toc-title">On this page</p>
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
        <div className="km-card doc-body" dangerouslySetInnerHTML={{ __html: html }} />
      </div>
    </>
  );
}

function DocCard({ page: p }: { page: DocPage }) {
  const news = p.id === WHATS_NEW;
  return (
    <a className="km-card km-gallery-card km-link-card" href={docHref(p.id)}>
      <span className="with-tile">
        <IconTile tint={news ? "orange" : "amber"}>
          <Icon name={news ? "whats-new" : "docs"} size={18} />
        </IconTile>
        <span className="km-row-title">{p.title}</span>
      </span>
    </a>
  );
}
