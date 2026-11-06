import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Tabs, TabPanel, useTabsId } from "../components/ui";
import {
  allows,
  ApiError,
  flowsApi,
  formatNumber,
  formatTime,
  type AccessRole,
  type FlowDetail,
  type FlowProblem,
  type FlowRun,
  type FlowRunDetail,
  type FlowValidation,
  type FlowVersion,
  type FlowVersionSummary,
  samplesApi,
} from "../api";
import { go, href } from "../router";
import { useTitle } from "../components/useTitle";
import { docHref } from "../docs/links";
import { YamlEditor, type YamlEditorHandle } from "../pipelines/YamlEditor";
import { PipelineDock, useDock, type DockTab } from "../pipelines/PipelineDock";
import type { FlowSelection } from "./FlowDiagram";
import { FlowAddTools, FlowNodesTable, FlowSettings, usePipelineNames } from "./FlowCanvasTools";
import { FlowRunFacts, took } from "./FlowRunSummary";
import { connect, setPositions, type Change } from "./edit";
import { readFlowGraph, type FlowGraph } from "./graph";
import { FlowRunStatus, RunFlowButton } from "./RunFlow";
import { DELETE_BUTTON_ID, DeleteFlowButton, DeleteFlowConfirm } from "./DeleteFlow";
import { starterFlow } from "./template";
import "../pipelines/pipelines.css";

const FlowDiagram = lazy(() => import("./FlowDiagram"));

const CHECK_DELAY_MS = 600;
const DRAW_DELAY_MS = 400;
/** How many changes made on the diagram can be undone. */
const UNDO_DEPTH = 50;

type View = "diagram" | "yaml";
const VIEW_KEY = "kilnmark.flow.view";
function savedView(): View {
  try {
    return window.localStorage.getItem(VIEW_KEY) === "yaml" ? "yaml" : "diagram";
  } catch {
    return "diagram";
  }
}
/** Whether the panel under the flow is open, remembered apart from the pipeline page's. */
const FLOW_DOCK_KEY = "kilnmark.flow.panel";

/**
 * One flow, laid out as a pipeline's page is: its diagram or its YAML, one tab at a time, both
 * views of one document; under them a panel with its nodes, the chosen node's or connection's
 * settings, its latest run, its runs and its versions; then the problems (choosing one moves the
 * caret to its line in the YAML) and saving. The YAML is checked a moment after each change.
 * Designers save it with a message, and can change the flow on its diagram and in the panel: each
 * change there is a change to the YAML, which stays the one record. Stewards run the saved version,
 * with a dialog for its parameters; designers and admins delete it, after a confirmation that says
 * what goes and what stays. With name null, a new flow from a starter or from a Library
 * template ({@code template}, its file); with a version, that saved version, read-only.
 */
export function FlowPage({
  name,
  version,
  role,
  template,
}: {
  name: string | null;
  version: number | null;
  role: AccessRole;
  template?: string | null;
}) {
  const steward = allows(role, "steward");
  const designer = allows(role, "designer");
  const isNew = name == null;
  const viewing = version != null;
  const editable = designer && !viewing;
  useTitle(isNew ? "New flow" : viewing ? `${name}, version ${version}` : (name ?? "Flow"));

  const [detail, setDetail] = useState<FlowDetail | null>(null);
  const [shown, setShown] = useState<FlowVersion | null>(null);
  const [yaml, setYaml] = useState(isNew ? starterFlow() : "");
  const [savedYaml, setSavedYaml] = useState<string | null>(null);
  const [mine, setMine] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const [check, setCheck] = useState<FlowValidation | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [failures, setFailures] = useState(0);
  const [conflict, setConflict] = useState(false);
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [versions, setVersions] = useState<FlowVersionSummary[] | null>(null);
  const [runs, setRuns] = useState<FlowRun[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const editor = useRef<YamlEditorHandle>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);
  const sequence = useRef(0);
  const tabsId = useTabsId();
  const dockId = useTabsId();
  const [view, setViewState] = useState<View>(savedView);
  const setView = useCallback((v: View) => {
    setViewState(v);
    try {
      window.localStorage.setItem(VIEW_KEY, v);
    } catch {
      // A remembered view is a convenience only.
    }
  }, []);
  const dock = useDock(dockId, FLOW_DOCK_KEY);
  const pipelines = usePipelineNames(designer);
  // The node or connection chosen on the diagram or in the Nodes tab; choosing one shows its settings.
  const [selected, setSelected] = useState<FlowSelection | null>(null);
  const selectedNow = useRef(selected);
  selectedNow.current = selected;
  const { show: showTab, setTab: setDockTab } = dock;
  const choose = useCallback(
    (s: FlowSelection | null) => {
      if (s) showTab("settings");
      if (JSON.stringify(selectedNow.current) === JSON.stringify(s)) return;
      selectedNow.current = s;
      setSelected(s);
    },
    [showTab],
  );
  // The panel's tabs, as a pipeline's: the nodes first, the chosen node's or connection's settings
  // (only while one is chosen, for designers), then the flow's latest run, its runs and versions.
  const dockTabs: DockTab[] = [
    "nodes",
    ...(editable && selected != null ? (["settings"] as const) : []),
    ...(isNew ? [] : (["run", "runs", "versions"] as const)),
  ];
  // When the tab shown goes (nothing is chosen any more): the flow's tab shown last; at first, Run,
  // or Nodes for a new flow.
  const lastFlowTab = useRef<DockTab | null>(null);
  const dockTab: DockTab = dockTabs.includes(dock.tab) ? dock.tab : (lastFlowTab.current ?? (isNew ? "nodes" : "run"));
  useEffect(() => {
    if (dockTab !== dock.tab) setDockTab(dockTab);
    if (dockTab !== "settings") lastFlowTab.current = dockTab;
  }, [dockTab, dock.tab, setDockTab]);
  // The latest run in full, for the Run and Nodes tabs: read only while one of them is shown, and
  // again whenever the runs list is.
  const [latestRun, setLatestRun] = useState<FlowRunDetail | null>(null);
  const [latestError, setLatestError] = useState<string | null>(null);

  useEffect(() => {
    if (failures > 0) errorRef.current?.focus();
  }, [failures]);

  const load = useCallback(async () => {
    if (isNew || name == null) return;
    try {
      const d = await flowsApi.get(name);
      setDetail(d);
      if (version != null) {
        setShown(await flowsApi.version(name, version));
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
    flowsApi.versions(name).then(setVersions).catch((e: Error) => setLoadError(e.message));
    flowsApi.runs(name).then(setRuns).catch((e: Error) => setLoadError(e.message));
  }, [isNew, name]);

  useEffect(() => {
    void load();
    loadLists();
  }, [load, loadLists]);

  // A new flow from a Library template: its YAML, named my-flow, with its <...> gaps for the check
  // to point at until they are filled in.
  // Only while the starter is untouched: nothing typed is replaced. Until it is known whether the
  // template applies, the usual starter does not pick a pipeline.
  const [fromTemplate, setFromTemplate] = useState<string | null>(null);
  const [templateState, setTemplateState] = useState<"none" | "pending" | "applied" | "failed">(
    isNew && template ? "pending" : "none",
  );
  useEffect(() => {
    if (!isNew || !template) return;
    let live = true;
    samplesApi
      .templates()
      .then((all) => {
        const t = all.find((x) => x.file === template && x.type === "flow");
        if (!live) return;
        if (!t) {
          setFromTemplate(`There is no flow template ${template}: starting from the usual one.`);
          setTemplateState("failed");
          return;
        }
        setYaml((y) => (y === starterFlow() ? t.yaml.replace(/^name:.*$/m, "name: my-flow") : y));
        setFromTemplate(`Started from the template ${t.name}. Replace each <…> with your own.`);
        setTemplateState("applied");
      })
      .catch((e: Error) => {
        if (!live) return;
        setFromTemplate(`The template could not be read (${e.message}): starting from the usual one.`);
        setTemplateState("failed");
      });
    return () => {
      live = false;
    };
  }, [isNew, template]);

  // A new flow's starter runs one of the person's pipelines once they are known, unless the YAML
  // has been changed by then.
  useEffect(() => {
    if (!isNew || templateState === "pending" || templateState === "applied" || !pipelines || pipelines.length === 0)
      return;
    setYaml((y) => (y === starterFlow() ? starterFlow(pipelines[0]) : y));
  }, [isNew, pipelines, templateState]);

  // Keep the runs table current while a run is queued or running. Only the lists: reloading the
  // flow would replace text being edited, and take in someone else's save without the conflict.
  const active = runs?.some((r) => r.status === "queued" || r.status === "running");
  useEffect(() => {
    if (!active) return;
    const t = window.setInterval(loadLists, 4000);
    return () => window.clearInterval(t);
  }, [active, loadLists]);

  const runShown = !isNew && (dockTab === "run" || (dockTab === "nodes" && !viewing)) && dock.open;
  useEffect(() => {
    const id = runs?.[0]?.id;
    if (id == null) {
      setLatestRun(null);
      return;
    }
    if (!runShown) return;
    let live = true;
    flowsApi
      .runDetail(id)
      .then((d) => {
        if (!live) return;
        setLatestRun(d);
        setLatestError(null);
      })
      .catch((e: Error) => live && setLatestError(e.message));
    return () => {
      live = false;
    };
  }, [runs, runShown]);

  const text = viewing ? (shown?.yaml ?? "") : yaml;
  // The diagram follows the text once typing pauses, and keeps the last flow it could read while
  // the YAML cannot be read, so the editor under it does not jump.
  const [drawnText, setDrawnText] = useState(text);
  useEffect(() => {
    const t = window.setTimeout(() => setDrawnText(text), DRAW_DELAY_MS);
    return () => window.clearTimeout(t);
  }, [text]);
  const drawn = useMemo(() => readFlowGraph(drawnText), [drawnText]);
  const lastDrawn = useRef<FlowGraph | null>(null);
  if (drawn.ok) lastDrawn.current = drawn.graph;
  const shownGraph = drawn.ok ? drawn.graph : lastDrawn.current;

  // Changes made on the diagram. The tools read the text as it is now, not as last drawn, so what
  // they change is what is in the editor.
  const live = useMemo(() => (editable ? readFlowGraph(yaml) : null), [editable, yaml]);
  const [canvasNote, setCanvasNote] = useState<{ text: string; bad: boolean } | null>(null);
  const undos = useRef<{ before: string; after: string; label: string }[]>([]);
  const [, setUndoTick] = useState(0);
  const applyEdit = (c: Change, label: string): boolean => {
    if ("error" in c) {
      setCanvasNote({ text: c.error, bad: true });
      return false;
    }
    if (c.text !== yaml) {
      undos.current = [...undos.current, { before: yaml, after: c.text, label }].slice(-UNDO_DEPTH);
      setUndoTick((n) => n + 1);
      setYaml(c.text);
      setDrawnText(c.text);
    }
    setCanvasNote({ text: label, bad: false });
    return true;
  };
  // A node or connection chosen and then renamed or removed in the YAML is no longer chosen.
  useEffect(() => {
    if (!selected || !live?.ok) return;
    const g = live.graph;
    const there =
      "node" in selected
        ? g.nodes.some((n) => n.id === selected.node)
        : g.connections.some((c) => c.index === selected.connection && c.from === selected.from && c.to === selected.to);
    if (!there) choose(null);
  }, [selected, live, choose]);

  // Undo is offered while the text is still what the last change left; typing in the YAML ends it.
  const lastChange = undos.current.at(-1);
  const canUndo = !!lastChange && lastChange.after === yaml;
  const undoChange = () => {
    if (!lastChange || !canUndo) return;
    undos.current = undos.current.slice(0, -1);
    setUndoTick((n) => n + 1);
    setYaml(lastChange.before);
    setDrawnText(lastChange.before);
    setCanvasNote({ text: `Undone: ${lastChange.label}`, bad: false });
  };

  // Check the YAML a moment after typing stops. Only the latest answer counts.
  useEffect(() => {
    if (!text.trim()) return;
    const n = ++sequence.current;
    setChecking(true);
    const t = window.setTimeout(() => {
      flowsApi
        .validate(text, isNew ? undefined : (name ?? undefined))
        .then((v) => {
          if (sequence.current !== n) return;
          setCheck(v);
          setCheckError(null);
        })
        .catch((e: Error) => {
          if (sequence.current === n) setCheckError(e.message);
        })
        .finally(() => {
          if (sequence.current === n) setChecking(false);
        });
    }, CHECK_DELAY_MS);
    return () => window.clearTimeout(t);
  }, [text, isNew, name]);

  const problems = useMemo(() => check?.problems ?? [], [check]);
  const problemLines = useMemo(() => new Set(problems.filter((p) => p.line > 0).map((p) => p.line)), [problems]);
  const dirty = !viewing && (isNew || (savedYaml != null && yaml !== savedYaml));

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
    if (!isNew && !message.trim()) {
      fail(new Error("Say what you changed in the message, so the version list makes sense later."));
      return;
    }
    setSaving(true);
    try {
      if (isNew) {
        const d = await flowsApi.create({ yaml, message: message.trim() || undefined });
        go(href.flow(d.name));
        return;
      }
      const d = await flowsApi.save(name!, { yaml, message: message.trim() }, detail?.latestVersion);
      setDetail(d);
      setSavedYaml(yaml);
      setMine(null);
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

  /** Loads the latest saved version in place of the text here, keeping that text to put back. */
  const reloadLatest = async () => {
    if (!name) return;
    try {
      const d = await flowsApi.get(name);
      setDetail(d);
      if (d.latest) {
        setMine(yaml);
        setYaml(d.latest.yaml);
        setSavedYaml(d.latest.yaml);
      }
      setConflict(false);
      setError(null);
      loadLists();
      setNotice(`Loaded version ${d.latestVersion}. Your text is kept: choose Put my changes back to see it again.`);
    } catch (err) {
      fail(err);
    }
  };

  /** Shows a problem's line in the YAML, moving to the YAML tab first when the diagram is shown. */
  const goTo = (p: FlowProblem) => {
    if (p.line <= 0) return;
    if (view === "yaml") {
      editor.current?.goToLine(p.line, p.column);
      return;
    }
    setView("yaml");
    window.setTimeout(() => editor.current?.goToLine(p.line, p.column), 0);
  };

  const queued = (run: FlowRun) => {
    setError(null);
    setNotice(`Run ${run.id} of version ${run.version} is queued.`);
    loadLists();
  };

  const title = detail?.displayName ?? name ?? check?.name ?? "the new flow";
  const summary = checkError
    ? `Could not check the YAML: ${checkError}`
    : !check
      ? checking
        ? "Checking…"
        : ""
      : problems.length === 0
        ? "No problems found."
        : `${problems.length} ${problems.length === 1 ? "problem" : "problems"} found.`;
  const unsavedRun = dirty && !isNew;

  if (loadError && !detail && !isNew) {
    return (
      <>
        <h1 tabIndex={-1}>{name}</h1>
        <p role="alert" className="error">
          {loadError}
        </p>
        <p>
          <a href={href.flows()}>Back to flows</a>
        </p>
      </>
    );
  }

  return (
    <>
      <p className="small">
        <a href={href.flows()}>Flows</a>
        {viewing && name && (
          <>
            {" "}
            › <a href={href.flow(name)}>{detail?.displayName ?? name}</a>
          </>
        )}
      </p>
      <div className="pl-head">
        <h1 tabIndex={-1}>{isNew ? "New flow" : viewing ? `${title}, version ${version}` : title}</h1>
        {!isNew && !viewing && steward && name && (
          <div className="pl-head-actions">
            <RunFlowButton
              name={name}
              label={`Run ${title} now`}
              parameters={detail?.latest?.parameters}
              disabled={!detail?.latest?.valid}
              title={detail?.latest?.valid ? undefined : "Fix its problems first"}
              primary
              onQueued={queued}
              onError={(m) => fail(new Error(m))}
            />
            <DeleteFlowButton
              role={role}
              onAsk={() => {
                setError(null);
                setConfirmDelete(true);
              }}
            />
          </div>
        )}
      </div>
      {!isNew && !viewing && steward && (
        <p id="fl-run-hint" className="small muted pl-run-hint">
          {unsavedRun ? `Run now runs the saved version ${detail?.latestVersion}, not your unsaved changes.` : ""}
        </p>
      )}
      {confirmDelete && name && !viewing && (
        <DeleteFlowConfirm
          name={name}
          title={title}
          sample={detail?.library === "sample"}
          onCancel={() => {
            setConfirmDelete(false);
            document.getElementById(DELETE_BUTTON_ID)?.focus();
          }}
          onError={(e) => {
            // A run that is going is the usual reason; it is not a save conflict.
            setConfirmDelete(false);
            setError((e as Error).message);
            setFailures((n) => n + 1);
          }}
        />
      )}
      {detail?.description && <p className="lead">{detail.description}</p>}
      {isNew && (
        <p className="lead">
          Build the flow on its diagram, or write its YAML: both edit the same flow, its nodes and the connections that
          say what runs after what. It is checked as you go; the name on its <span className="mono">name:</span> line is
          fixed once saved.{" "}
          <a href={docHref("flows")}>How flows are built</a>.
        </p>
      )}
      {!isNew && !viewing && detail?.latest && (
        <p className="small muted">
          Version {detail.latestVersion}, saved {formatTime(detail.updatedAt)} by {detail.updatedBy}.
          {!detail.latest.valid && " It has problems, so it cannot run."}
          {detail.lastRun && (
            <>
              {" "}
              Last run: <FlowRunStatus run={detail.lastRun} />{" "}
              <a href={href.flowRun(detail.lastRun.id)}>run {detail.lastRun.id}</a>.
            </>
          )}
        </p>
      )}
      {viewing && shown && (
        <p className="small muted">
          Saved {formatTime(shown.createdAt)} by {shown.author}
          {shown.message && <>: “{shown.message}”</>}. Read-only.{" "}
          {name && <a href={href.flow(name)}>Back to the latest version</a>}
        </p>
      )}
      {fromTemplate && <p className="hint">{fromTemplate}</p>}
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
            <button type="button" onClick={reloadLatest}>
              Load the latest version and keep my text
            </button>
          </div>
        </div>
      )}
      {mine != null && editable && (
        <div className="buttons">
          <button
            type="button"
            onClick={() => {
              setYaml(mine);
              setMine(null);
            }}
          >
            Put my changes back
          </button>
        </div>
      )}

      <section aria-labelledby="fl-build-h" className="panel pl-build">
        <h2 id="fl-build-h" className="visually-hidden">
          {editable ? "Build the flow" : "The flow"}
        </h2>
        <div className="pl-view-tabs">
          <Tabs
            label="How to show the flow"
            idBase={tabsId}
            selected={view}
            onSelect={(v) => setView(v as View)}
            tabs={[
              { id: "diagram", label: "Diagram" },
              { id: "yaml", label: "YAML" },
            ]}
          />
        </div>
        <TabPanel idBase={tabsId} selected={view}>
          {view === "diagram" ? (
            <>
              {shownGraph ? (
                // The note lies over the diagram, so nothing below it moves while the YAML is being typed.
                <div className="fd-holder">
                  {!drawn.ok && <p className="fd-stale-note small">{drawn.error} The diagram shows the flow as it last could be read.</p>}
                  <Suspense fallback={<p role="status">Loading…</p>}>
                    <FlowDiagram
                      graph={shownGraph}
                      label={`Diagram of ${viewing ? `version ${version} of ` : ""}the flow`}
                      editable={editable && drawn.ok}
                      selected={selected}
                      onSelect={choose}
                      onMove={(positions) => applyEdit(setPositions(yaml, positions), "Moved a node.")}
                      onConnect={(from, to) => applyEdit(connect(yaml, from, to), `Connected ${from} to ${to}.`)}
                    />
                  </Suspense>
                </div>
              ) : (
                <p className="muted">{drawn.ok ? "" : `${drawn.error} Change it in the YAML tab.`}</p>
              )}
              {editable && (
                <>
                  <div className="fd-note-row">
                    <p id="fd-note" tabIndex={-1} role="status" className={canvasNote?.bad ? "error small" : "small muted"}>
                      {canvasNote?.text ?? ""}
                    </p>
                    {canUndo && (
                      <button type="button" onClick={undoChange}>
                        Undo
                      </button>
                    )}
                  </div>
                  {!live?.ok && <p className="small muted">The diagram can be changed again once the YAML can be read.</p>}
                </>
              )}
            </>
          ) : (
            <>
              <p id="fl-yaml-hint" className="hint">
                {editable
                  ? "Tab indents by two spaces; press Escape, then Tab, to move on. Problems are listed below with their lines."
                  : viewing
                    ? "A saved version, shown as it was saved."
                    : "Your role can read this flow. Designers change it."}
              </p>
              <YamlEditor
                ref={editor}
                id="fl-yaml"
                label={`YAML of ${title}`}
                value={text}
                onChange={setYaml}
                readOnly={!editable}
                problemLines={problemLines}
                describedBy="fl-yaml-hint fl-check-summary"
              />
            </>
          )}
        </TabPanel>

        <PipelineDock
          idBase={dockId}
          tabs={dockTabs}
          tab={dockTab}
          open={dock.open}
          onTab={dock.show}
          onOpen={dock.setOpen}
          label="Panels under the flow"
        >
          {dockTab === "nodes" && (
            <>
              <h3 className="visually-hidden">Nodes</h3>
              {shownGraph ? (
                <FlowNodesTable
                  graph={shownGraph}
                  // The latest run is of the latest version: an old version's page shows none.
                  run={viewing ? null : latestRun}
                  onChoose={
                    editable && drawn.ok
                      ? (node) => {
                          setView("diagram");
                          choose({ node });
                        }
                      : undefined
                  }
                />
              ) : (
                <p className="muted">The nodes are listed here once the flow can be read.</p>
              )}
              {editable &&
                (view !== "diagram" ? (
                  <p className="hint fd-dock-add">
                    Nodes are added and connected with the diagram.{" "}
                    <button type="button" className="link-button" onClick={() => setView("diagram")}>
                      Show the diagram
                    </button>
                  </p>
                ) : live?.ok ? (
                  <div className="fd-dock-add">
                    <FlowAddTools graph={live.graph} text={yaml} pipelines={pipelines} onSelect={choose} apply={applyEdit} />
                  </div>
                ) : (
                  <p className="small muted fd-dock-add">Nodes can be added again once the YAML can be read.</p>
                ))}
            </>
          )}
          {dockTab === "settings" &&
            (view !== "diagram" ? (
              <p className="hint">
                A node&apos;s settings are shown with the diagram.{" "}
                <button type="button" className="link-button" onClick={() => setView("diagram")}>
                  Show the diagram
                </button>
              </p>
            ) : live?.ok ? (
              <FlowSettings graph={live.graph} text={yaml} pipelines={pipelines} selected={selected} onSelect={choose} apply={applyEdit} />
            ) : (
              <p className="small muted">The settings can be changed again once the YAML can be read.</p>
            ))}
          {dockTab === "run" && (
            <>
              {latestRun ? (
                <div className="pl-dock-run">
                  <div className="pl-dock-run-head">
                    <h3 className="cv-side-title">
                      Run {latestRun.run.id} of version {latestRun.run.version}
                      {(latestRun.run.status === "queued" || latestRun.run.status === "running") && (
                        <span className="muted">, updating as it goes</span>
                      )}
                    </h3>
                    <a href={href.flowRun(latestRun.run.id)}>
                      Open run {latestRun.run.id}: where its time went, its chart and its nodes
                    </a>
                  </div>
                  <FlowRunFacts detail={latestRun} />
                </div>
              ) : runs === null || (runs.length > 0 && latestError == null) ? (
                <p role="status">Loading…</p>
              ) : latestError ? (
                <p role="alert" className="error">
                  {latestError}
                </p>
              ) : (
                <p>Not run yet.{steward ? " Run now runs the saved version, and its progress is shown here." : ""}</p>
              )}
            </>
          )}
          {dockTab === "runs" && (
            <>
              <h3 className="visually-hidden">Runs</h3>
              {runs === null && <p role="status">Loading…</p>}
              {runs?.length === 0 && <p>Not run yet.{steward ? " Run now runs the saved version." : ""}</p>}
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
                            <a href={href.flowRun(r.id)}>Run {r.id}</a>
                          </th>
                          <td>
                            <FlowRunStatus run={r} />
                            {r.error && <div className="small muted">{r.error}</div>}
                          </td>
                          <td>{r.version}</td>
                          <td className="small">
                            {formatTime(r.requestedAt)} by {r.requestedBy}
                          </td>
                          <td>{took(r.startedAt, r.finishedAt)}</td>
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
                      <a href={href.flow(name!, v.n)} aria-current={version === v.n ? "page" : undefined}>
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
          <p id="fl-check-summary" className={problems.length > 0 ? "pl-summary pl-bad" : "pl-summary"} aria-live="polite">
            {summary}
          </p>
          {problems.length > 0 && (
            <ul className="pl-problem-list" aria-label="Problems">
              {problems.map((p, i) => (
                <li key={i}>
                  {p.line > 0 ? (
                    <button type="button" className="pl-problem" onClick={() => goTo(p)}>
                      <span className="pl-problem-at">Line {p.line}</span>
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
          {check && check.parameters.length > 0 && (
            <p className="small muted">
              Parameters:{" "}
              {check.parameters.map((p, i) => (
                <span key={p.name}>
                  {i > 0 && ", "}
                  <span className="mono">{p.name}</span> ({p.type}
                  {p.required ? ", needed when it runs" : ""})
                </span>
              ))}
              .
            </p>
          )}
        </div>
        {editable && (
          <form className="pl-save" onSubmit={save}>
            <div className="field">
              <label htmlFor="fl-message">{isNew ? "Message (optional)" : "What changed"}</label>
              <input
                id="fl-message"
                value={message}
                maxLength={500}
                placeholder={isNew ? "First version" : "For example: stop when over 5% is set aside"}
                onChange={(e) => setMessage(e.target.value)}
              />
            </div>
            <div className="buttons">
              <button type="submit" className="primary" aria-disabled={saving || (!dirty && !isNew)}>
                {saving ? "Saving…" : isNew ? "Save flow" : "Save new version"}
              </button>
              {!isNew && dirty && savedYaml != null && (
                <button type="button" onClick={() => setYaml(savedYaml)}>
                  Undo my changes
                </button>
              )}
            </div>
            <p className="hint">
              {problems.length > 0
                ? "A flow with problems can be saved as a draft, but it cannot run until they are fixed."
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
