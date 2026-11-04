import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Tabs, TabPanel, useTabsId } from "../components/ui";
import {
  allows,
  ApiError,
  formatNumber,
  formatTime,
  pipelinesApi,
  type AccessRole,
  type PipelineDetail,
  type PipelineProblem,
  type PipelineRun,
  type PipelineValidation,
  type PipelineVersion,
  type PipelineVersionSummary,
} from "../api";
import { go, href } from "../router";
import { useTitle } from "../components/useTitle";
import { docHref } from "../docs/links";
import { RunStatus, formatBytes, formatDuration, runTime } from "./RunStatus";
import { nodeMetrics, shareOf, splitWords } from "./nodeMetrics";
import { rateWords } from "./live";
import { columnsText } from "./outline";
import { nodeQualityWords } from "./quality";
import { emptyYaml, findStarterPicks, renamed, starterYaml, withoutLayout } from "./template";
import { YamlEditor, type YamlEditorHandle } from "./YamlEditor";
import { CANVAS_TABS, NODE_TABS, PipelineDock, useDock, type DockControl, type DockTab } from "./PipelineDock";
import { RunFacts, RunNodes, rowsRead, useRunDetail } from "./RunSummary";
import type { CanvasRun } from "./canvas/CanvasView";
import type { Graph } from "./canvas/model";
import "./pipelines.css";

// The graph library is large, so the graph and the canvas load when the page first shows one.
const PipelineDiagram = lazy(() => import("./PipelineDiagram"));
const CanvasView = lazy(() => import("./canvas/CanvasView"));

const NAME = /^[a-z][a-z0-9-]{0,62}$/;
const CHECK_DELAY_MS = 600;
/** Changes to one setting this close together are one node to undo. */
const COALESCE_MS = 1500;
const HISTORY = 200;

type View = "canvas" | "yaml";
const VIEW_KEY = "kilnmark.pipeline.view";
function savedView(): View {
  try {
    return window.localStorage.getItem(VIEW_KEY) === "yaml" ? "yaml" : "canvas";
  } catch {
    return "canvas";
  }
}
type Undo = { text: string; label: string; key?: string; at: number };
const MOVED = "Moved nodes";

/**
 * One pipeline, built on the canvas or written as YAML: two views of one document. The canvas
 * (palette, graph, settings panel) and the YAML editor edit the same text; the YAML is checked a
 * moment after each change and the problems are listed below both (choosing one selects its node
 * on the canvas, or moves the caret to its line in the YAML). Save with a message, Run the saved
 * version, its versions and its runs. With name null, a new pipeline from the starter template or
 * an empty canvas. With a version, that saved version, read-only.
 */
export function PipelinePage({ name, version, role }: { name: string | null; version: number | null; role: AccessRole }) {
  const steward = allows(role, "steward");
  const designer = allows(role, "designer");
  const isNew = name == null;
  const viewing = version != null;
  const editable = designer && !viewing;
  useTitle(isNew ? "New pipeline" : viewing ? `${name}, version ${version}` : (name ?? "Pipeline"));

  const [detail, setDetail] = useState<PipelineDetail | null>(null);
  const [shown, setShown] = useState<PipelineVersion | null>(null);
  const [yaml, setYaml] = useState(isNew ? starterYaml("my-pipeline") : "");
  const [savedYaml, setSavedYaml] = useState<string | null>(null);
  const [newName, setNewName] = useState("my-pipeline");
  const [displayName, setDisplayName] = useState("");
  const [message, setMessage] = useState("");
  const [check, setCheck] = useState<PipelineValidation | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);
  const [versions, setVersions] = useState<PipelineVersionSummary[] | null>(null);
  const [runs, setRuns] = useState<PipelineRun[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const editor = useRef<YamlEditorHandle>(null);
  const [view, setViewState] = useState<View>(savedView);
  const setView = (v: View) => {
    setViewState(v);
    try {
      window.localStorage.setItem(VIEW_KEY, v);
    } catch {
      // A remembered view is a convenience only.
    }
  };
  const tabsId = useTabsId();
  const dockId = useTabsId();
  const dock = useDock(dockId);
  const [dockHost, setDockHost] = useState<HTMLDivElement | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [focusRequest, setFocusRequest] = useState<{ node: string; key: string | null; n: number } | null>(null);
  const [confirmRun, setConfirmRun] = useState(false);
  // A run asked for here, shown before the runs list has it.
  const [startedRun, setStartedRun] = useState<number | null>(null);
  const canvasMemory = useRef<{ lastGood?: Graph | null }>({});
  // Undo and redo for changes made on the canvas (the YAML editor has the browser's own).
  const yamlNow = useRef(yaml);
  yamlNow.current = yaml;
  const undos = useRef<Undo[]>([]);
  const redos = useRef<Undo[]>([]);
  const [, setHistoryTick] = useState(0);
  const edit = useCallback((next: string, label: string, key?: string) => {
    const now = Date.now();
    const last = undos.current.at(-1);
    if (key && last?.key === key && now - last.at < COALESCE_MS) {
      last.at = now;
    } else {
      undos.current = [...undos.current, { text: yamlNow.current, label, key, at: now }].slice(-HISTORY);
    }
    redos.current = [];
    yamlNow.current = next;
    setYaml(next);
    setHistoryTick((n) => n + 1);
  }, []);
  const undo = useCallback((): string | null => {
    const last = undos.current.at(-1);
    if (!last) return null;
    undos.current = undos.current.slice(0, -1);
    redos.current = [...redos.current, { ...last, text: yamlNow.current }];
    yamlNow.current = last.text;
    setYaml(last.text);
    setHistoryTick((n) => n + 1);
    return last.label;
  }, []);
  const redo = useCallback((): string | null => {
    const next = redos.current.at(-1);
    if (!next) return null;
    redos.current = redos.current.slice(0, -1);
    undos.current = [...undos.current, { ...next, text: yamlNow.current, key: undefined }];
    yamlNow.current = next.text;
    setYaml(next.text);
    setHistoryTick((n) => n + 1);
    return next.label;
  }, []);
  const errorRef = useRef<HTMLParagraphElement>(null);
  // Each failure moves the focus to its message once it is on the page (even the same message again).
  const [failures, setFailures] = useState(0);
  useEffect(() => {
    if (failures > 0) errorRef.current?.focus();
  }, [failures]);
  const sequence = useRef(0);

  const load = useCallback(async () => {
    if (isNew || name == null) return;
    try {
      const d = await pipelinesApi.get(name);
      setDetail(d);
      if (version != null) {
        setShown(await pipelinesApi.version(name, version));
      } else if (d.latest) {
        setYaml(d.latest.yaml);
        setSavedYaml(d.latest.yaml);
      }
      setLoadError(null);
    } catch (e) {
      setLoadError((e as Error).message);
    }
  }, [isNew, name, version]);

  const loadLists = useCallback(() => {
    if (isNew || name == null) return;
    pipelinesApi.versions(name).then(setVersions).catch((e: Error) => setLoadError(e.message));
    pipelinesApi.runs(name).then(setRuns).catch((e: Error) => setLoadError(e.message));
  }, [isNew, name]);

  useEffect(() => {
    void load();
    loadLists();
  }, [load, loadLists]);

  // A new pipeline's template names the person's own sources once they are known, unless the
  // YAML has been changed by then. Without sources it keeps its placeholders.
  useEffect(() => {
    if (!isNew) return;
    let live = true;
    findStarterPicks()
      .then((picks) => {
        if (!live) return;
        setYaml((y) => {
          const n = /^name:\s*(.*)$/m.exec(y)?.[1] ?? "my-pipeline";
          return y === starterYaml(n) ? starterYaml(n, picks) : y;
        });
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [isNew]);

  // Keep the runs table current while a run is queued or running.
  const active = runs?.some((r) => r.status === "queued" || r.status === "running");
  useEffect(() => {
    if (!active) return;
    const t = window.setInterval(loadLists, 4000);
    return () => window.clearInterval(t);
  }, [active, loadLists]);

  // The latest run (or the one just asked for), node by node, for the canvas and the Run tab: looked
  // at every two seconds while it is queued or running, as the run page does.
  const latestRun = runs?.[0] ?? null;
  const runId = Math.max(startedRun ?? 0, latestRun?.id ?? 0) || null;
  const runLook = useRunDetail(runId);
  const runShown = runLook.detail;
  // Once it ends, the runs list and the page's last run catch up.
  const wasUnfinished = useRef(false);
  useEffect(() => {
    if (wasUnfinished.current && !runLook.unfinished) {
      loadLists();
      void load();
    }
    wasUnfinished.current = runLook.unfinished;
  }, [runLook.unfinished, loadLists, load]);
  const canvasRun: CanvasRun | null = useMemo(
    () =>
      runShown && !viewing
        ? {
            id: runShown.run.id,
            version: runShown.run.version,
            status: runShown.run.status,
            nodes: runShown.nodes,
            quality: runShown.quality ?? [],
            progress: runShown.progress ?? null,
          }
        : null,
    [runShown, viewing],
  );

  // The panel under the canvas, one tab at a time: the nodes first (choosing one there, or on the
  // canvas, opens its settings), the selected node's settings and preview (only while a node is
  // selected), then the pipeline's run, suggestions, runs and versions.
  const dockTabs: DockTab[] = [
    "nodes",
    ...(selected != null ? (["settings", "preview"] as const) : []),
    ...(isNew ? [] : (["run"] as const)),
    "suggestions",
    ...(isNew ? [] : (["runs", "versions"] as const)),
  ];
  // When the tab shown goes (the node was deselected or removed): the Run tab if a run is going or
  // it was the last of the pipeline's tabs shown, else Nodes.
  const lastPipelineTab = useRef<DockTab | null>(null);
  const fallback: DockTab = !isNew && (runLook.unfinished || lastPipelineTab.current === "run") ? "run" : "nodes";
  const dockTab = dockTabs.includes(dock.tab) ? dock.tab : fallback;
  const { setTab: setDockTab } = dock;
  useEffect(() => {
    if (dockTab !== dock.tab) setDockTab(dockTab);
    if (!NODE_TABS.has(dockTab)) lastPipelineTab.current = dockTab;
  }, [dockTab, dock.tab, setDockTab]);
  const dockControl: DockControl = useMemo(
    () => ({ tab: dockTab, open: dock.open, show: dock.show, host: dockHost, panel: dock.panel }),
    [dockTab, dock.open, dock.show, dockHost, dock.panel],
  );
  // The nodes that read, for the rows a run read.
  const reads = useMemo(() => new Set((check?.nodes ?? []).filter((n) => n.kind === "source").map((n) => n.id)), [check]);

  const text = viewing ? (shown?.yaml ?? "") : yaml;

  // Check the YAML a moment after typing stops. Only the latest answer counts.
  useEffect(() => {
    if (!text.trim()) return;
    const mine = ++sequence.current;
    setChecking(true);
    const t = window.setTimeout(() => {
      pipelinesApi
        .validate(text, isNew ? undefined : (name ?? undefined))
        .then((v) => {
          if (sequence.current !== mine) return;
          setCheck(v);
          setCheckError(null);
        })
        .catch((e: Error) => {
          if (sequence.current === mine) setCheckError(e.message);
        })
        .finally(() => {
          if (sequence.current === mine) setChecking(false);
        });
    }, CHECK_DELAY_MS);
    return () => window.clearTimeout(t);
  }, [text, isNew, name]);

  const problems = useMemo(() => check?.problems ?? [], [check]);
  const problemLines = useMemo(() => new Set(problems.filter((p) => p.line > 0).map((p) => p.line)), [problems]);
  const problemNodes = useMemo(() => new Set(problems.map((p) => p.node).filter((s): s is string => !!s)), [problems]);
  const dirty = !viewing && (isNew || (savedYaml != null && yaml !== savedYaml));
  // Moving nodes changes only the layout block: that is no reason to warn before a run.
  const contentDirty = dirty && !isNew && savedYaml != null && withoutLayout(yaml) !== withoutLayout(savedYaml);
  const layoutOnly = dirty && !isNew && !contentDirty;
  useEffect(() => {
    setMessage((m) => (layoutOnly && m.trim() === "" ? MOVED : !layoutOnly && m === MOVED ? "" : m));
  }, [layoutOnly]);
  const nameProblem = isNew && !NAME.test(newName)
    ? "Start with a small letter, then use small letters, digits and dashes (at most 63)."
    : null;

  const fail = (e: unknown) => {
    setError((e as Error).message);
    setConflict(e instanceof ApiError && e.status === 409 && !isNew);
    setFailures((n) => n + 1);
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (saving || (!dirty && !isNew)) return;
    setError(null);
    setConflict(false);
    setNotice("");
    if (isNew && nameProblem) {
      fail(new Error(`Name: ${nameProblem}`));
      return;
    }
    if (!isNew && !message.trim()) {
      fail(new Error("Say what you changed in the message, so the version list makes sense later."));
      return;
    }
    setSaving(true);
    try {
      if (isNew) {
        const d = await pipelinesApi.create({
          name: newName,
          displayName: displayName.trim() || undefined,
          yaml,
          message: message.trim() || undefined,
        });
        go(href.pipeline(d.name));
        return;
      }
      const d = await pipelinesApi.save(name!, { yaml, message: message.trim() }, detail?.latestVersion);
      setDetail(d);
      setSavedYaml(yaml);
      setMessage("");
      setNotice(
        `Saved as version ${d.latestVersion}${d.latest && !d.latest.valid ? ", as a draft: fix its problems before running it" : ""}.`,
      );
      loadLists();
    } catch (err) {
      fail(err);
    } finally {
      setSaving(false);
    }
  };

  /** Loads the latest saved version in place of the text here, which stays one undo away. */
  const reloadLatest = async () => {
    if (!name) return;
    try {
      const d = await pipelinesApi.get(name);
      setDetail(d);
      if (d.latest) {
        edit(d.latest.yaml, "Loaded the latest version");
        setSavedYaml(d.latest.yaml);
      }
      setConflict(false);
      setError(null);
      loadLists();
      setNotice(
        `Loaded version ${d.latestVersion}. Your version is one undo away: press Undo on the canvas to get it back, then save again.`,
      );
    } catch (err) {
      fail(err);
    }
  };

  const runNow = async () => {
    if (!name) return;
    // From the text as it is now: a setting typed a moment ago may not have been drawn yet.
    const unsaved = savedYaml != null && withoutLayout(yamlNow.current) !== withoutLayout(savedYaml);
    if (unsaved && !confirmRun) {
      setConfirmRun(true);
      return;
    }
    setConfirmRun(false);
    setError(null);
    try {
      const run = await pipelinesApi.run(name);
      setNotice(`Run ${run.id} of version ${run.version} is queued.`);
      // Its progress is shown under the canvas, live.
      setStartedRun(run.id);
      dock.show("run");
      loadLists();
    } catch (err) {
      fail(err);
    }
  };

  const remove = async () => {
    if (!name || !window.confirm(`Delete ${detail?.displayName ?? name} with all its versions and runs? What its runs wrote stays.`))
      return;
    try {
      await pipelinesApi.remove(name);
      go(href.pipelines());
    } catch (err) {
      fail(err);
    }
  };

  const goTo = (p: PipelineProblem) => {
    if (view === "canvas" && p.node) {
      // The setting is the word the message starts with, or the key on the problem's line.
      const key =
        /Setting '([^']+)' of (?:node|step)/.exec(p.message)?.[1] ??
        /has no setting '([^']+)'/.exec(p.message)?.[1] ??
        /needs the setting '([^']+)'/.exec(p.message)?.[1] ??
        /^(?:Node|Step) [^:]+: ([a-z_][a-z0-9_]*):/.exec(p.message)?.[1] ??
        null;
      setSelected(p.node);
      setFocusRequest((r) => ({ node: p.node!, key, n: (r?.n ?? 0) + 1 }));
      return;
    }
    if (view === "canvas") setView("yaml");
    if (p.line > 0) window.setTimeout(() => editor.current?.goToLine(p.line, p.column), 0);
  };

  const startEmpty = () => {
    edit(emptyYaml(NAME.test(newName) ? newName : "my-pipeline"), "Started with an empty canvas.");
    setSelected(null);
    setView("canvas");
  };
  const startTemplate = () => {
    const n = NAME.test(newName) ? newName : "my-pipeline";
    edit(starterYaml(n), "Started from the template.");
    findStarterPicks()
      .then((picks) => setYaml((y) => (y === starterYaml(n) ? starterYaml(n, picks) : y)))
      .catch(() => {});
  };

  if (loadError && !detail) {
    return (
      <>
        <h1 tabIndex={-1}>{name}</h1>
        <p role="alert" className="error">
          {loadError}
        </p>
        <p>
          <a href={href.pipelines()}>All pipelines</a>
        </p>
      </>
    );
  }

  const title = isNew ? "New pipeline" : (detail?.displayName ?? name);
  // Always on the page, and changed only when a check finishes, so only results are announced;
  // "Checking…" is shown beside it but not announced on every keystroke.
  const summary = checkError
      ? `Could not check: ${checkError}`
      : check == null
        ? ""
        : problems.length === 0
          ? `No problems. ${check.nodes.length} ${check.nodes.length === 1 ? "node" : "nodes"}.`
          : `${problems.length} ${problems.length === 1 ? "problem" : "problems"}.`;

  return (
    <>
      <p className="small">
        <a href={href.pipelines()}>Pipelines</a>
        {viewing && name && (
          <>
            {" "}
            › <a href={href.pipeline(name)}>{detail?.displayName ?? name}</a>
          </>
        )}
      </p>
      <div className="pl-head">
        <h1 tabIndex={-1}>
          {title}
          {viewing && <span className="muted">, version {version}</span>}
        </h1>
        {!isNew && !viewing && steward && (
          <div className="buttons">
            <button
              type="button"
              className="primary"
              onClick={runNow}
              disabled={!detail?.latest?.valid}
              aria-describedby={contentDirty ? "pl-run-hint" : undefined}
            >
              Run now
            </button>
            {designer && (
              <button type="button" onClick={remove}>
                Delete
              </button>
            )}
          </div>
        )}
      </div>
      {/* Below the buttons, with its line kept, so nothing moves as it comes and goes (a click
          that starts as the text changes would otherwise miss its button). */}
      {!isNew && !viewing && steward && (
        <p id="pl-run-hint" className="small muted pl-run-hint">
          {contentDirty ? `Run now runs the saved version ${detail?.latestVersion}, not your unsaved changes.` : ""}
        </p>
      )}
      {confirmRun && (
        <div className="warning pl-confirm" role="alert">
          <p>
            You have changes that are not saved. Run now runs the saved version {detail?.latestVersion}, without them.
            Save first to run your changes.
          </p>
          <div className="buttons">
            <button type="button" className="primary" onClick={runNow}>
              Run version {detail?.latestVersion}
            </button>
            <button type="button" onClick={() => setConfirmRun(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {detail?.description && <p className="lead">{detail.description}</p>}
      {isNew && (
        <div className="lead pl-start">
          <p>
            Build the pipeline on the canvas, or write its YAML: both edit the same pipeline, and it is checked as you go.
            It starts from a template that reads from one of your sources and writes to one that allows writing; replace
            anything in angle brackets. <a href={docHref("pipelines")}>How pipelines are built</a>.
          </p>
          {steward && (
            <div className="buttons">
              <button type="button" onClick={startTemplate}>
                Start from the template
              </button>
              <button type="button" onClick={startEmpty}>
                Start with an empty canvas
              </button>
            </div>
          )}
        </div>
      )}
      {!isNew && !viewing && detail?.latest && (
        <p className="small muted">
          Version {detail.latestVersion}, saved {formatTime(detail.updatedAt)} by {detail.updatedBy}.
          {!detail.latest.valid && " It has problems, so it cannot run."}
          {detail.lastRun && (
            <>
              {" "}
              Last run: <RunStatus run={detail.lastRun} />{" "}
              <a href={href.pipelineRun(detail.lastRun.id)}>run {detail.lastRun.id}</a>.
            </>
          )}
        </p>
      )}
      {viewing && shown && (
        <p className="small muted">
          Saved {formatTime(shown.createdAt)} by {shown.author}
          {shown.message && <>: “{shown.message}”</>}. Read-only.{" "}
          {name && <a href={href.pipeline(name)}>Back to the latest version</a>}
        </p>
      )}
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      {conflict && (
        <div className="pl-conflict">
          <p className="small">Your changes are still here, not saved.</p>
          <div className="buttons">
            <button
              type="button"
              onClick={() => {
                setView("yaml");
                window.setTimeout(() => editor.current?.goToLine(1), 0);
              }}
            >
              Show my version in the YAML tab
            </button>
            <button type="button" onClick={reloadLatest}>
              Load the latest and keep mine as an undo step
            </button>
          </div>
        </div>
      )}

      {isNew && (
        <div className="field-row">
          <div className="field">
            <label htmlFor="pl-name">Name</label>
            <input
              id="pl-name"
              value={newName}
              required
              autoComplete="off"
              spellCheck={false}
              aria-invalid={nameProblem ? true : undefined}
              aria-describedby="pl-name-hint"
              onChange={(e) => {
                const v = e.target.value.trim();
                if (NAME.test(v)) setYaml((y) => renamed(y, v));
                setNewName(v);
              }}
            />
            <p id="pl-name-hint" className={nameProblem ? "hint pl-bad" : "hint"}>
              {nameProblem ?? "Fixed once saved: small letters, digits and dashes, such as customer-billing."}
            </p>
          </div>
          <div className="field">
            <label htmlFor="pl-display">Display name (optional)</label>
            <input id="pl-display" value={displayName} maxLength={200} onChange={(e) => setDisplayName(e.target.value)} />
          </div>
        </div>
      )}

      <section aria-labelledby="pl-build-h" className={`panel pl-build${view === "canvas" ? " pl-wide" : ""}`}>
        <h2 id="pl-build-h" className="visually-hidden">
          {editable ? "Build the pipeline" : "The pipeline"}
        </h2>
        <div className="pl-view-tabs">
          <Tabs
            label="How to show the pipeline"
            idBase={tabsId}
            selected={view}
            onSelect={(v) => setView(v as View)}
            tabs={[
              { id: "canvas", label: "Canvas" },
              { id: "yaml", label: "YAML" },
            ]}
          />
        </div>
        <TabPanel idBase={tabsId} selected={view}>
          {view === "canvas" ? (
            <Suspense fallback={<p role="status">Loading the canvas…</p>}>
              <CanvasView
                yaml={text}
                onEdit={edit}
                onUndo={undo}
                onRedo={redo}
                canUndo={undos.current.length > 0}
                canRedo={redos.current.length > 0}
                readOnly={!editable}
                check={check}
                selected={selected}
                onSelect={setSelected}
                focusRequest={focusRequest}
                run={canvasRun}
                title={title ?? "the pipeline"}
                memory={canvasMemory.current}
                dock={dockControl}
              />
            </Suspense>
          ) : (
            <div className="pl-layout">
              <div className="pl-yaml">
                <p id="yaml-hint" className="hint">
                  {editable
                    ? "Tab indents by two spaces; press Escape, then Tab, to move on. Problems are listed below with their lines."
                    : viewing
                      ? "A saved version, shown as it was saved."
                      : "Your role can read this pipeline. Stewards edit it."}
                </p>
                <YamlEditor
                  ref={editor}
                  id="pl-yaml"
                  label={`YAML of ${title}`}
                  value={text}
                  onChange={(v) => edit(v, "Typed in the YAML", "yaml")}
                  readOnly={!editable}
                  problemLines={problemLines}
                  describedBy="yaml-hint pl-check-summary"
                />
              </div>
              <div className="pl-graph">
                <h3 id="graph-h">Nodes</h3>
                {check && check.nodes.length > 0 ? (
                  <Suspense fallback={<p role="status">Loading the graph…</p>}>
                    <PipelineDiagram nodes={check.nodes} edges={check.edges} problemNodes={problemNodes} />
                  </Suspense>
                ) : (
                  <p className="muted">{checking ? "Checking…" : "The nodes appear here once the YAML can be read."}</p>
                )}
              </div>
            </div>
          )}
        </TabPanel>

        <PipelineDock idBase={dockId} tabs={dockTabs} tab={dockTab} open={dock.open} onTab={dock.show} onOpen={dock.setOpen}>
          {/* The canvas draws the Settings, Preview and Suggestions tabs here; kept while the canvas
              is shown, so it is there as soon as one of them is chosen. */}
          {view === "canvas" && <div ref={setDockHost} className="pl-dock-host" hidden={!CANVAS_TABS.has(dockTab)} />}
          {view !== "canvas" && CANVAS_TABS.has(dockTab) && (
            <p className="hint pl-dock-none">
              {dockTab === "settings" ? "A node's settings" : dockTab === "preview" ? "Previews of a node's rows" : "Suggestions"} are shown
              with the canvas.{" "}
              <button type="button" className="link-button" onClick={() => setView("canvas")}>
                Show the canvas
              </button>
            </p>
          )}
          {dockTab === "run" && (
            <>
              {runShown ? (
                <div className="pl-dock-run">
                  <div className="pl-dock-run-head">
                    <h3 className="cv-side-title">
                      Run {runShown.run.id} of version {runShown.run.version}
                      {runLook.unfinished && <span className="muted">, updating as it goes</span>}
                    </h3>
                    <a href={href.pipelineRun(runShown.run.id)}>Open run {runShown.run.id}: its timeline, log and what it wrote</a>
                  </div>
                  <p role="status" className="small notice">
                    {runLook.notice}
                  </p>
                  <RunFacts run={runShown.run} rowsRead={rowsRead(runShown, reads)} />
                  <h4>Nodes</h4>
                  <RunNodes detail={runShown} quality label={` ${runShown.run.id}`} />
                  {runShown.notes.length > 0 && (
                    <>
                      <h4>Notes</h4>
                      <ul className="small">
                        {runShown.notes.map((n, i) => (
                          <li key={i}>{n}</li>
                        ))}
                      </ul>
                    </>
                  )}
                </div>
              ) : runLook.error ? (
                <p role="alert" className="error">
                  {runLook.error}
                </p>
              ) : runs === null || runId != null ? (
                <p role="status">Loading…</p>
              ) : (
                <p>Not run yet.{steward ? " Run now runs the saved version, and its progress is shown here." : ""}</p>
              )}
            </>
          )}
          {dockTab === "nodes" && (
            <>
              <h3 className="visually-hidden">Nodes as a table</h3>
              {check && check.nodes.length > 0 ? (
                <div className="table-scroll" role="region" aria-label="Nodes table" tabIndex={0}>
                  <table className="small">
                    <thead>
                      <tr>
                        <th scope="col">Node</th>
                        <th scope="col">Type</th>
                        <th scope="col">Reads from</th>
                        <th scope="col">Columns out</th>
                        {canvasRun && (
                          <>
                            <th scope="col" className="num">Rows in</th>
                            <th scope="col" className="num">Rows out</th>
                            <th scope="col" className="num">Time</th>
                            <th scope="col" className="num">Rows a second</th>
                            <th scope="col">How its time went</th>
                            <th scope="col" className="num">Peak memory</th>
                            <th scope="col">Last run</th>
                          </>
                        )}
                      </tr>
                    </thead>
                    <tbody>
                      {check.nodes.map((s) => {
                        const ran = canvasRun?.nodes.find((r) => r.node === s.id);
                        const measured = nodeQualityWords(canvasRun?.quality?.find((q) => q.node === s.id));
                        const m = ran ? nodeMetrics(ran, canvasRun?.progress?.slowest ?? null) : null;
                        const outs = Object.values(s.columns).reduce((n, cols) => n + (cols?.length ?? 0), 0);
                        return (
                          <tr key={s.id}>
                            <th scope="row">
                              <button
                                type="button"
                                className="pl-link mono"
                                onClick={() => {
                                  if (view === "canvas") {
                                    setSelected(s.id);
                                    setFocusRequest((r) => ({ node: s.id, key: null, n: (r?.n ?? 0) + 1 }));
                                  } else {
                                    editor.current?.goToLine(s.line);
                                  }
                                }}
                                aria-label={view === "canvas" ? `${s.id}: select it on the canvas` : `${s.id}: go to line ${s.line}`}
                              >
                                {s.id}
                              </button>
                              {problemNodes.has(s.id) && <div className="pl-bad">Has a problem</div>}
                            </th>
                            <td>{s.type ?? "–"}</td>
                            <td className="mono">
                              {check.edges
                                .filter((e) => e.toNode === s.id)
                                .map((e) => `${e.fromNode}${e.fromPort === "out" ? "" : `.${e.fromPort}`}${e.toPort === "in" ? "" : ` → ${e.toPort}`}`)
                                .join(", ") || "–"}
                            </td>
                            <td className="pl-nodes-cols">
                              {Object.keys(s.columns).length === 0 ? (
                                "No outputs"
                              ) : (
                                <details>
                                  <summary>
                                    {outs} {outs === 1 ? "column" : "columns"}
                                  </summary>
                                  {columnsText(s)}
                                </details>
                              )}
                            </td>
                            {canvasRun && (
                              <>
                                <td className="num">{m?.rowsIn == null ? "–" : formatNumber(m.rowsIn)}</td>
                                <td className="num">{m?.rowsOut == null ? "–" : formatNumber(m.rowsOut)}</td>
                                <td className="num">{m ? formatDuration(m.durationMs) : "–"}</td>
                                <td className="num">{m?.rate == null ? "–" : rateWords(m.rate)}</td>
                                <td>
                                  {m?.split ? (
                                    <span className="pl-split-cell">
                                      <span className="pl-split-bar" role="img" aria-label={splitWords(m) ?? ""}>
                                        <span className="pl-split-work" style={{ width: shareOf(m.split.workingMs, m.durationMs ?? 0) }} />
                                        <span className="pl-split-wait" style={{ width: shareOf(m.split.waitingMs, m.durationMs ?? 0) }} />
                                        <span className="pl-split-held" style={{ width: shareOf(m.split.heldUpMs, m.durationMs ?? 0) }} />
                                      </span>
                                      <span className="small">{shareOf(m.split.heldUpMs, m.durationMs ?? 0)} held up</span>
                                    </span>
                                  ) : (
                                    "–"
                                  )}
                                  {m?.slowest && <div className="small pl-split-slowest">Held the run back most</div>}
                                </td>
                                <td className="num">{m ? formatBytes(m.peakMemory) : "–"}</td>
                                <td>
                                  {ran ? [ran.outcome ? ran.outcome.replace("_", " ") : "", measured ?? ""].filter(Boolean).join(" · ") || "–" : "–"}
                                </td>
                              </>
                            )}
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="muted">{checking ? "Checking…" : "The nodes are listed here once the pipeline can be read."}</p>
              )}
            </>
          )}
          {dockTab === "runs" && (
            <>
              <h3 className="visually-hidden">Runs</h3>
              {runs === null && <p role="status">Loading…</p>}
              {runs?.length === 0 && <p>Not run yet.</p>}
              {runs && runs.length > 0 && (
                <div className="table-scroll" role="region" aria-label="History table" tabIndex={0}>
                  <table>
                    <thead>
                      <tr>
                        <th scope="col">Run</th>
                        <th scope="col">Status</th>
                        <th scope="col">Version</th>
                        <th scope="col">Asked for</th>
                        <th scope="col">Took</th>
                        <th scope="col">Rows written</th>
                      </tr>
                    </thead>
                    <tbody>
                      {runs.map((r) => (
                        <tr key={r.id}>
                          <th scope="row">
                            <a href={href.pipelineRun(r.id)}>Run {r.id}</a>
                          </th>
                          <td>
                            <RunStatus run={r} />
                            {r.error && <div className="small muted">{r.error}</div>}
                          </td>
                          <td>{r.version}</td>
                          <td className="small">
                            {formatTime(r.requestedAt)} by {r.requestedBy}
                          </td>
                          <td>{runTime(r)}</td>
                          <td>{formatNumber(r.rowsWritten)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
          {dockTab === "versions" && (
            <>
              <h3 className="visually-hidden">Versions</h3>
              {versions === null && <p role="status">Loading…</p>}
              {versions && (
                <ol className="pl-versions" reversed>
                  {versions.map((v) => (
                    <li key={v.n}>
                      <a href={href.pipeline(name!, v.n)} aria-current={version === v.n ? "page" : undefined}>
                        Version {v.n}
                      </a>
                      {v.n === detail?.latestVersion && " (latest)"}
                      {!v.valid && (
                        <span className="pl-bad">
                          {" "}
                          · {v.problems} {v.problems === 1 ? "problem" : "problems"}
                        </span>
                      )}
                      <div className="small muted">
                        {formatTime(v.createdAt)} by {v.author}
                        {v.message && <>: {v.message}</>}
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </>
          )}
        </PipelineDock>

        <div className="pl-problems">
          {checking && (
            <p className="pl-summary muted" aria-hidden="true">
              Checking…
            </p>
          )}
          <p id="pl-check-summary" className={problems.length > 0 ? "pl-summary pl-bad" : "pl-summary"} aria-live="polite">
            {summary}
          </p>
          {problems.length > 0 && (
            <ul className="pl-problem-list" aria-label="Problems">
              {problems.map((p, i) => (
                <li key={i}>
                  {p.line > 0 || (view === "canvas" && p.node) ? (
                    <button type="button" className="pl-problem" onClick={() => goTo(p)}>
                      {p.line > 0 && <span className="pl-problem-at">Line {p.line}</span>}
                      {p.node && <span className="mono"> ({p.node})</span>}: {p.message}
                    </button>
                  ) : (
                    <span className="pl-problem">
                      {p.node && <span className="mono">{p.node}: </span>}
                      {p.message}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
          {check && check.notes.length > 0 && (
            <details className="small">
              <summary>
                {check.notes.length} {check.notes.length === 1 ? "note" : "notes"} (do not stop a run)
              </summary>
              <ul>
                {check.notes.map((n, i) => (
                  <li key={i}>
                    {n.line > 0 ? `Line ${n.line}: ` : ""}
                    {n.message}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
        {(editable || (isNew && designer)) && (
          <form className="pl-save" onSubmit={save}>
            <div className="field">
              <label htmlFor="pl-message">{isNew ? "Message (optional)" : "What changed"}</label>
              <input
                id="pl-message"
                value={message}
                maxLength={500}
                placeholder={isNew ? "First version" : "For example: drop test customers"}
                onChange={(e) => setMessage(e.target.value)}
              />
            </div>
            <div className="buttons">
              <button type="submit" className="primary" aria-disabled={saving || (!dirty && !isNew)}>
                {saving ? "Saving…" : isNew ? "Save pipeline" : "Save new version"}
              </button>
              {!isNew && dirty && savedYaml != null && (
                <button type="button" onClick={() => edit(savedYaml, "Undid my changes")}>
                  Undo my changes
                </button>
              )}
            </div>
            <p className="hint">
              {problems.length > 0
                ? "A pipeline with problems can be saved as a draft, but it cannot run until they are fixed."
                : dirty
                  ? "Unsaved changes."
                  : "No unsaved changes."}
            </p>
          </form>
        )}
      </section>
    </>
  );
}
