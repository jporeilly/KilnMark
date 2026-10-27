import type { ReactNode, Ref } from "react";

export type Crumb = { label: ReactNode; href?: string };

/**
 * The top of every page: breadcrumbs, an optional tile or icon, the h1 (focusable, so moving to a
 * page puts focus on it), a line of facts under it, chips, and actions at the end. Pages should use
 * this rather than their own h1 so they all share the same rhythm.
 */
export function PageHeader({
  title,
  crumbs,
  tile,
  meta,
  chips,
  actions,
  lead,
  headingRef,
  titleClassName,
}: {
  title: ReactNode;
  crumbs?: Crumb[];
  tile?: ReactNode;
  meta?: ReactNode;
  chips?: ReactNode;
  actions?: ReactNode;
  lead?: ReactNode;
  headingRef?: Ref<HTMLHeadingElement>;
  titleClassName?: string;
}) {
  return (
    <div className="km-page-head">
      {crumbs && crumbs.length > 0 && (
        <nav aria-label="Breadcrumb" className="crumbs">
          <ol>
            {crumbs.map((c, i) => (
              <li key={i} aria-current={i === crumbs.length - 1 && !c.href ? "page" : undefined}>
                {c.href ? <a href={c.href}>{c.label}</a> : c.label}
              </li>
            ))}
          </ol>
        </nav>
      )}
      <div className="km-page-title-row">
        {tile && <div className="km-page-tile">{tile}</div>}
        <div className="km-page-title">
          <h1 tabIndex={-1} ref={headingRef} className={titleClassName}>
            {title}
          </h1>
          {meta && <div className="km-page-meta">{meta}</div>}
        </div>
        {chips && <div className="km-page-chips">{chips}</div>}
        {actions && <div className="km-page-actions">{actions}</div>}
      </div>
      {lead && <p className="lead">{lead}</p>}
    </div>
  );
}
