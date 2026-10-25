import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from "react";
import { allows, api, ApiError, session, SIGNED_OUT, type Me } from "./api";
import { go, href, useRoute } from "./router";
import { HomePage } from "./pages/HomePage";
import { SearchPage } from "./pages/SearchPage";
import { AssetPage } from "./pages/AssetPage";
import { SourcesPage } from "./pages/SourcesPage";
import { SourcePage } from "./pages/SourcePage";
import { SignInPage, type SignInNotice } from "./pages/SignInPage";
import { TokensPage } from "./pages/TokensPage";
import { AiPage } from "./pages/AiPage";
import { SettingsPage } from "./pages/SettingsPage";
import { AiUsagePage } from "./pages/AiUsagePage";
import { BrowsePage } from "./browse/BrowsePage";
import { RelationshipsPage } from "./browse/RelationshipsPage";
import { RoleContext } from "./components/role";
import { RulesPage } from "./pages/RulesPage";
import { IssuesPage } from "./pages/IssuesPage";
import { docHref, WHATS_NEW } from "./docs/links";
import "./docs/docs.css";

// The documentation viewer carries the guides and a Markdown reader, so it loads when first opened.
const DocsPage = lazy(() => import("./pages/DocsPage"));
import { Brand } from "./components/Brand";
import { ThemeQuickSwitch } from "./components/ThemeQuickSwitch";

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
  const [version, setVersion] = useState<string | null>(null);

  useEffect(() => {
    api
      .info()
      .then((i) => setVersion(i.version))
      .catch(() => setVersion(null));
  }, []);

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
    const t = window.setTimeout(() => {
      // A widget that changes the page in place (the browse tree) keeps the focus where it is.
      if (document.activeElement?.closest("[data-keeps-focus]")) return;
      document.querySelector<HTMLElement>("main h1")?.focus();
    }, 0);
    return () => window.clearTimeout(t);
  }, [route]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    // On the search page in "ask" mode, the search bar asks a question.
    if (query.trim()) go(route.page === "search" && route.ask ? href.ask(query.trim()) : href.search(query.trim()));
  };

  const current = (page: string) => (route.page === page ? "page" : undefined);
  const inSources = route.page === "sources" || route.page === "source";

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
            <Brand />
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
            <ThemeQuickSwitch />
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
              <a href={href.sources()} aria-current={route.page === "source" ? "true" : inSources ? "page" : undefined}>
                Sources
              </a>
            </li>
            <li>
              <a href={href.browse()} aria-current={current("browse")}>
                Browse
              </a>
            </li>
            <li>
              <a href={href.relationships()} aria-current={current("relationships")}>
                Relationships
              </a>
            </li>
            <li>
              <a href={href.rules()} aria-current={current("rules")}>
                Quality rules
              </a>
            </li>
            <li>
              <a href={href.issues()} aria-current={current("issues")}>
                Issues
              </a>
            </li>
            <li>
              <a href={docHref()} aria-current={current("docs")}>
                Documentation
              </a>
            </li>
            <li>
              <a
                href={href.settings()}
                aria-current={["settings", "tokens", "ai", "ai-usage"].includes(route.page) ? "page" : undefined}
              >
                Settings
              </a>
            </li>
          </ul>
          <p className="small muted nav-version">
            {version && <>Kilnmark {version} · </>}
            <a href={docHref(WHATS_NEW)}>What's new</a>
          </p>
        </nav>
        <RoleContext.Provider value={me.role}>
        <main id="main" tabIndex={-1}>
          {route.page === "home" && <HomePage />}
          {route.page === "settings" && <SettingsPage role={me.role} />}
          {route.page === "search" && <SearchPage q={route.q} ask={route.ask} />}
          {route.page === "asset" && <AssetPage urn={route.urn} />}
          {route.page === "sources" && <SourcesPage role={me.role} />}
          {route.page === "source" && <SourcePage name={route.name} role={me.role} />}
          {(route.page === "ai" || route.page === "ai-usage") &&
            (allows(me.role, "admin") ? (
              route.page === "ai" ? <AiPage /> : <AiUsagePage />
            ) : (
              <>
                <h1 tabIndex={-1}>AI models</h1>
                <p>Only admins can manage AI models. Ask an admin if a feature needs a model.</p>
              </>
            ))}
          {route.page === "browse" && (
            <BrowsePage source={route.source} container={route.container} asset={route.asset} />
          )}
          {route.page === "relationships" && <RelationshipsPage status={route.status} />}
          {route.page === "rules" && <RulesPage role={me.role} urn={route.urn} />}
          {route.page === "issues" && <IssuesPage role={me.role} />}
          {route.page === "docs" && (
            <Suspense fallback={<p role="status">Loading the documentation…</p>}>
              <DocsPage doc={route.doc} section={route.section} />
            </Suspense>
          )}
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
        </RoleContext.Provider>
      </div>
    </>
  );
}
