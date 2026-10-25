import { useEffect, useState } from "react";

/** Routes live in the hash, so the server needs no rewrite rules. */
export type Route =
  | { page: "search"; q: string; ask: boolean }
  | { page: "asset"; urn: string }
  | { page: "sources" }
  | { page: "source"; name: string }
  | { page: "tokens" }
  | { page: "settings" }
  // Notifications: each person's own choices; email settings and the send log for admins
  | { page: "notifications" }
  | { page: "email" }
  | { page: "email-log" }
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
  | { page: "relationships"; status: string };

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
  if (path.startsWith("/asset/")) return { page: "asset", urn: decode(path.slice("/asset/".length)) };
  if (path === "/sources") return { page: "sources" };
  if (path.startsWith("/source/")) return { page: "source", name: decode(path.slice("/source/".length)) };
  if (path === "/tokens") return { page: "tokens" };
  if (path === "/settings") return { page: "settings" };
  if (path === "/settings/notifications") return { page: "notifications" };
  if (path === "/settings/email") return { page: "email" };
  if (path === "/settings/email/sent") return { page: "email-log" };
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
  if (path === "/relationships") return { page: "relationships", status: params.get("status") ?? "proposed" };
  return { page: "home" };
}

export const href = {
  home: () => "#/",
  search: (q: string) => `#/search?q=${encodeURIComponent(q)}`,
  ask: (q: string) => `#/search?ask=1&q=${encodeURIComponent(q)}`,
  asset: (urn: string) => `#/asset/${encodeURIComponent(urn)}`,
  sources: () => "#/sources",
  source: (name: string) => `#/source/${encodeURIComponent(name)}`,
  tokens: () => "#/tokens",
  settings: () => "#/settings",
  notifications: () => "#/settings/notifications",
  email: () => "#/settings/email",
  emailLog: () => "#/settings/email/sent",
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
