import { useEffect, useState } from "react";

/** Routes live in the hash, so the server needs no rewrite rules. */
export type Route =
  | { page: "search"; q: string; ask: boolean }
  | { page: "asset"; urn: string }
  | { page: "sources" }
  | { page: "source"; name: string }
  | { page: "tokens" }
  | { page: "signout" }
  | { page: "ai" }
  | { page: "ai-usage" }
  | { page: "home" }
  // Browsing and relationships
  | { page: "browse"; source: string | null; container: string | null; asset: string | null }
  | { page: "relationships"; status: string };

export function parse(hash: string): Route {
  const [path, query = ""] = hash.replace(/^#/, "").split("?");
  const params = new URLSearchParams(query);
  if (path === "/search") return { page: "search", q: params.get("q") ?? "", ask: params.get("ask") === "1" };
  if (path.startsWith("/asset/")) return { page: "asset", urn: decodeURIComponent(path.slice("/asset/".length)) };
  if (path === "/sources") return { page: "sources" };
  if (path.startsWith("/source/")) return { page: "source", name: decodeURIComponent(path.slice("/source/".length)) };
  if (path === "/tokens") return { page: "tokens" };
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
