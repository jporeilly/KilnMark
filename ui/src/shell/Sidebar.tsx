import { forwardRef } from "react";
import { Brand } from "../components/Brand";
import { Icon } from "../components/icons";
import { NAV, NAV_GROUPS } from "../nav";
import { href, type Route } from "../router";

/**
 * The dark sidebar: the logo tile, the main navigation with coloured icons (the current page on
 * an accent-tinted row, said by aria-current), the open-issue count, Documentation with What's
 * new first (badged New until this browser has opened it for this version), and the version. Under 1200 px it narrows to an icon rail (names stay readable to screen readers
 * and show as tooltips); under 768 px it is a drawer opened from the top bar.
 */
export const Sidebar = forwardRef<HTMLDivElement, {
  route: Route;
  version: string | null;
  /** The open-issue count as shown ("12", "500+"), or null for none or unknown. */
  openIssues: string | null;
  /** What's new has changed since this browser last opened it. */
  whatsNew: boolean;
  drawerOpen: boolean;
  onNavigate: () => void;
  onCloseDrawer: () => void;
  inert?: boolean;
}>(function Sidebar({ route, version, openIssues, whatsNew, drawerOpen, onNavigate, onCloseDrawer, inert }, ref) {
  const item = (n: (typeof NAV)[number]) => {
    const current = n.current(route);
    const count = n.badge === "issues" ? openIssues : null;
    return (
      <li key={n.id}>
        <a
          href={n.href}
          className={`nav-item nav-tint-${n.tint}`}
          aria-current={current}
          data-tip={n.label}
          onClick={onNavigate}
        >
          <Icon name={n.icon} size={18} className="nav-icon" />
          <span className="nav-label">{n.label}</span>
          {count && (
            <span className="nav-badge num">
              <span className="visually-hidden">, </span>
              {count}
              <span className="visually-hidden"> open</span>
            </span>
          )}
          {n.badge === "new" && whatsNew && (
            <span className="nav-badge nav-badge--new">
              <span className="visually-hidden">, </span>
              <span className="nav-badge-text">New</span>
              <span className="visually-hidden"> since you last looked</span>
            </span>
          )}
        </a>
      </li>
    );
  };
  return (
    <div className="sidebar-col">
    <div
      ref={ref}
      className={drawerOpen ? "sidebar is-open" : "sidebar"}
      // As a drawer it is a modal dialog: the page behind is inert until it closes.
      role={drawerOpen ? "dialog" : undefined}
      aria-modal={drawerOpen ? true : undefined}
      aria-label={drawerOpen ? "Menu" : undefined}
      inert={inert}
      id="sidebar"
    >
      <div className="sidebar-top">
        <a className="brand" href={href.home()} onClick={onNavigate}>
          <Brand />
        </a>
        {drawerOpen && (
          <button type="button" className="km-icon-button sidebar-close" onClick={onCloseDrawer} aria-label="Close the menu">
            <Icon name="close" size={20} />
          </button>
        )}
      </div>
      <nav aria-label="Main" className="sidenav">
        <ul className="nav-list">{NAV.filter((n) => !n.group).map(item)}</ul>
        {(Object.keys(NAV_GROUPS) as (keyof typeof NAV_GROUPS)[]).map((g) => {
          const heading = NAV_GROUPS[g];
          const items = NAV.filter((n) => n.group === g);
          if (!items.length) return null;
          return (
            <div key={g} className="nav-group">
              {heading && <div className="nav-divider" role="presentation" />}
              {heading && (
                <p className="nav-group-title" id={`nav-${g}`}>
                  {heading}
                </p>
              )}
              <ul className="nav-list" aria-labelledby={heading ? `nav-${g}` : undefined}>
                {items.map(item)}
              </ul>
            </div>
          );
        })}
      </nav>
      {version && (
        <p className="sidebar-foot nav-version">
          <span className="nav-version-text">Kilnmark {version}</span>
          <span className="nav-version-short" aria-hidden="true">
            {version}
          </span>
        </p>
      )}
    </div>
    </div>
  );
});
