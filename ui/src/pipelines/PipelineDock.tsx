import { useCallback, useState, type ReactNode } from "react";
import { IconChevronDown, IconChevronUp } from "@tabler/icons-react";
import { Tabs, type TabItem } from "../components/ui";

/** The tabs of the panel under the canvas, in their order. */
export type DockTab = "settings" | "preview" | "suggestions" | "nodes" | "runs" | "versions";
/** The tabs the canvas fills: they need the canvas shown, not the YAML. */
export const CANVAS_TABS: ReadonlySet<DockTab> = new Set(["settings", "preview", "suggestions"]);
const LABELS: Record<DockTab, string> = {
  settings: "Settings",
  preview: "Preview",
  suggestions: "Suggestions",
  nodes: "Nodes",
  runs: "Runs",
  versions: "Versions",
};

export const DOCK_OPEN_KEY = "kilnmark.pipeline.panel";

function savedOpen(): boolean {
  try {
    return window.localStorage.getItem(DOCK_OPEN_KEY) !== "collapsed";
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

/** The panel's state: the tab shown and whether it is open, remembered per viewer. */
export function useDock(idBase: string) {
  const [tab, setTab] = useState<DockTab>("settings");
  const [open, setOpenState] = useState<boolean>(savedOpen);
  const setOpen = useCallback((o: boolean) => {
    setOpenState(o);
    try {
      window.localStorage.setItem(DOCK_OPEN_KEY, o ? "open" : "collapsed");
    } catch {
      // A remembered panel is a convenience only.
    }
  }, []);
  const show = useCallback(
    (t: DockTab) => {
      setTab(t);
      setOpen(true);
    },
    [setOpen],
  );
  const panel = useCallback(() => document.getElementById(`${idBase}-panel`), [idBase]);
  return { tab, open, setOpen, show, panel };
}

/**
 * The panel under the canvas: one tab bar (the ARIA tabs pattern: arrow keys, Home and End move
 * between the tabs and show them) and one panel area with its own scroll, so the canvas keeps the
 * page's width and nothing covers it. It can be collapsed to the tab bar; choosing a tab opens it.
 */
export function PipelineDock({
  idBase,
  tabs,
  tab,
  open,
  onTab,
  onOpen,
  children,
}: {
  idBase: string;
  tabs: DockTab[];
  tab: DockTab;
  open: boolean;
  onTab: (t: DockTab) => void;
  onOpen: (open: boolean) => void;
  children: ReactNode;
}) {
  const items: TabItem[] = tabs.map((t) => ({ id: t, label: LABELS[t] }));
  return (
    <div className={`pl-dock${open ? " is-open" : " is-collapsed"}`}>
      <div className="pl-dock-bar">
        <Tabs idBase={idBase} label="Panels under the canvas" selected={tab} onSelect={(t) => onTab(t as DockTab)} tabs={items} />
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
