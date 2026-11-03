import { href } from "../router";

/**
 * Browse's two views: the catalogue (sources, schemas, tables and their diagram) and every
 * relationship, where proposals are reviewed. Links, as each view has its own address.
 */
export function BrowseTabs({ current }: { current: "catalogue" | "relationships" }) {
  return (
    <nav className="browse-tabs" aria-label="Browse">
      <a href={href.browse()} aria-current={current === "catalogue" ? "page" : undefined}>
        Catalogue
      </a>
      <a href={href.relationships()} aria-current={current === "relationships" ? "page" : undefined}>
        Relationships
      </a>
    </nav>
  );
}
