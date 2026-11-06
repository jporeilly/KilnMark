import { useEffect, useState } from "react";

/** Routes live in the hash, so the server needs no rewrite rules. */
export type Route =
  | { page: "search"; q: string; ask: boolean }
  | { page: "asset"; urn: string; tab: string | null }
  | { page: "sources"; add: boolean }
  /** A source; schema and table open that table in its Explore section (ADR 0045). */
  | { page: "source"; name: string; schema: string | null; table: string | null }
  | { page: "tokens" }
  | { page: "settings" }
  // Notifications: each person's own choices; email settings and the send log for admins
  | { page: "notifications"; confirm: string | null }
  | { page: "email" }
  | { page: "email-log" }
  // The audit log, for admins
  | { page: "audit" }
  // Who can do what: tokens and provider sign-ins, for admins (ADR 0037)
  | { page: "access-review" }
  // Drivers the customer installs, for admins
  | { page: "drivers" }
  // JDBC drivers admins register for the generic JDBC connector
  | { page: "jdbc-drivers" }
  | { page: "rules"; urn: string }
  | { page: "issues" }
  | { page: "signout" }
  | { page: "ai" }
  | { page: "ai-usage" }
  | { page: "home" }
  // The bundled user guides (src/docs): a page, and optionally a section of it
  | { page: "docs"; doc: string | null; section: string | null }
  // Browsing and relationships
  | { page: "browse"; source: string | null; container: string | null; asset: string | null }
  | { page: "relationships"; status: string }
  // Pipelines: the list, one pipeline (a new one when name is null), and one run
  | { page: "pipelines" }
  /** template: a template's file (samples/templates/...) a new pipeline starts from. */
  | { page: "pipeline"; name: string | null; version: number | null; template?: string | null }
  | { page: "pipeline-run"; id: number }
  // Flows: the list, one flow (a new one when name is null), and one run
  | { page: "flows" }
  | { page: "flow"; name: string | null; version: number | null; template?: string | null }
  /** Every schedule on the server (ADR 0046): all, on, paused, paused as its setter lost the role, or failed last time. */
  | { page: "schedules"; show: ScheduleFilter }
  /** The library of samples and templates (ADR 0043), on one of its two tabs. */
  | { page: "library"; tab: "samples" | "templates" }
  | { page: "flow-run"; id: number };

/** Which schedules the Schedules page shows; "setter": paused because whoever set them lost the role. */
export type ScheduleFilter = "all" | "on" | "paused" | "setter" | "failed";
const SCHEDULE_FILTERS: ScheduleFilter[] = ["all", "on", "paused", "setter", "failed"];

/** A path part decoded, or as it is when it is not valid percent-encoding. */
function decode(part: string): string {
  try {
    return decodeURIComponent(part);
  } catch {
    return part;
  }
}

export function parse(hash: string): Route {
  const [path, query = ""] = hash.replace(/^#/, "").split("?");
  const params = new URLSearchParams(query);
  if (path === "/search") return { page: "search", q: params.get("q") ?? "", ask: params.get("ask") === "1" };
  if (path.startsWith("/asset/")) return { page: "asset", urn: decode(path.slice("/asset/".length)), tab: params.get("tab") };
  if (path === "/sources") return { page: "sources", add: params.get("add") === "1" };
  if (path.startsWith("/source/"))
    return {
      page: "source",
      name: decode(path.slice("/source/".length)),
      schema: params.get("schema"),
      table: params.get("table"),
    };
  if (path === "/tokens") return { page: "tokens" };
  if (path === "/settings") return { page: "settings" };
  if (path === "/settings/notifications") return { page: "notifications", confirm: params.get("confirm") };
  if (path === "/settings/email") return { page: "email" };
  if (path === "/settings/email/sent") return { page: "email-log" };
  if (path === "/settings/audit") return { page: "audit" };
  if (path === "/settings/access-review") return { page: "access-review" };
  if (path === "/settings/drivers") return { page: "drivers" };
  if (path === "/settings/jdbc-drivers") return { page: "jdbc-drivers" };
  if (path === "/quality/rules") return { page: "rules", urn: params.get("urn") ?? "" };
  if (path === "/quality/issues") return { page: "issues" };
  if (path === "/sign-out") return { page: "signout" };
  if (path === "/ai") return { page: "ai" };
  if (path === "/ai/usage") return { page: "ai-usage" };
  if (path === "/browse")
    return {
      page: "browse",
      source: params.get("source"),
      container: params.get("container"),
      asset: params.get("asset"),
    };
  if (path === "/docs" || path.startsWith("/docs/")) {
    const [doc, section] = path.slice("/docs/".length).split("/").map((p) => (p ? decode(p) : null));
    return { page: "docs", doc: doc ?? null, section: section ?? null };
  }
  if (path === "/pipelines") return { page: "pipelines" };
  if (path === "/pipelines/new") return { page: "pipeline", name: null, version: null, template: params.get("template") };
  if (path.startsWith("/pipelines/")) {
    const v = Number(params.get("version"));
    return {
      page: "pipeline",
      name: decode(path.slice("/pipelines/".length)),
      version: Number.isInteger(v) && v > 0 ? v : null,
    };
  }
  if (path.startsWith("/pipeline-runs/")) {
    const id = Number(path.slice("/pipeline-runs/".length));
    if (Number.isInteger(id) && id > 0) return { page: "pipeline-run", id };
  }
  if (path === "/flows") return { page: "flows" };
  if (path === "/flows/new") return { page: "flow", name: null, version: null, template: params.get("template") };
  if (path === "/schedules") {
    const show = params.get("show") as ScheduleFilter | null;
    return { page: "schedules", show: show && SCHEDULE_FILTERS.includes(show) ? show : "all" };
  }
  if (path === "/library") return { page: "library", tab: params.get("tab") === "templates" ? "templates" : "samples" };
  if (path.startsWith("/flows/")) {
    const v = Number(params.get("version"));
    return { page: "flow", name: decode(path.slice("/flows/".length)), version: Number.isInteger(v) && v > 0 ? v : null };
  }
  if (path.startsWith("/flow-runs/")) {
    const id = Number(path.slice("/flow-runs/".length));
    if (Number.isInteger(id) && id > 0) return { page: "flow-run", id };
  }
  if (path === "/relationships") return { page: "relationships", status: params.get("status") ?? "proposed" };
  return { page: "home" };
}

export const href = {
  home: () => "#/",
  search: (q: string) => `#/search?q=${encodeURIComponent(q)}`,
  ask: (q: string) => `#/search?ask=1&q=${encodeURIComponent(q)}`,
  /** An asset, on one of its tabs (overview when tab is left out). */
  asset: (urn: string, tab?: string) => `#/asset/${encodeURIComponent(urn)}${tab ? `?tab=${encodeURIComponent(tab)}` : ""}`,
  sources: () => "#/sources",
  /** Sources, with the Add a connection gallery open. */
  addConnection: () => "#/sources?add=1",
  /** A source; with a schema (and a table), its Explore section opened there. */
  source: (name: string, schema?: string | null, table?: string | null) => {
    const q = new URLSearchParams();
    if (schema) q.set("schema", schema);
    if (schema && table) q.set("table", table);
    const query = q.toString();
    return `#/source/${encodeURIComponent(name)}${query ? `?${query}` : ""}`;
  },
  tokens: () => "#/tokens",
  settings: () => "#/settings",
  notifications: () => "#/settings/notifications",
  email: () => "#/settings/email",
  emailLog: () => "#/settings/email/sent",
  audit: () => "#/settings/audit",
  accessReview: () => "#/settings/access-review",
  drivers: () => "#/settings/drivers",
  jdbcDrivers: () => "#/settings/jdbc-drivers",
  rules: (urn?: string) => (urn ? `#/quality/rules?urn=${encodeURIComponent(urn)}` : "#/quality/rules"),
  issues: () => "#/quality/issues",
  signOut: () => "#/sign-out",
  ai: () => "#/ai",
  aiUsage: () => "#/ai/usage",
  browse: (source?: string | null, container?: string | null) => {
    const p = new URLSearchParams();
    if (source) p.set("source", source);
    if (source && container != null) p.set("container", container);
    const q = p.toString();
    return q ? `#/browse?${q}` : "#/browse";
  },
  /** A diagram of one asset and the assets related to it. */
  neighbours: (urn: string) => `#/browse?asset=${encodeURIComponent(urn)}`,
  pipelines: () => "#/pipelines",
  /** A new pipeline; with a template's file, starting from that template. */
  newPipeline: (template?: string) => `#/pipelines/new${template ? `?template=${encodeURIComponent(template)}` : ""}`,
  /** A pipeline; with a version, that saved version is shown read-only. */
  pipeline: (name: string, version?: number) =>
    `#/pipelines/${encodeURIComponent(name)}${version ? `?version=${version}` : ""}`,
  pipelineRun: (id: number) => `#/pipeline-runs/${id}`,
  flows: () => "#/flows",
  newFlow: (template?: string) => `#/flows/new${template ? `?template=${encodeURIComponent(template)}` : ""}`,
  /** The library of samples and templates. */
  library: (tab?: "samples" | "templates") => (tab === "templates" ? "#/library?tab=templates" : "#/library"),
  /** A flow; with a version, that saved version is shown read-only. */
  flow: (name: string, version?: number) => `#/flows/${encodeURIComponent(name)}${version ? `?version=${version}` : ""}`,
  flowRun: (id: number) => `#/flow-runs/${id}`,
  /** Every schedule; with a filter, only those on, paused or failed last time. */
  schedules: (show?: ScheduleFilter) => (show && show !== "all" ? `#/schedules?show=${show}` : "#/schedules"),
  relationships: (status = "proposed") => `#/relationships?status=${encodeURIComponent(status)}`,
};

export function go(to: string) {
  window.location.hash = to;
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parse(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parse(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}
