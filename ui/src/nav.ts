import { docHref } from "./docs/links";
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
  badge?: "issues";
  /** Entries after a divider: help and settings. */
  group?: "secondary";
};

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
  // Pipelines arrive from the pipelines branch. Add them here, after Sources, when that merges:
  // {
  //   id: "pipelines", label: "Pipelines", href: href.pipelines(), icon: "pipelines", tint: "mint",
  //   current: (r) => (r.page === "pipelines" ? "page" : ["pipeline", "pipeline-run"].includes(r.page) ? "true" : undefined),
  // },
  { id: "browse", label: "Browse", href: href.browse(), icon: "browse", tint: "violet", current: on("browse") },
  {
    id: "relationships",
    label: "Relationships",
    href: href.relationships(),
    icon: "relationships",
    tint: "pink",
    current: on("relationships"),
  },
  { id: "rules", label: "Quality rules", href: href.rules(), icon: "rules", tint: "green", current: on("rules") },
  { id: "issues", label: "Issues", href: href.issues(), icon: "issues", tint: "red", current: on("issues"), badge: "issues" },
  { id: "docs", label: "Documentation", href: docHref(), icon: "docs", tint: "amber", current: on("docs"), group: "secondary" },
  {
    id: "settings",
    label: "Settings",
    href: href.settings(),
    icon: "settings",
    tint: "slate",
    current: on("settings", "tokens", "ai", "ai-usage"),
    group: "secondary",
  },
];
