import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { IconChevronDown, IconChevronUp } from "@tabler/icons-react";
import { Tabs, type TabItem } from "../components/ui";

/**
 * The tabs of the panel under the canvas, in their order: the selected node's (only while one is
 * selected), then the pipeline's.
 */
export type DockTab = "settings" | "preview" | "run" | "suggestions" | "nodes" | "runs" | "versions";
/** The selected node's tabs: shown only while a node is selected. */
export const NODE_TABS: ReadonlySet<DockTab> = new Set(["settings", "preview"]);
/** The tabs the canvas fills: they need the canvas shown, not the YAML. */
export const CANVAS_TABS: ReadonlySet<DockTab> = new Set(["settings", "preview", "suggestions"]);
const LABELS: Record<DockTab, string> = {
  settings: "Settings",
  preview: "Preview",
  run: "Run",
  suggestions: "Suggestions",
  nodes: "Nodes",
  runs: "Runs",
  versions: "Versions",
};

export const DOCK_OPEN_KEY = "kilnmark.pipeline.panel";

function savedOpen(key: string): boolean {
  try {
    return window.localStorage.getItem(key) !== "collapsed";
  } catch {
    return true;
  }
}

/** What the canvas asks of the panel: show a tab (opening the panel), and where it draws into it. */
export type DockControl = {
  tab: DockTab;
  open: boolean;
  /** Shows a tab and opens the panel. */
  show: (tab: DockTab) => void;
  /** Where the canvas draws the Settings, Preview and Suggestions tabs (null until it is on the page). */
  host: HTMLElement | null;
  /** The tab panel itself, for moving the focus to it. */
  panel: () => HTMLElement | null;
};

/**
 * The panel's state: the tab shown and whether it is open, remembered per viewer under `openKey`
 * (pages other than a pipeline's keep their own, so collapsing one does not collapse the other).
 */
export function useDock(idBase: string, openKey: string = DOCK_OPEN_KEY) {
  const [tab, setTab] = useState<DockTab>("settings");
  const [open, setOpenState] = useState<boolean>(() => savedOpen(openKey));
  const setOpen = useCallback(
    (o: boolean) => {
      setOpenState(o);
      try {
        window.localStorage.setItem(openKey, o ? "open" : "collapsed");
      } catch {
        // A remembered panel is a convenience only.
      }
    },
    [openKey],
  );
  const show = useCallback(
    (t: DockTab) => {
      setTab(t);
      setOpen(true);
    },
    [setOpen],
  );
  const panel = useCallback(() => document.getElementById(`${idBase}-panel`), [idBase]);
  return { tab, open, setOpen, show, setTab, panel };
}

/**
 * The panel under the canvas: one tab bar (the ARIA tabs pattern: arrow keys, Home and End move
 * between the tabs and show them) and one panel area with its own scroll, so the canvas keeps the
 * page's width and nothing covers it. It can be collapsed to the tab bar; choosing a tab opens it.
 * Tabs come and go (the node's with the selection): when the one shown goes while the focus was in
 * the panel and is lost with it, the focus moves to the tab shown in its place.
 */
export function PipelineDock({
  idBase,
  tabs,
  tab,
  open,
  onTab,
  onOpen,
  label = "Panels under the canvas",
  children,
}: {
  idBase: string;
  tabs: DockTab[];
  tab: DockTab;
  open: boolean;
  onTab: (t: DockTab) => void;
  onOpen: (open: boolean) => void;
  /** The tab bar's name for screen readers. */
  label?: string;
  children: ReactNode;
}) {
  const items: TabItem[] = tabs.map((t) => ({ id: t, label: LABELS[t] }));
  const box = useRef<HTMLDivElement>(null);
  // Whether the focus is (or was, when what held it was removed) in the panel.
  const hadFocus = useRef(false);
  // Listened to in the DOM, not React's tree: the canvas draws into the panel through a portal.
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const into = () => {
      hadFocus.current = true;
    };
    const out = (e: FocusEvent) => {
      // Moved elsewhere on the page; a focus lost with no new place (an element removed) is kept.
      if (e.relatedTarget && !el.contains(e.relatedTarget as Node)) hadFocus.current = false;
    };
    el.addEventListener("focusin", into);
    el.addEventListener("focusout", out);
    return () => {
      el.removeEventListener("focusin", into);
      el.removeEventListener("focusout", out);
    };
  }, []);
  const shown = `${tab}|${tabs.join(",")}`;
  useEffect(() => {
    const a = document.activeElement;
    if (hadFocus.current && (!a || a === document.body || !a.isConnected)) {
      document.getElementById(`${idBase}-tab-${tab}`)?.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shown]);
  return (
    <div ref={box} className={`pl-dock${open ? " is-open" : " is-collapsed"}`}>
      <div className="pl-dock-bar">
        <Tabs idBase={idBase} label={label} selected={tab} onSelect={(t) => onTab(t as DockTab)} tabs={items} />
        <button
          type="button"
          className="cv-tool pl-dock-toggle"
          aria-expanded={open}
          aria-controls={`${idBase}-panel`}
          onClick={() => onOpen(!open)}
        >
          {open ? <IconChevronDown size={18} stroke={1.8} aria-hidden="true" /> : <IconChevronUp size={18} stroke={1.8} aria-hidden="true" />}
          {open ? "Collapse" : "Expand"}
          <span className="visually-hidden"> the panel</span>
        </button>
      </div>
      <div
        role="tabpanel"
        id={`${idBase}-panel`}
        aria-labelledby={`${idBase}-tab-${tab}`}
        className="pl-dock-panel"
        tabIndex={-1}
        hidden={!open}
      >
        {children}
      </div>
    </div>
  );
}
