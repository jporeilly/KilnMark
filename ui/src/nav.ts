import { docHref, WHATS_NEW } from "./docs/links";
import { href, type Route } from "./router";

/**
 * The sidebar's entries, in order. Each has a Tabler icon (by name, src/components/icons.tsx) in
 * one of the sidebar's colours (--km-nav-* in tokens.css; "accent" follows the accent colour), and
 * says when it is the current page ("page") or holds the current page ("true", for a page inside
 * it, such as one source under Sources). `badge: "issues"` shows the open-issue count.
 *
 * To add an entry, add an object here; the sidebar, the icon rail and the drawer all follow.
 */
export type NavTint = "accent" | "sky" | "mint" | "violet" | "pink" | "green" | "red" | "amber" | "slate";

export type NavItem = {
  id: string;
  label: string;
  href: string;
  icon: string;
  tint: NavTint;
  /** "page" when this is the page, "true" when the page is inside it, else undefined. */
  current: (route: Route) => "page" | "true" | undefined;
  /** "issues": the open-issue count; "new": a New badge while What's new has not been seen. */
  badge?: "issues" | "new";
  /** Entries after the main ones: "docs" (under a Documentation heading) and "secondary" (settings). */
  group?: "docs" | "secondary";
};

/** Headings shown above a group of entries (hidden on the icon rail, where a divider stands in). */
export const NAV_GROUPS: Record<NonNullable<NavItem["group"]>, string | null> = { docs: "Documentation", secondary: null };

const on = (...pages: string[]) => (route: Route) => (pages.includes(route.page) ? ("page" as const) : undefined);

export const NAV: NavItem[] = [
  { id: "home", label: "Home", href: href.home(), icon: "home", tint: "accent", current: on("home") },
  {
    id: "sources",
    label: "Sources",
    href: href.sources(),
    icon: "sources",
    tint: "sky",
    current: (r) => (r.page === "sources" ? "page" : r.page === "source" ? "true" : undefined),
  },
  // In the order the work goes: find the data and how it joins (Browse holds the catalogue and the
  // relationships between its tables, as two views), set what good looks like, then build the
  // pipeline; issues last.
  {
    id: "browse",
    label: "Browse",
    href: href.browse(),
    icon: "browse",
    tint: "violet",
    current: (r) => (r.page === "browse" ? "page" : r.page === "relationships" ? "true" : undefined),
  },
  { id: "rules", label: "Quality rules", href: href.rules(), icon: "rules", tint: "green", current: on("rules") },
  {
    id: "pipelines",
    label: "Pipelines",
    href: href.pipelines(),
    icon: "pipelines",
    tint: "mint",
    current: (r) => (r.page === "pipelines" ? "page" : r.page === "pipeline" || r.page === "pipeline-run" ? "true" : undefined),
  },
  // Flows run pipelines in an order, so they come right after them.
  {
    id: "flows",
    label: "Flows",
    href: href.flows(),
    icon: "flows",
    tint: "sky",
    current: (r) => (r.page === "flows" ? "page" : r.page === "flow" || r.page === "flow-run" ? "true" : undefined),
  },
  // Samples and templates, apart from a team's own pipelines and flows (ADR 0043).
  { id: "library", label: "Library", href: href.library(), icon: "library", tint: "amber", current: on("library") },
  { id: "issues", label: "Issues", href: href.issues(), icon: "issues", tint: "red", current: on("issues"), badge: "issues" },
  {
    id: "whats-new",
    label: "What's new",
    href: docHref(WHATS_NEW),
    icon: "whats-new",
    tint: "amber",
    current: (r) => (r.page === "docs" && r.doc === WHATS_NEW ? "page" : undefined),
    badge: "new",
    group: "docs",
  },
  {
    id: "docs",
    label: "All guides",
    href: docHref(),
    icon: "docs",
    tint: "amber",
    current: (r) => (r.page === "docs" ? (r.doc && r.doc !== WHATS_NEW ? "true" : r.doc ? undefined : "page") : undefined),
    group: "docs",
  },
  {
    id: "settings",
    label: "Settings",
    href: href.settings(),
    icon: "settings",
    tint: "slate",
    current: on("settings", "tokens", "ai", "ai-usage", "notifications", "email", "email-log", "audit"),
    group: "secondary",
  },
];
