import { useEffect, useState } from "react";

/** Routes live in the hash, so the server needs no rewrite rules. */
export type Route =
  | { page: "search"; q: string }
  | { page: "asset"; urn: string }
  | { page: "sources" }
  | { page: "tokens" }
  | { page: "signout" }
  | { page: "home" }
  // Browsing and relationships
  | { page: "browse"; source: string | null; container: string | null; asset: string | null }
  | { page: "relationships"; status: string };

export function parse(hash: string): Route {
  const [path, query = ""] = hash.replace(/^#/, "").split("?");
  const params = new URLSearchParams(query);
  if (path === "/search") return { page: "search", q: params.get("q") ?? "" };
  if (path.startsWith("/asset/")) return { page: "asset", urn: decodeURIComponent(path.slice("/asset/".length)) };
  if (path === "/sources") return { page: "sources" };
  if (path === "/tokens") return { page: "tokens" };
  if (path === "/sign-out") return { page: "signout" };
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
  asset: (urn: string) => `#/asset/${encodeURIComponent(urn)}`,
  sources: () => "#/sources",
  tokens: () => "#/tokens",
  signOut: () => "#/sign-out",
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
