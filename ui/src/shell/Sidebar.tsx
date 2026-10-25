import { forwardRef } from "react";
import { Brand } from "../components/Brand";
import { Icon } from "../components/icons";
import { docHref, WHATS_NEW } from "../docs/links";
import { NAV } from "../nav";
import { href, type Route } from "../router";

/**
 * The dark sidebar: the logo tile, the main navigation with coloured icons (the current page on
 * an accent-tinted row, said by aria-current), the open-issue count, and the version with a link
 * to What's new. Under 1200 px it narrows to an icon rail (names stay readable to screen readers
 * and show as tooltips); under 768 px it is a drawer opened from the top bar.
 */
export const Sidebar = forwardRef<HTMLDivElement, {
  route: Route;
  version: string | null;
  openIssues: number | null;
  drawerOpen: boolean;
  onNavigate: () => void;
  onCloseDrawer: () => void;
  inert?: boolean;
}>(function Sidebar({ route, version, openIssues, drawerOpen, onNavigate, onCloseDrawer, inert }, ref) {
  const item = (n: (typeof NAV)[number]) => {
    const current = n.current(route);
    const count = n.badge === "issues" && openIssues ? openIssues : 0;
    return (
      <li key={n.id}>
        <a
          href={n.href}
          className={`nav-item nav-tint-${n.tint}`}
          aria-current={current}
          title={n.label}
          onClick={onNavigate}
        >
          <Icon name={n.icon} size={18} className="nav-icon" />
          <span className="nav-label">{n.label}</span>
          {count > 0 && (
            <span className="nav-badge num">
              {count}
              <span className="visually-hidden"> open</span>
            </span>
          )}
        </a>
      </li>
    );
  };
  return (
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
        <div className="nav-divider" role="presentation" />
        <ul className="nav-list">{NAV.filter((n) => n.group === "secondary").map(item)}</ul>
      </nav>
      <p className="sidebar-foot nav-version">
        <span className="nav-version-text">{version && <>Kilnmark {version} · </>}</span>
        <a href={docHref(WHATS_NEW)} onClick={onNavigate}>
          What's new
        </a>
      </p>
    </div>
  );
});
