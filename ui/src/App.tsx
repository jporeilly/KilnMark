import { useEffect, useRef, useState, type FormEvent } from "react";
import { go, href, useRoute } from "./router";
import { HomePage } from "./pages/HomePage";
import { SearchPage } from "./pages/SearchPage";
import { AssetPage } from "./pages/AssetPage";
import { SourcesPage } from "./pages/SourcesPage";

/** App shell: skip link, search bar (Ctrl+K), main navigation and the current page. */
export function App() {
  const route = useRoute();
  const searchBox = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState(route.page === "search" ? route.q : "");
  const first = useRef(true);

  // Ctrl+K or / jumps to search from anywhere, as the design rules promise.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement;
      if ((e.key === "k" && (e.ctrlKey || e.metaKey)) || (e.key === "/" && !typing)) {
        e.preventDefault();
        searchBox.current?.focus();
        searchBox.current?.select();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // After moving to another page, put focus on its heading so keyboard and screen-reader users start there.
  useEffect(() => {
    if (route.page === "search") setQuery(route.q);
    if (first.current) {
      first.current = false;
      return;
    }
    const t = window.setTimeout(() => document.querySelector<HTMLElement>("main h1")?.focus(), 0);
    return () => window.clearTimeout(t);
  }, [route]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (query.trim()) go(href.search(query.trim()));
  };

  const current = (page: string) => (route.page === page ? "page" : undefined);

  return (
    <>
      <a href="#main" className="skip" onClick={(e) => {
        e.preventDefault();
        document.getElementById("main")?.focus();
      }}>
        Skip to main content
      </a>
      <div className="shell">
        <header className="topbar">
          <a className="brand" href={href.home()}>
            <span className="brand-mark" aria-hidden="true">K</span>
            Kilnmark
          </a>
          <form role="search" className="searchbar" onSubmit={submit}>
            <label htmlFor="q" className="visually-hidden">
              Search the catalogue
            </label>
            <input
              id="q"
              ref={searchBox}
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search tables, views and columns"
              autoComplete="off"
              aria-describedby="q-hint"
            />
            <span id="q-hint" className="kbd-hint">
              <kbd>Ctrl</kbd>+<kbd>K</kbd>
            </span>
            <button type="submit">Search</button>
          </form>
        </header>
        <nav aria-label="Main" className="sidenav">
          <ul>
            <li>
              <a href={href.home()} aria-current={current("home")}>
                Home
              </a>
            </li>
            <li>
              <a href={href.sources()} aria-current={current("sources")}>
                Sources
              </a>
            </li>
          </ul>
        </nav>
        <main id="main" tabIndex={-1}>
          {route.page === "home" && <HomePage />}
          {route.page === "search" && <SearchPage q={route.q} />}
          {route.page === "asset" && <AssetPage urn={route.urn} />}
          {route.page === "sources" && <SourcesPage />}
        </main>
      </div>
    </>
  );
}
