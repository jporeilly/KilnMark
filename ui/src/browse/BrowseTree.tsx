import { useCallback, useEffect, useMemo, useRef, useState, type FocusEvent, type KeyboardEvent, type ReactNode } from "react";
import { formatNumber, plural } from "../api";
import { go, href } from "../router";
import { browseApi, type BrowseAsset, type BrowseContainer, type BrowseSource } from "./browseApi";
import { containerIcon, formatLabel, Icon, sourceWords } from "./icons";
import { SourceTile } from "../components/ConnectorTile";

const PAGE = 100;

type Loaded<T> = { items: T[]; total: number; loading: boolean; error: string | null };

/** A row the keyboard can reach, in the order the tree shows them. */
type Item = {
  id: string;
  parent: string | null;
  level: number;
  expandable: boolean;
  activate: () => void;
};

const sourceId = (s: string) => `s:${s}`;
const containerId = (s: string, c: string) => `c:${s}\u0000${c}`;
const assetId = (urn: string) => `a:${urn}`;
const moreId = (parent: string) => `m:${parent}`;

/** The id an HTML element can carry for a tree item (ids may hold any character but stay readable). */
const domId = (id: string) => `tree-${Array.from(id, (ch) => ch.charCodeAt(0).toString(36)).join("-")}`;

/**
 * The catalogue as a tree: source, then container (schema, folder or bucket prefix), then assets.
 * Children load when a node first opens, a page at a time. Follows the ARIA tree pattern: one tab
 * stop; arrow keys move, Right opens or goes to the first child, Left closes or goes to the parent;
 * Home and End; Enter or Space chooses; typing a letter jumps to the next item starting with it.
 */
export function BrowseTree({
  selectedSource,
  selectedContainer,
}: {
  selectedSource: string | null;
  selectedContainer: string | null;
}) {
  const [filter, setFilter] = useState("");
  const [q, setQ] = useState("");
  const [sources, setSources] = useState<BrowseSource[] | null>(null);
  const [sourceError, setSourceError] = useState<string | null>(null);
  const [containers, setContainers] = useState<Record<string, Loaded<BrowseContainer>>>({});
  const [assets, setAssets] = useState<Record<string, Loaded<BrowseAsset>>>({});
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [focused, setFocused] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const refs = useRef(new Map<string, HTMLElement>());
  const generation = useRef(0); // bumps when the filter changes, so late answers are dropped

  useEffect(() => {
    browseApi
      .sources()
      .then(setSources)
      .catch((e: Error) => setSourceError(e.message));
  }, []);

  // Filter: wait for a pause in typing, then reload with the new text.
  useEffect(() => {
    const t = window.setTimeout(() => setQ(filter.trim()), 300);
    return () => window.clearTimeout(t);
  }, [filter]);

  useEffect(() => {
    generation.current += 1;
    setContainers({});
    setAssets({});
    if (q && sources) setExpanded((e) => new Set([...e, ...sources.map((s) => sourceId(s.name))]));
  }, [q, sources]);

  // Open the selected source (and container) the first time the page shows them.
  useEffect(() => {
    if (!selectedSource) return;
    setExpanded((e) => {
      const next = new Set(e);
      next.add(sourceId(selectedSource));
      return next;
    });
  }, [selectedSource]);

  const loadContainers = useCallback(
    (source: string, offset: number) => {
      const gen = generation.current;
      setContainers((all) => ({
        ...all,
        [source]: { ...(all[source] ?? { items: [], total: 0, error: null }), loading: true, error: null },
      }));
      browseApi
        .containers(source, q, offset, PAGE)
        .then((page) => {
          if (gen !== generation.current) return;
          setContainers((all) => ({
            ...all,
            [source]: {
              items: [...(offset === 0 ? [] : all[source]?.items ?? []), ...page.items],
              total: page.total,
              loading: false,
              error: null,
            },
          }));
          setAnnouncement(`${source}: ${plural(page.total, "item")}`);
        })
        .catch((e: Error) => {
          if (gen !== generation.current) return;
          setContainers((all) => ({ ...all, [source]: { items: [], total: 0, loading: false, error: e.message } }));
        });
    },
    [q],
  );

  const loadAssets = useCallback(
    (source: string, container: string, offset: number) => {
      const key = containerId(source, container);
      const gen = generation.current;
      setAssets((all) => ({
        ...all,
        [key]: { ...(all[key] ?? { items: [], total: 0, error: null }), loading: true, error: null },
      }));
      browseApi
        .assets(source, container, q, offset, PAGE)
        .then((page) => {
          if (gen !== generation.current) return;
          setAssets((all) => ({
            ...all,
            [key]: {
              items: [...(offset === 0 ? [] : all[key]?.items ?? []), ...page.items],
              total: page.total,
              loading: false,
              error: null,
            },
          }));
          setAnnouncement(`${container || "top level"}: ${plural(page.total, "asset")}`);
        })
        .catch((e: Error) => {
          if (gen !== generation.current) return;
          setAssets((all) => ({ ...all, [key]: { items: [], total: 0, loading: false, error: e.message } }));
        });
    },
    [q],
  );

  // Load what an open node needs and does not have yet.
  useEffect(() => {
    for (const id of expanded) {
      if (id.startsWith("s:")) {
        const s = id.slice(2);
        if (!containers[s]) loadContainers(s, 0);
      } else if (id.startsWith("c:")) {
        const [s, c] = id.slice(2).split("\u0000");
        if (!assets[id]) loadAssets(s, c, 0);
      }
    }
  }, [expanded, containers, assets, loadContainers, loadAssets]);

  const toggle = useCallback((id: string, open?: boolean) => {
    setExpanded((e) => {
      const next = new Set(e);
      const want = open ?? !next.has(id);
      if (want) next.add(id);
      else next.delete(id);
      return next;
    });
  }, []);

  const visibleSources = useMemo(
    () =>
      (sources ?? []).filter((s) => {
        if (!q) return true;
        const c = containers[s.name];
        return !c || c.loading || c.items.length > 0;
      }),
    [sources, q, containers],
  );

  // The rows in view, in order, for keyboard movement.
  const items = useMemo(() => {
    const out: Item[] = [];
    for (const s of visibleSources) {
      const sid = sourceId(s.name);
      out.push({
        id: sid,
        parent: null,
        level: 1,
        expandable: true,
        activate: () => {
          toggle(sid, true);
          go(href.browse(s.name));
        },
      });
      if (!expanded.has(sid)) continue;
      const cs = containers[s.name];
      for (const c of cs?.items ?? []) {
        const cid = containerId(s.name, c.name);
        out.push({
          id: cid,
          parent: sid,
          level: 2,
          expandable: true,
          activate: () => {
            toggle(cid, true);
            go(href.browse(s.name, c.name));
          },
        });
        if (!expanded.has(cid)) continue;
        const as = assets[cid];
        for (const a of as?.items ?? []) {
          out.push({ id: assetId(a.urn), parent: cid, level: 3, expandable: false, activate: () => go(href.asset(a.urn)) });
        }
        if (as && !as.loading && as.items.length < as.total) {
          out.push({
            id: moreId(cid),
            parent: cid,
            level: 3,
            expandable: false,
            activate: () => loadAssets(s.name, c.name, as.items.length),
          });
        }
      }
      if (cs && !cs.loading && cs.items.length < cs.total) {
        out.push({
          id: moreId(sid),
          parent: sid,
          level: 2,
          expandable: false,
          activate: () => loadContainers(s.name, cs.items.length),
        });
      }
    }
    return out;
  }, [visibleSources, expanded, containers, assets, toggle, loadAssets, loadContainers]);

  const current = focused && items.some((i) => i.id === focused) ? focused : items[0]?.id ?? null;

  const moveTo = (id: string | undefined) => {
    if (!id) return;
    setFocused(id);
    refs.current.get(id)?.focus();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLUListElement>) => {
    if (!current) return;
    const index = items.findIndex((i) => i.id === current);
    const item = items[index];
    if (!item) return;
    const open = expanded.has(item.id);
    let handled = true;
    switch (e.key) {
      case "ArrowDown":
        moveTo(items[index + 1]?.id);
        break;
      case "ArrowUp":
        moveTo(items[index - 1]?.id);
        break;
      case "Home":
        moveTo(items[0]?.id);
        break;
      case "End":
        moveTo(items[items.length - 1]?.id);
        break;
      case "ArrowRight":
        if (item.expandable && !open) toggle(item.id, true);
        else if (item.expandable && items[index + 1]?.parent === item.id) moveTo(items[index + 1].id);
        break;
      case "ArrowLeft":
        if (item.expandable && open) toggle(item.id, false);
        else if (item.parent) moveTo(item.parent);
        break;
      case "Enter":
      case " ":
        item.activate();
        break;
      default:
        if (e.key.length === 1 && /\S/.test(e.key) && !e.ctrlKey && !e.metaKey && !e.altKey) {
          const ch = e.key.toLowerCase();
          const order = [...items.slice(index + 1), ...items.slice(0, index + 1)];
          const next = order.find((i) => (refs.current.get(i.id)?.dataset.name ?? "").toLowerCase().startsWith(ch));
          moveTo(next?.id);
        } else {
          handled = false;
        }
    }
    if (handled) {
      e.preventDefault();
      e.stopPropagation();
    }
  };

  const register = (id: string) => (el: HTMLElement | null) => {
    if (el) refs.current.set(id, el);
    else refs.current.delete(id);
  };

  const rowProps = (id: string, level: number, setsize: number, posinset: number, name: string, meta = true) => ({
    id: domId(id),
    // The name is the row's own label (and its counts), not everything nested inside it.
    "aria-labelledby": meta ? `${domId(id)}-label ${domId(id)}-meta` : `${domId(id)}-label`,
    ref: register(id),
    role: "treeitem",
    "aria-level": level,
    "aria-setsize": setsize,
    "aria-posinset": posinset,
    tabIndex: id === current ? 0 : -1,
    "data-name": name,
    onFocus: (e: FocusEvent) => {
      if (e.target === e.currentTarget) setFocused(id);
    },
  });

  const statusRow = (loaded: Loaded<unknown> | undefined, what: string) => {
    if (!loaded) return null;
    if (loaded.loading && loaded.items.length === 0)
      return (
        <li role="none" className="tree-status">
          Loading {what}…
        </li>
      );
    if (loaded.error)
      return (
        <li role="none" className="tree-status error">
          {loaded.error}
        </li>
      );
    if (!loaded.loading && loaded.items.length === 0)
      return (
        <li role="none" className="tree-status muted">
          {q ? "Nothing here matches the filter." : `No ${what}.`}
        </li>
      );
    return null;
  };

  return (
    <div className="browse-tree">
      <div className="field">
        <label htmlFor="tree-filter">Filter by name</label>
        <input
          id="tree-filter"
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Schemas, folders, tables, files"
          autoComplete="off"
          aria-describedby="tree-help"
        />
        <p id="tree-help" className="hint">
          Arrow keys move through the tree, Right opens, Left closes, Enter chooses.
        </p>
      </div>
      <p className="visually-hidden" role="status" aria-live="polite">
        {announcement}
      </p>
      {sourceError && (
        <p role="alert" className="error">
          {sourceError}
        </p>
      )}
      {sources && sources.length === 0 && (
        <p className="muted">
          No sources yet. Add one on the <a href={href.sources()}>Sources</a> page, then scan it.
        </p>
      )}
      {sources == null && !sourceError && <p role="status">Loading sources…</p>}
      {sources && sources.length > 0 && (
        <ul role="tree" aria-label="Catalogue" className="tree" onKeyDown={onKeyDown} data-keeps-focus>
          {visibleSources.map((s, si) => {
            const sid = sourceId(s.name);
            const open = expanded.has(sid);
            const words = sourceWords(s.kind);
            const cs = containers[s.name];
            const selected = selectedSource === s.name && selectedContainer == null;
            return (
              <li
                key={sid}
                {...rowProps(sid, 1, visibleSources.length, si + 1, s.label)}
                aria-expanded={open}
                aria-selected={selected}
                aria-busy={cs?.loading || undefined}
              >
                <TreeRow
                  open={open}
                  expandable
                  selected={selected}
                  onToggle={() => toggle(sid)}
                  onChoose={() => {
                    setFocused(sid);
                    toggle(sid, true);
                    go(href.browse(s.name));
                  }}
                >
                  <SourceTile kind={s.kind} size="sm" />
                  <span className="tree-label" id={`${domId(sid)}-label`}>
                    {s.label}
                  </span>
                  <span className="tree-meta" id={`${domId(sid)}-meta`}>
                    {words.kind} · {plural(s.assets, "asset")}
                  </span>
                </TreeRow>
                {open && (
                  <ul role="group">
                    {statusRow(cs, words.containers)}
                    {(cs?.items ?? []).map((c, ci) => {
                      const cid = containerId(s.name, c.name);
                      const copen = expanded.has(cid);
                      const as = assets[cid];
                      const csel = selectedSource === s.name && selectedContainer === c.name;
                      const setsize = cs!.items.length + (cs!.items.length < cs!.total ? 1 : 0);
                      return (
                        <li
                          key={cid}
                          {...rowProps(cid, 2, setsize, ci + 1, c.name || "(top level)")}
                          aria-expanded={copen}
                          aria-selected={csel}
                          aria-busy={as?.loading || undefined}
                        >
                          <TreeRow
                            open={copen}
                            expandable
                            selected={csel}
                            onToggle={() => toggle(cid)}
                            onChoose={() => {
                              setFocused(cid);
                              toggle(cid, true);
                              go(href.browse(s.name, c.name));
                            }}
                          >
                            <Icon name={containerIcon(s.kind)} />
                            <span className="tree-label mono" id={`${domId(cid)}-label`}>
                              {c.name || "(top level)"}
                            </span>
                            <span className="tree-meta" id={`${domId(cid)}-meta`}>
                              {plural(c.assets, "asset")}
                            </span>
                          </TreeRow>
                          {copen && (
                            <ul role="group">
                              {statusRow(as, "assets")}
                              {(as?.items ?? []).map((a, ai) => {
                                const aid = assetId(a.urn);
                                return (
                                  <li
                                    key={aid}
                                    {...rowProps(aid, 3, as!.items.length + (as!.items.length < as!.total ? 1 : 0), ai + 1, a.name)}
                                  >
                                    <TreeRow
                                      onChoose={() => {
                                        setFocused(aid);
                                        go(href.asset(a.urn));
                                      }}
                                    >
                                      <Icon name={a.format} file={a.name} />
                                      <span className="tree-label mono" id={`${domId(aid)}-label`}>
                                        {a.name}
                                      </span>
                                      <span className="tree-meta" id={`${domId(aid)}-meta`}>
                                        {formatLabel(a.format)}
                                      </span>
                                    </TreeRow>
                                  </li>
                                );
                              })}
                              {as && !as.loading && as.items.length < as.total && (
                                <MoreRow
                                  {...rowProps(moreId(cid), 3, as.items.length + 1, as.items.length + 1, "", false)}
                                  labelId={`${domId(moreId(cid))}-label`}
                                  shown={as.items.length}
                                  total={as.total}
                                  onChoose={() => loadAssets(s.name, c.name, as.items.length)}
                                />
                              )}
                              {as?.loading && as.items.length > 0 && (
                                <li role="none" className="tree-status">
                                  Loading more…
                                </li>
                              )}
                            </ul>
                          )}
                        </li>
                      );
                    })}
                    {cs && !cs.loading && cs.items.length < cs.total && (
                      <MoreRow
                        {...rowProps(moreId(sid), 2, cs.items.length + 1, cs.items.length + 1, "", false)}
                        labelId={`${domId(moreId(sid))}-label`}
                        shown={cs.items.length}
                        total={cs.total}
                        onChoose={() => loadContainers(s.name, cs.items.length)}
                      />
                    )}
                  </ul>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function TreeRow({
  children,
  open,
  expandable,
  selected,
  onToggle,
  onChoose,
}: {
  children: ReactNode;
  open?: boolean;
  expandable?: boolean;
  selected?: boolean;
  onToggle?: () => void;
  onChoose: () => void;
}) {
  return (
    <div className={`tree-row${selected ? " is-selected" : ""}`} onClick={onChoose}>
      {expandable ? (
        <span
          className="tree-toggle"
          aria-hidden="true"
          onClick={(e) => {
            e.stopPropagation();
            onToggle?.();
          }}
        >
          {open ? "▾" : "▸"}
        </span>
      ) : (
        <span className="tree-toggle" aria-hidden="true" />
      )}
      {children}
    </div>
  );
}

function MoreRow({
  shown,
  total,
  onChoose,
  labelId,
  ...props
}: {
  shown: number;
  total: number;
  onChoose: () => void;
  labelId: string;
} & Record<string, unknown>) {
  return (
    <li {...props}>
      <div className="tree-row tree-more" onClick={onChoose}>
        <span className="tree-toggle" aria-hidden="true" />
        <span id={labelId}>
          Show more ({formatNumber(shown)} of {formatNumber(total)} shown)
        </span>
      </div>
    </li>
  );
}
