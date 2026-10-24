import { useEffect, useRef, useState, type FormEvent } from "react";
import { allows, api, ApiError, session, SIGNED_OUT, type Me } from "./api";
import { go, href, useRoute } from "./router";
import { HomePage } from "./pages/HomePage";
import { SearchPage } from "./pages/SearchPage";
import { AssetPage } from "./pages/AssetPage";
import { SourcesPage } from "./pages/SourcesPage";
import { SignInPage, type SignInNotice } from "./pages/SignInPage";
import { TokensPage } from "./pages/TokensPage";

/**
 * Checks who is signed in, then shows either the sign-in page or the app. Signing out (the
 * #/sign-out link) and a token the server stops accepting both return to sign-in, with a message.
 */
export function App() {
  const route = useRoute();
  const [me, setMe] = useState<Me | null>(null);
  const [checking, setChecking] = useState(true);
  const [notice, setNotice] = useState<SignInNotice | null>(null);

  // Is there a usable token in this tab already (or is sign-in turned off on the server)?
  useEffect(() => {
    api
      .me()
      .then(setMe)
      .catch((e) => {
        if (!(e instanceof ApiError) || e.status !== 401) setNotice({ kind: "error", text: (e as Error).message });
      })
      .finally(() => setChecking(false));
  }, []);

  useEffect(() => {
    const onSignedOut = (e: Event) => {
      setMe(null);
      setNotice({ kind: "error", text: (e as CustomEvent<string>).detail });
    };
    window.addEventListener(SIGNED_OUT, onSignedOut);
    return () => window.removeEventListener(SIGNED_OUT, onSignedOut);
  }, []);

  useEffect(() => {
    if (route.page === "signout" && me?.signInRequired) {
      session.clear();
      setMe(null);
      setNotice({ kind: "info", text: "You have signed out." });
    }
  }, [route, me]);

  if (checking) {
    return (
      <main id="main" className="signin">
        <p role="status">Loading Kilnmark…</p>
      </main>
    );
  }
  if (!me) {
    return (
      <main id="main" tabIndex={-1}>
        <SignInPage
          notice={notice}
          onSignedIn={(who) => {
            setMe(who);
            setNotice(null);
            if (route.page === "signout") go(href.home());
          }}
        />
      </main>
    );
  }
  return <Shell me={me} />;
}

/** App shell: skip link, search bar (Ctrl+K), who is signed in, main navigation and the current page. */
function Shell({ me }: { me: Me }) {
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
          <div className="who">
            <span>
              {me.signInRequired ? "Signed in as " : ""}
              <strong>{me.name}</strong> <span className="muted">({me.role})</span>
            </span>
            {me.signInRequired && <a href={href.signOut()}>Sign out</a>}
          </div>
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
            {allows(me.role, "admin") && (
              <li>
                <a href={href.tokens()} aria-current={current("tokens")}>
                  API tokens
                </a>
              </li>
            )}
          </ul>
        </nav>
        <main id="main" tabIndex={-1}>
          {route.page === "home" && <HomePage />}
          {route.page === "search" && <SearchPage q={route.q} />}
          {route.page === "asset" && <AssetPage urn={route.urn} />}
          {route.page === "sources" && <SourcesPage role={me.role} />}
          {route.page === "tokens" &&
            (allows(me.role, "admin") ? (
              <TokensPage me={me} />
            ) : (
              <>
                <h1 tabIndex={-1}>API tokens</h1>
                <p>Only admins can manage API tokens. Ask an admin if you need a token.</p>
              </>
            ))}
        </main>
      </div>
    </>
  );
}
