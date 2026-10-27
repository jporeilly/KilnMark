import { useEffect, useState } from "react";

/** Routes live in the hash, so the server needs no rewrite rules. */
export type Route =
  | { page: "search"; q: string; ask: boolean }
  | { page: "asset"; urn: string; tab: string | null }
  | { page: "sources"; add: boolean }
  | { page: "source"; name: string }
  | { page: "tokens" }
  | { page: "settings" }
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
  | { page: "pipeline"; name: string | null; version: number | null }
  | { page: "pipeline-run"; id: number };

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
  if (path.startsWith("/source/")) return { page: "source", name: decode(path.slice("/source/".length)) };
  if (path === "/tokens") return { page: "tokens" };
  if (path === "/settings") return { page: "settings" };
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
  if (path === "/pipelines/new") return { page: "pipeline", name: null, version: null };
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
  source: (name: string) => `#/source/${encodeURIComponent(name)}`,
  tokens: () => "#/tokens",
  settings: () => "#/settings",
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
  newPipeline: () => "#/pipelines/new",
  /** A pipeline; with a version, that saved version is shown read-only. */
  pipeline: (name: string, version?: number) =>
    `#/pipelines/${encodeURIComponent(name)}${version ? `?version=${version}` : ""}`,
  pipelineRun: (id: number) => `#/pipeline-runs/${id}`,
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
