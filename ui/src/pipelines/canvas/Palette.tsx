import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type RefObject } from "react";
import {
  IconArrowsJoin,
  IconBox,
  IconChevronDown,
  IconChevronRight,
  IconDatabase,
  IconDeviceFloppy,
  IconFilter,
  IconHistory,
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconMathFunction,
  IconSearch,
  IconShieldCheck,
  IconSparkles,
  IconSum,
} from "@tabler/icons-react";
import type { StepType } from "../../api";
import { useDismiss } from "../../components/ui";
import { StepIcon } from "./StepNode";

/** The folders, in order; a category the server names that is not here comes after them. */
export const CATEGORIES = ["Read", "Write", "Filter and route", "Transform", "Combine", "Aggregate", "Quality", "AI", "Other"];
export const RECENT = "Recently used";

const ICONS: Record<string, typeof IconBox> = {
  Read: IconDatabase,
  Write: IconDeviceFloppy,
  "Filter and route": IconFilter,
  Transform: IconMathFunction,
  Combine: IconArrowsJoin,
  Aggregate: IconSum,
  Quality: IconShieldCheck,
  AI: IconSparkles,
  Other: IconBox,
  [RECENT]: IconHistory,
};

/** A step type's folder: the one the server names, else one from its kind (for an older server). */
export const categoryOf = (t: StepType) =>
  t.category ?? (t.kind === "source" ? "Read" : t.kind === "sink" ? "Write" : t.kind === "gate" ? "Quality" : "Other");

const OPEN_KEY = "kilnmark.canvas.folders";
const LAST_KEY = "kilnmark.canvas.lastFolder";
const RECENT_KEY = "kilnmark.canvas.recent";

function stored<T>(key: string, fallback: T): T {
  try {
    const v = window.localStorage.getItem(key);
    return v == null ? fallback : (JSON.parse(v) as T);
  } catch {
    return fallback;
  }
}
function store(key: string, value: unknown) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Remembered folders are a convenience only.
  }
}

/** The step types added most recently, newest first: at most five. */
export function rememberUsed(type: string) {
  const list = stored<string[]>(RECENT_KEY, []).filter((t) => t !== type);
  store(RECENT_KEY, [type, ...list].slice(0, 5));
}

type Folder = { name: string; types: StepType[] };

const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, "-");

/** Moves the focus through the visible folders and steps of a tree with the arrow keys. */
function treeKeys(e: KeyboardEvent<HTMLElement>, root: HTMLElement | null, toggle: (folder: string, open?: boolean) => void) {
  if (!root) return;
  const items = [...root.querySelectorAll<HTMLElement>("[data-tree-item]")].filter((el) => el.offsetParent !== null);
  const here = e.target as HTMLElement;
  const i = items.indexOf(here);
  if (i < 0) return;
  const folder = here.dataset.folder;
  const isFolder = here.dataset.treeItem === "folder";
  const go = (j: number) => {
    e.preventDefault();
    e.stopPropagation();
    items[Math.max(0, Math.min(items.length - 1, j))]?.focus();
  };
  switch (e.key) {
    case "ArrowDown":
      return go(i + 1);
    case "ArrowUp":
      return go(i - 1);
    case "Home":
      return go(0);
    case "End":
      return go(items.length - 1);
    case "ArrowRight":
      e.preventDefault();
      e.stopPropagation();
      if (isFolder && folder) {
        if (here.getAttribute("aria-expanded") === "false") toggle(folder, true);
        else go(i + 1);
      }
      return;
    case "ArrowLeft":
      e.preventDefault();
      e.stopPropagation();
      if (isFolder && folder) toggle(folder, false);
      else if (folder) root.querySelector<HTMLElement>(`[data-tree-item="folder"][data-folder="${CSS.escape(folder)}"]`)?.focus();
      return;
  }
}

/**
 * The step types, in folders by category: an icon rail (a button per folder, opening a list of its
 * steps beside it) that expands into a tree with search. Folders are disclosure buttons; the arrow
 * keys move through the tree, Right and Left open and close a folder, Enter adds a step after the
 * selected one. Which folders are open is remembered in this browser.
 */
export function Palette({
  types,
  usedTypes,
  readOnly,
  expanded,
  onExpand,
  onAdd,
  searchRef,
  hint,
}: {
  types: StepType[] | null;
  usedTypes: string[];
  readOnly: boolean;
  expanded: boolean;
  onExpand: (open: boolean) => void;
  onAdd: (type: string) => void;
  searchRef: RefObject<HTMLInputElement | null>;
  hint: string;
}) {
  const [search, setSearch] = useState("");
  // Read each time: steps added by dragging or from the rail update it too.
  const recent = stored<string[]>(RECENT_KEY, []);
  const recentKey = recent.join(",");
  const folders: Folder[] = useMemo(() => {
    const byName = new Map<string, StepType[]>();
    for (const t of types ?? []) byName.set(categoryOf(t), [...(byName.get(categoryOf(t)) ?? []), t]);
    const names = [...CATEGORIES.filter((c) => byName.has(c)), ...[...byName.keys()].filter((c) => !CATEGORIES.includes(c))];
    const out = names.map((name) => ({ name, types: byName.get(name)! }));
    const used = recent.map((r) => types?.find((t) => t.type === r)).filter((t): t is StepType => !!t);
    return used.length ? [{ name: RECENT, types: used }, ...out] : out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [types, recentKey]);

  const [open, setOpen] = useState<Set<string>>(() => {
    const saved = stored<string[] | null>(OPEN_KEY, null);
    if (saved) return new Set(saved);
    const last = stored<string | null>(LAST_KEY, null);
    return new Set(last ? [last] : []);
  });
  // Folders holding the steps this pipeline uses open by themselves, until folders are opened or
  // closed by hand (which is then remembered).
  const usedKey = usedTypes.join(",");
  useEffect(() => {
    if (!types || stored<string[] | null>(OPEN_KEY, null)) return;
    const used = new Set(usedTypes.map((u) => types.find((t) => t.type === u)).filter(Boolean).map((t) => categoryOf(t!)));
    if (used.size) setOpen((o) => new Set([...o, ...used]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [types, usedKey]);

  const toggle = useCallback((folder: string, to?: boolean) => {
    setOpen((o) => {
      const next = new Set(o);
      const want = to ?? !next.has(folder);
      if (want) next.add(folder);
      else next.delete(folder);
      store(OPEN_KEY, [...next]);
      if (want) store(LAST_KEY, folder);
      return next;
    });
  }, []);

  const add = (type: string) => onAdd(type);

  const q = search.trim().toLowerCase();
  const matches = (t: StepType) => !q || `${t.type} ${t.label} ${t.help} ${categoryOf(t)}`.toLowerCase().includes(q);
  const shown = folders
    .map((f) => ({ ...f, types: f.types.filter(matches) }))
    .filter((f) => f.types.length > 0 && !(q && f.name === RECENT));
  const tree = useRef<HTMLDivElement>(null);

  // The rail's fly-out: one folder's steps beside the rail.
  const [flyout, setFlyout] = useState<string | null>(null);
  const flyPanel = useRef<HTMLDivElement>(null);
  const flyTrigger = useRef<HTMLButtonElement | null>(null);
  const railButtons = useRef<Record<string, HTMLButtonElement | null>>({});
  const closeFly = useCallback((returnFocus: boolean) => {
    setFlyout((f) => {
      if (returnFocus && f) railButtons.current[f]?.focus();
      return null;
    });
  }, []);
  useDismiss(flyout != null, flyPanel, flyTrigger, closeFly);
  useEffect(() => {
    if (flyout) flyPanel.current?.querySelector<HTMLElement>("button")?.focus();
  }, [flyout]);

  const stepButton = (t: StepType, folder: string) => (
    <button
      type="button"
      className={`cv-type cv-kind-${t.kind}`}
      draggable={!readOnly}
      disabled={readOnly}
      title={t.help}
      aria-description={t.help}
      data-tree-item="step"
      data-folder={folder}
      onDragStart={(e) => {
        e.dataTransfer.setData("application/x-kilnmark-step", t.type);
        e.dataTransfer.effectAllowed = "copy";
      }}
      onClick={() => {
        add(t.type);
        if (flyout) setFlyout(null);
      }}
    >
      <span className="cv-type-icon">
        <StepIcon type={t.type} kind={t.kind} />
      </span>
      <span>{t.label}</span>
    </button>
  );

  const railKeys = (e: KeyboardEvent<HTMLElement>) => {
    const items = [...(e.currentTarget.querySelectorAll<HTMLElement>("[data-rail-item]") ?? [])];
    const i = items.indexOf(e.target as HTMLElement);
    if (i < 0) return;
    const to = e.key === "ArrowDown" ? i + 1 : e.key === "ArrowUp" ? i - 1 : e.key === "Home" ? 0 : e.key === "End" ? items.length - 1 : null;
    if (to != null) {
      e.preventDefault();
      e.stopPropagation();
      items[(to + items.length) % items.length]?.focus();
    } else if (e.key === "ArrowRight") {
      const f = (e.target as HTMLElement).dataset.folder;
      if (f) {
        e.preventDefault();
        e.stopPropagation();
        flyTrigger.current = e.target as HTMLButtonElement;
        setFlyout(f);
      }
    }
  };

  const flyFolder = folders.find((f) => f.name === flyout);

  return (
    <div className={`cv-palette${expanded ? " is-expanded" : ""}`}>
      <nav className="cv-rail" aria-label="Step types" onKeyDown={railKeys}>
        <button
          type="button"
          className="cv-rail-button"
          data-rail-item
          aria-expanded={expanded}
          aria-controls="cv-palette-panel"
          aria-label={expanded ? "Hide the step list" : "Add a step: show the step list"}
          title={expanded ? "Hide the step list" : "Show the step list"}
          onClick={() => {
            setFlyout(null);
            onExpand(!expanded);
          }}
        >
          {expanded ? <IconLayoutSidebarLeftCollapse size={20} stroke={1.8} aria-hidden="true" /> : <IconLayoutSidebarLeftExpand size={20} stroke={1.8} aria-hidden="true" />}
        </button>
        <span className="cv-rail-sep" aria-hidden="true" />
        {folders.map((f) => {
          const Icon = ICONS[f.name] ?? IconBox;
          return (
            <button
              key={f.name}
              type="button"
              ref={(el) => {
                railButtons.current[f.name] = el;
              }}
              className={`cv-rail-button${flyout === f.name ? " is-open" : ""}`}
              data-rail-item
              data-folder={f.name}
              aria-label={`${f.name} (${f.types.length})`}
              title={`${f.name} (${f.types.length})`}
              aria-expanded={flyout === f.name}
              aria-controls={flyout === f.name ? "cv-flyout" : undefined}
              disabled={readOnly}
              onClick={(e) => {
                flyTrigger.current = e.currentTarget;
                setFlyout(flyout === f.name ? null : f.name);
              }}
            >
              <Icon size={20} stroke={1.8} aria-hidden="true" />
            </button>
          );
        })}
      </nav>

      {flyFolder && (
        <div
          id="cv-flyout"
          ref={flyPanel}
          className="cv-flyout"
          role="group"
          aria-label={`${flyFolder.name} steps`}
          onKeyDown={(e) => {
            const items = [...e.currentTarget.querySelectorAll<HTMLElement>("button")];
            const i = items.indexOf(e.target as HTMLElement);
            const to = e.key === "ArrowDown" ? i + 1 : e.key === "ArrowUp" ? i - 1 : null;
            if (to != null) {
              e.preventDefault();
              e.stopPropagation();
              items[(to + items.length) % items.length]?.focus();
            } else if (e.key === "ArrowLeft") {
              e.preventDefault();
              e.stopPropagation();
              closeFly(true);
            }
          }}
        >
          <h4 className="cv-group-title">{flyFolder.name}</h4>
          <ul className="cv-type-list">
            {flyFolder.types.map((t) => (
              <li key={t.type}>{stepButton(t, flyFolder.name)}</li>
            ))}
          </ul>
        </div>
      )}

      {expanded && (
        <section id="cv-palette-panel" className="cv-palette-panel" aria-labelledby="cv-palette-h">
          <h3 id="cv-palette-h" className="cv-side-title">
            Steps
          </h3>
          <label className="cv-search">
            <span className="visually-hidden">Find a step type</span>
            <IconSearch size={16} stroke={1.8} aria-hidden="true" />
            <input
              ref={searchRef}
              type="search"
              placeholder="Find a step"
              value={search}
              disabled={readOnly}
              aria-describedby="cv-palette-hint"
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "ArrowDown") {
                  e.preventDefault();
                  tree.current?.querySelector<HTMLElement>("[data-tree-item]")?.focus();
                }
              }}
            />
          </label>
          <p id="cv-palette-hint" className="hint">
            {hint}
          </p>
          {types == null && <p className="small muted">Loading the step types…</p>}
          <div className="cv-tree" ref={tree} onKeyDown={(e) => treeKeys(e, tree.current, toggle)}>
            <ul className="cv-folders">
              {shown.map((f) => {
                const isOpen = q ? true : open.has(f.name);
                const id = `cv-folder-${slug(f.name)}`;
                const Icon = ICONS[f.name] ?? IconBox;
                return (
                  <li key={f.name}>
                    <button
                      type="button"
                      className="cv-folder"
                      aria-expanded={isOpen}
                      aria-controls={id}
                      data-tree-item="folder"
                      data-folder={f.name}
                      // While searching every folder with a match is open, so Tab goes straight to the steps.
                      tabIndex={q ? -1 : undefined}
                      onClick={() => !q && toggle(f.name)}
                    >
                      {isOpen ? <IconChevronDown size={16} stroke={2} aria-hidden="true" /> : <IconChevronRight size={16} stroke={2} aria-hidden="true" />}
                      <Icon size={16} stroke={1.8} aria-hidden="true" />
                      <span>
                        {f.name} <span className="cv-count">({f.types.length})</span>
                      </span>
                    </button>
                    <ul id={id} className="cv-type-list" hidden={!isOpen}>
                      {f.types.map((t) => (
                        <li key={t.type}>{stepButton(t, f.name)}</li>
                      ))}
                    </ul>
                  </li>
                );
              })}
            </ul>
          </div>
          {types && shown.length === 0 && <p className="small muted">No step type matches “{search}”.</p>}
        </section>
      )}
    </div>
  );
}
