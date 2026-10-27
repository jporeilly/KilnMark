import React, { lazy, Suspense, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { allows, api, ApiError, qualityApi, session, SIGNED_OUT, type Me } from "./api";
import { go, href, useRoute } from "./router";
import { HomePage } from "./pages/HomePage";
import { SearchPage } from "./pages/SearchPage";
import { AssetPage } from "./pages/AssetPage";
import { SourcesPage } from "./pages/SourcesPage";
import { SourcePage } from "./pages/SourcePage";
import { SignInPage, type SignInNotice } from "./pages/SignInPage";
import { SettingsPage } from "./pages/SettingsPage";
import { BrowsePage } from "./browse/BrowsePage";
import { RoleContext } from "./components/role";
import { IssuesPage } from "./pages/IssuesPage";
import "./docs/docs.css";
import { AssistantPanel } from "./assistant/AssistantPanel";
import { useAssistant } from "./assistant/useAssistant";

// Heavier pages load when first opened, so the main bundle stays small: the documentation (the
// guides and a Markdown reader), pipelines (the YAML editor and diagram), the rule form, AI models,
// tokens and relationships.
const DocsPage = lazy(() => import("./pages/DocsPage"));
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const page = <T extends Record<K, React.ComponentType<any>>, K extends keyof T>(load: () => Promise<T>, key: K) =>
  lazy(() => load().then((m) => ({ default: m[key] })));
const AiPage = page(() => import("./pages/AiPage"), "AiPage");
const AiUsagePage = page(() => import("./pages/AiUsagePage"), "AiUsagePage");
const TokensPage = page(() => import("./pages/TokensPage"), "TokensPage");
const PipelinesPage = page(() => import("./pipelines/PipelinesPage"), "PipelinesPage");
const PipelinePage = page(() => import("./pipelines/PipelinePage"), "PipelinePage");
const PipelineRunPage = page(() => import("./pipelines/PipelineRunPage"), "PipelineRunPage");
const RulesPage = page(() => import("./pages/RulesPage"), "RulesPage");
const RelationshipsPage = page(() => import("./browse/RelationshipsPage"), "RelationshipsPage");
import { Icon } from "./components/icons";
import { Card, EmptyState, PageHeader } from "./components/ui";
import { NotificationBell } from "./notifications/NotificationBell";
import { WHATS_NEW } from "./docs/links";
import { Sidebar } from "./shell/Sidebar";
import { AskButton, SearchForm, UserMenu } from "./shell/TopBar";

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
        <p role="status" className="muted">Loading Kilnmark…</p>
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

const SEEN_KEY = "kilnmark.whatsNewSeen";

/**
 * Whether to badge What's new: the version has changed since this browser last opened that page.
 * Kept in localStorage (a per-viewer convenience; storage may be blocked, so every access is
 * guarded and the badge simply shows). Opening the page records the version.
 */
function useWhatsNewBadge(version: string | null, onPage: boolean): boolean {
  const read = () => {
    try {
      return window.localStorage.getItem(SEEN_KEY);
    } catch {
      return null;
    }
  };
  const [seen, setSeen] = useState<string | null>(read);
  useEffect(() => {
    if (!onPage || !version) return;
    try {
      window.localStorage.setItem(SEEN_KEY, version);
    } catch {
      // Storage blocked: the badge goes for this page load only.
    }
    setSeen(version);
  }, [onPage, version]);
  return version != null && seen !== version;
}

/** Whether a media query matches now, following changes. */
function useMedia(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia?.(query).matches ?? false);
  useEffect(() => {
    const m = window.matchMedia?.(query);
    if (!m) return;
    const onChange = () => setMatches(m.matches);
    onChange();
    m.addEventListener("change", onChange);
    return () => m.removeEventListener("change", onChange);
  }, [query]);
  return matches;
}

/**
 * App shell: skip link, the dark sidebar (an icon rail under 1200 px, a drawer under 768 px), the
 * top bar with search (Ctrl+K), Ask Kilnmark (Alt+A), notifications and the user menu, and the
 * current page.
 */
function Shell({ me }: { me: Me }) {
  const route = useRoute();
  const searchBox = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState(route.page === "search" ? route.q : "");
  const first = useRef(true);
  const [version, setVersion] = useState<string | null>(null);
  const [openIssues, setOpenIssues] = useState<string | null>(null);
  const whatsNew = useWhatsNewBadge(version, route.page === "docs" && route.doc === WHATS_NEW);
  // The Ask panel (src/assistant): its conversation lives here, so it stays across pages and goes on sign-out.
  const assistant = useAssistant();
  const [askOpen, setAskOpen] = useState(false);
  const [askFocus, setAskFocus] = useState(0);
  const askReturn = useRef<HTMLElement | null>(null);
  const askButton = useRef<HTMLButtonElement>(null);
  const openAsk = () => {
    if (!document.getElementById("assistant")?.contains(document.activeElement)) {
      askReturn.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    }
    setAskOpen(true);
    setAskFocus((n) => n + 1);
  };
  // Below 1100px the panel covers the page, so the page behind it is inert (the panel acts as a modal).
  const narrow = useMedia("(max-width: 1099px)");
  const covered = askOpen && narrow;
  const closeAsk = (returnFocus = true) => {
    setAskOpen(false);
    if (!returnFocus) return; // a link was followed: the page it opened takes focus
    const back = askReturn.current;
    // Back where focus was, or to the Ask button if that was nowhere in particular or has gone.
    const target = back && back !== document.body && document.contains(back) ? back : askButton.current;
    window.setTimeout(() => target?.focus(), 0);
  };

  // Under 768px the sidebar is a drawer: a modal dialog over the page, opened from the top bar.
  const drawerMode = useMedia("(max-width: 767px)");
  const [drawer, setDrawer] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const sidebar = useRef<HTMLDivElement>(null);
  const drawerOpen = drawer && drawerMode;
  const closeDrawer = (returnFocus = true) => {
    setDrawer(false);
    if (returnFocus) window.setTimeout(() => menuButton.current?.focus(), 0);
  };
  useEffect(() => {
    if (!drawerMode) setDrawer(false);
  }, [drawerMode]);
  useEffect(() => {
    if (!drawerOpen) return;
    // After the drawer becomes visible (it slides in), focus its first item.
    const raf = window.requestAnimationFrame(() => sidebar.current?.querySelector<HTMLElement>(".nav-item")?.focus());
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        closeDrawer();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      window.cancelAnimationFrame(raf);
      document.removeEventListener("keydown", onKey);
    };
  }, [drawerOpen]);

  useEffect(() => {
    api
      .info()
      .then((i) => setVersion(i.version))
      .catch(() => setVersion(null));
  }, []);

  // The open-issue count on the sidebar, refreshed as people move around (resolving one updates it).
  useEffect(() => {
    // The list stops at 500: a full list shows as "500+".
    qualityApi
      .issues("open", 500)
      .then((list) => setOpenIssues(list.length === 0 ? null : list.length >= 500 ? "500+" : String(list.length)))
      .catch(() => setOpenIssues(null));
  }, [route]);

  const openAskRef = useRef(openAsk);
  openAskRef.current = openAsk;

  // Ctrl+K or / jumps to search from anywhere, as the design rules promise.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const typing = e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement;
      if ((e.key === "k" && (e.ctrlKey || e.metaKey)) || (e.key === "/" && !typing)) {
        e.preventDefault();
        searchBox.current?.focus();
        searchBox.current?.select();
      }
      // Alt+A opens the Ask panel, or moves focus into it. By the letter typed, so it is A on AZERTY
      // keyboards too; by the key where the layout types no Latin letter (Cyrillic, Greek), and on a
      // Mac, where Option changes the letter (å), and not while typing there.
      const mac = /Mac/.test(navigator.platform);
      const latin = /^[a-z]$/i.test(e.key);
      const isA = mac ? e.code === "KeyA" && !typing : latin ? e.key.toLowerCase() === "a" : e.code === "KeyA";
      if (e.altKey && !e.ctrlKey && !e.metaKey && isA) {
        e.preventDefault();
        openAskRef.current();
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
      // A widget that changes the page in place (the browse tree, tabs) keeps the focus where it is.
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

  return (
    <>
      <a href="#main" className="skip" inert={covered || drawerOpen} onClick={(e) => {
        e.preventDefault();
        document.getElementById("main")?.focus();
      }}>
        Skip to main content
      </a>
      <div className={`shell${askOpen ? " assistant-open" : ""}${drawerOpen ? " drawer-open" : ""}`}>
        <Sidebar
          ref={sidebar}
          route={route}
          version={version}
          openIssues={openIssues}
          whatsNew={whatsNew}
          drawerOpen={drawerOpen}
          onNavigate={() => drawerOpen && closeDrawer(false)}
          onCloseDrawer={() => closeDrawer()}
          inert={covered}
        />
        {drawerOpen && <div className="drawer-backdrop" aria-hidden="true" onClick={() => closeDrawer()} />}
        <div className="workspace" inert={covered || drawerOpen}>
          <header className="topbar">
            <button
              type="button"
              ref={menuButton}
              className="km-icon-button menu-button"
              aria-label="Open the menu"
              aria-expanded={drawerOpen}
              aria-controls="sidebar"
              onClick={() => setDrawer(true)}
            >
              <Icon name="menu" size={22} />
            </button>
            <SearchForm query={query} setQuery={setQuery} onSubmit={submit} inputRef={searchBox} />
            <div className="topbar-actions">
              <AskButton ref={askButton} open={askOpen} onClick={() => (askOpen ? closeAsk() : openAsk())} />
              <NotificationBell role={me.role} />
              <UserMenu me={me} />
            </div>
          </header>
          <RoleContext.Provider value={me.role}>
          <main id="main" tabIndex={-1}>
          <Suspense fallback={<p role="status" className="muted">Loading…</p>}>
          {route.page === "home" && <HomePage me={me} />}
          {route.page === "settings" && <SettingsPage role={me.role} />}
          {route.page === "search" && <SearchPage q={route.q} ask={route.ask} />}
          {route.page === "asset" && <AssetPage urn={route.urn} tab={route.tab} />}
          {route.page === "sources" && <SourcesPage role={me.role} add={route.add} />}
          {route.page === "source" && <SourcePage name={route.name} role={me.role} />}
          {(route.page === "ai" || route.page === "ai-usage") &&
            (allows(me.role, "admin") ? (
              route.page === "ai" ? <AiPage /> : <AiUsagePage />
            ) : (
              <NotAllowed title="AI models">Only admins can manage AI models. Ask an admin if a feature needs a model.</NotAllowed>
            ))}
          {route.page === "browse" && (
            <BrowsePage source={route.source} container={route.container} asset={route.asset} />
          )}
          {route.page === "relationships" && <RelationshipsPage status={route.status} />}
          {route.page === "rules" && <RulesPage role={me.role} urn={route.urn} />}
          {route.page === "issues" && <IssuesPage role={me.role} />}
          {route.page === "pipelines" && <PipelinesPage role={me.role} />}
          {route.page === "pipeline" && (
            <PipelinePage key={route.name ?? "#new"} name={route.name} version={route.version} role={me.role} />
          )}
          {route.page === "pipeline-run" && <PipelineRunPage id={route.id} role={me.role} />}
          {route.page === "docs" && <DocsPage doc={route.doc} section={route.section} />}
          {route.page === "tokens" &&
            (allows(me.role, "admin") ? (
              <TokensPage me={me} />
            ) : (
              <NotAllowed title="API tokens">Only admins can manage API tokens. Ask an admin if you need a token.</NotAllowed>
            ))}
          </Suspense>
          </main>
          </RoleContext.Provider>
        </div>
        {askOpen && (
          <AssistantPanel
            route={route}
            state={assistant}
            onClose={closeAsk}
            focusSignal={askFocus}
            covering={narrow}
          />
        )}
      </div>
    </>
  );
}

/** A page this role cannot use: says so, and who can help. */
function NotAllowed({ title, children }: { title: string; children: ReactNode }) {
  return (
    <>
      <PageHeader title={title} />
      <Card>
        <EmptyState icon={<Icon name="key" size={22} />} title="Admins only">
          {children}
        </EmptyState>
      </Card>
    </>
  );
}
