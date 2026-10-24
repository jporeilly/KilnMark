import { useEffect, useState } from "react";

/** Routes live in the hash, so the server needs no rewrite rules. */
export type Route =
  | { page: "search"; q: string }
  | { page: "asset"; urn: string }
  | { page: "sources" }
  | { page: "tokens" }
  | { page: "rules"; urn: string }
  | { page: "issues" }
  | { page: "signout" }
  | { page: "home" };

export function parse(hash: string): Route {
  const [path, query = ""] = hash.replace(/^#/, "").split("?");
  const params = new URLSearchParams(query);
  if (path === "/search") return { page: "search", q: params.get("q") ?? "" };
  if (path.startsWith("/asset/")) return { page: "asset", urn: decodeURIComponent(path.slice("/asset/".length)) };
  if (path === "/sources") return { page: "sources" };
  if (path === "/tokens") return { page: "tokens" };
  if (path === "/quality/rules") return { page: "rules", urn: params.get("urn") ?? "" };
  if (path === "/quality/issues") return { page: "issues" };
  if (path === "/sign-out") return { page: "signout" };
  return { page: "home" };
}

export const href = {
  home: () => "#/",
  search: (q: string) => `#/search?q=${encodeURIComponent(q)}`,
  asset: (urn: string) => `#/asset/${encodeURIComponent(urn)}`,
  sources: () => "#/sources",
  tokens: () => "#/tokens",
  rules: (urn?: string) => (urn ? `#/quality/rules?urn=${encodeURIComponent(urn)}` : "#/quality/rules"),
  issues: () => "#/quality/issues",
  signOut: () => "#/sign-out",
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
