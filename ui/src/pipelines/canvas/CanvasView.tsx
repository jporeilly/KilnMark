import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent } from "react";
import {
  applyNodeChanges,
  Background,
  getNodesBounds,
  MarkerType,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type Edge,
  type FinalConnectionState,
  type NodeChange,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { flushSync } from "react-dom";
import {
  IconArrowBackUp,
  IconArrowForwardUp,
  IconFocus2,
  IconKeyboard,
  IconLayoutDistributeHorizontal,
  IconPlug,
  IconSparkles,
  IconTextSize,
  IconX,
  IconZoomIn,
  IconZoomOut,
} from "@tabler/icons-react";
import {
  api,
  formatNumber,
  pipelinesApi,
  type PipelineRunProgress,
  type PipelineStepQuality,
  type PipelineStepRun,
  type PipelineValidation,
  type Source,
  type StepType,
} from "../../api";
import { useColourScheme } from "../../theme/appearance";
import { columnsText } from "../outline";
import { stepQualityWords } from "../quality";
import { predictedWords, predictionApi, predictionsByStep, type Prediction, type Suggestion } from "../prediction";
import { liveEdgeRows, liveSteps } from "../live";
import { kindWord, problemsByStep, stepLabel, stepSummary } from "./insight";
import { autoLayout, makeRoom, nodeHeight, placed, spotAfter, type EdgeLabel, type LayoutSizes } from "./layout";
import {
  addStep,
  catalogueOf,
  connect,
  connectProblem,
  disconnect,
  duplicateStep,
  insertSteps,
  portsOf,
  possibleConnections,
  readGraph,
  refText,
  removeStep,
  renameStep,
  setPositions,
  setSetting,
  type Catalogue,
  type Change,
  type Graph,
  type GraphEdge,
  type Point,
  type Value,
} from "./model";
import { Palette, rememberUsed } from "./Palette";
import { SettingsPanel } from "./SettingsPanel";
import { StepNode, badgeRows, runWords, type StepNodeType, type StepRunInfo } from "./StepNode";
import "./canvas.css";

export type CanvasRun = {
  id: number;
  version: number;
  status: string;
  steps: PipelineStepRun[];
  /** Each write's and gate's measured quality; empty from servers before measured quality. */
  quality?: PipelineStepQuality[];
  /** While the run goes: where its rows are (rows and rate per step, the slowest step, rows per connection). */
  progress?: PipelineRunProgress | null;
};

export type CanvasProps = {
  yaml: string;
  /** Applies a change made on the canvas; `coalesce` merges quick edits of one setting into one undo step. */
  onEdit: (next: string, label: string, coalesce?: string) => void;
  onUndo: () => string | null;
  onRedo: () => string | null;
  canUndo: boolean;
  canRedo: boolean;
  readOnly: boolean;
  check: PipelineValidation | null;
  selected: string | null;
  onSelect: (id: string | null) => void;
  /** Set by the problems list: select this step and move to its setting. */
  focusRequest: { step: string; key: string | null; n: number } | null;
  run: CanvasRun | null;
  title: string;
  /** Kept by the page across switches to the YAML tab: the last graph that could be read. */
  memory: { lastGood?: Graph | null };
};

let catalogueLoad: Promise<StepType[]> | null = null;
const loadCatalogue = () => (catalogueLoad ??= pipelinesApi.steps().catch((e) => {
  catalogueLoad = null;
  throw e;
}));

const nodeTypes = { step: StepNode };
const SuggestionsPanel = lazy(() => import("./SuggestionsPanel"));
/** How long after the last change the predicted scores are worked out again. */
const PREDICT_AFTER_MS = 700;
/** Setting kinds typed a character at a time: written to the YAML once typing pauses. */
const TYPED = new Set(["text", "number", "expression", "expressions", "conditions", "column_map", "asset", "rules"]);
/** The smallest zoom at which a step's words can still be read. */
const MIN_READABLE = 0.7;
const reducedMotion = () => typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
const isTyping = (el: EventTarget | null) =>
  el instanceof HTMLElement && (el.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName));
const edgeId = (e: GraphEdge) => `e:${e.from}.${e.fromPort}>${e.to}.${e.toPort}`;
const fromHandles = (c: { source: string | null; sourceHandle?: string | null; target: string | null; targetHandle?: string | null }): GraphEdge | null =>
  c.source && c.target
    ? { from: c.source, fromPort: (c.sourceHandle ?? "out:out").replace(/^out:/, ""), to: c.target, toPort: (c.targetHandle ?? "in:in").replace(/^in:/, "") }
    : null;
const edgeWords = (e: GraphEdge) => `${refText(e.from, e.fromPort)} to ${e.to}.${e.toPort}`;

const SHORTCUTS: [string, string][] = [
  ["Tab, Shift+Tab", "Move between steps (and on to the settings)"],
  ["Arrow keys", "Move to a connected step: right to the next, left to the one before, up and down between steps beside each other"],
  ["Shift+Arrow keys", "Move the step on the canvas"],
  ["Alt+Arrow keys", "Pan the canvas"],
  ["C", "Connect to… : connect the step to another"],
  ["A", "Add a step: open the step list and go to its search"],
  ["Arrow keys in the step list", "Move through the folders and steps; Right opens a folder, Left closes it; Enter adds the step"],
  ["Enter", "Go to the step's settings"],
  ["Delete or Backspace", "Remove the step (or a connection selected with the mouse)"],
  ["Ctrl+D", "Duplicate the step"],
  ["Ctrl+Z, Ctrl+Shift+Z or Ctrl+Y", "Undo, redo"],
  ["+ and -", "Zoom in and out"],
  ["F", "Fit the whole pipeline in view"],
  ["R", "Readable size: the first steps at a size you can read"],
  ["L", "Lay the steps out again, left to right"],
  ["?", "These shortcuts"],
  ["Escape", "Clear the selection, or close a menu"],
];

function Canvas(props: CanvasProps) {
  const { yaml, onEdit, readOnly: lockedByPage, check, selected, onSelect, run } = props;
  const flow = useReactFlow();
  const scheme = useColourScheme();
  const [types, setTypes] = useState<StepType[] | null>(null);
  const [typesError, setTypesError] = useState<string | null>(null);
  const [sources, setSources] = useState<Source[] | null>(null);
  const [announce, setAnnounce] = useState("");
  const [refusal, setRefusal] = useState<string | null>(null);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [selectedEdge, setSelectedEdge] = useState<string | null>(null);
  const [connectFor, setConnectFor] = useState<string | null>(null);
  const [prediction, setPrediction] = useState<Prediction | null>(null);
  const [suggestOpen, setSuggestOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const toolbar = useRef<HTMLDivElement>(null);
  // The latest text, also for changes made before React has drawn the last one.
  const yamlRef = useRef(yaml);
  yamlRef.current = yaml;
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const flowBox = useRef<HTMLDivElement>(null);
  const searchBox = useRef<HTMLInputElement>(null);
  const connectDialog = useRef<HTMLDialogElement>(null);
  const keysDialog = useRef<HTMLDialogElement>(null);
  const pendingFocus = useRef<string | null>(null);
  const quietFocus = useRef<string | null>(null);
  const duration = reducedMotion() ? 0 : 200;

  useEffect(() => {
    let live = true;
    loadCatalogue()
      .then((t) => live && setTypes(t))
      .catch((e: Error) => live && setTypesError(e.message));
    api
      .sources()
      .then((s) => live && setSources(s))
      .catch(() => live && setSources(null));
    return () => {
      live = false;
    };
  }, []);

  const catalogue: Catalogue = useMemo(() => catalogueOf(types ?? []), [types]);
  const read = useMemo(() => readGraph(yaml, catalogue), [yaml, catalogue]);
  if (read.ok) props.memory.lastGood = read.graph;
  const graph = read.ok ? read.graph : (props.memory.lastGood ?? null);
  const broken = !read.ok;
  const readOnly = lockedByPage || broken || types == null;
  // An empty canvas opens the step list; otherwise it waits in the rail. (A saved pipeline is empty
  // text until it has loaded, which is not an empty canvas.)
  const stepCount = graph?.steps.length ?? -1;
  const loaded = yaml.trim() !== "" && types != null;
  useEffect(() => {
    if (loaded && stepCount === 0 && !lockedByPage) setPaletteOpen(true);
  }, [loaded, stepCount, lockedByPage]);
  // A selected step that is gone (undone, removed in the YAML) is no longer selected.
  useEffect(() => {
    if (selected && graph && !graph.steps.some((s) => s.id === selected)) onSelect(null);
  }, [graph, selected, onSelect]);
  const problems = useMemo(() => problemsByStep(check?.problems ?? []), [check]);
  const runSteps = useMemo(() => {
    const m = new Map<string, StepRunInfo>();
    const going = run != null && (run.status === "queued" || run.status === "running");
    if (going && run.steps.length === 0) {
      // Live: rows so far and the rate, from the run's latest progress.
      for (const [id, l] of liveSteps(run.progress))
        m.set(id, { rowsIn: l.rowsIn, rowsOut: l.rowsOut, outcome: null, error: null, live: { rate: l.rate, state: l.state, slowest: l.slowest } });
      return m;
    }
    for (const s of run?.steps ?? []) m.set(s.step, { rowsIn: s.rowsIn, rowsOut: s.rowsOut, outcome: s.outcome, error: s.error });
    return m;
  }, [run]);
  const runQuality = useMemo(() => new Map((run?.quality ?? []).map((q) => [q.step, q])), [run]);
  // Predicted quality from the catalogue's metadata, worked out again a moment after each change.
  const canPredict = read.ok && yaml.trim() !== "";
  useEffect(() => {
    if (!canPredict) return;
    const ctl = new AbortController();
    const t = window.setTimeout(() => {
      predictionApi
        .predict(yaml, ctl.signal)
        .then((p) => !ctl.signal.aborted && setPrediction(p))
        .catch(() => !ctl.signal.aborted && setPrediction(null));
    }, PREDICT_AFTER_MS);
    return () => {
      ctl.abort();
      window.clearTimeout(t);
    };
  }, [yaml, canPredict]);
  // An unreadable YAML shows the last pipeline that could be read, without estimates for it.
  const predicted = useMemo(() => predictionsByStep(read.ok ? prediction : null), [prediction, read.ok]);

  // ---- where the steps go: their real heights and the connections' labels ----

  // Each step's height as drawn by React Flow, once it has been; until then (and while it grows
  // after a run) the height worked out from its ports and rows of chips, whichever is larger.
  const [drawnHeights, setDrawnHeights] = useState<Record<string, number>>({});
  const heights = useMemo(() => {
    const out: Record<string, number> = {};
    for (const s of graph?.steps ?? []) {
      const rows = badgeRows({
        problems: problems.get(s.id)?.length ?? 0,
        run: runSteps.get(s.id) ?? null,
        quality: runQuality.get(s.id) ?? null,
        predicted: predicted.get(s.id) ?? null,
      });
      out[s.id] = Math.max(nodeHeight(portsOf(s, catalogue), rows), drawnHeights[s.id] ?? 0);
    }
    return out;
  }, [graph, catalogue, problems, runSteps, runQuality, predicted, drawnHeights]);
  const edgeLabels = useMemo(() => {
    const out = new Map<string, string>();
    if (!graph) return out;
    const rows = new Map(run?.steps.map((s) => [s.step, s]) ?? []);
    const live = run && run.steps.length === 0 ? liveEdgeRows(run.progress) : null;
    for (const e of graph.edges) {
      const plain = e.fromPort === "out" && e.toPort === "in";
      const counted = live
        ? (live.get(`${e.from}.${e.fromPort}>${e.to}.${e.toPort}`) ?? null)
        : (rows.get(e.from)?.ports?.out?.[e.fromPort] ?? (e.fromPort === "out" ? rows.get(e.from)?.rowsOut : null));
      const parts = [plain ? "" : e.toPort === "in" || e.toPort === "main" ? e.fromPort : `${e.fromPort} → ${e.toPort}`];
      if (run && counted != null) parts.push(`${formatNumber(counted)} rows`);
      out.set(edgeId(e), parts.filter(Boolean).join(" · "));
    }
    return out;
  }, [graph, run]);
  const sizes = useMemo<LayoutSizes>(() => {
    const labels: EdgeLabel[] = (graph?.edges ?? []).map((e) => ({ from: e.from, to: e.to, text: edgeLabels.get(edgeId(e)) ?? "" }));
    return { heights, labels };
  }, [graph, heights, edgeLabels]);
  // Where each step is placed: its saved position (what the YAML's layout says), else laid out.
  const positions = useMemo(() => (graph ? placed(graph, catalogue, sizes) : {}), [graph, catalogue, sizes]);
  // Where it is shown: moved apart in the view only where its badges or a connection's label would
  // not fit between saved positions (a layout saved before a run). The YAML is not changed until a
  // step is moved or Auto-layout is chosen.
  const shown = useMemo(() => makeRoom(positions, heights, sizes.labels), [positions, heights, sizes]);
  // Choosing a step shows its settings in place of the suggestions.
  const lastSelected = useRef(selected);
  useEffect(() => {
    if (selected && selected !== lastSelected.current) setSuggestOpen(false);
    lastSelected.current = selected;
  }, [selected]);

  const say = useCallback((words: string) => {
    // Cleared first, so the same words twice are read out twice.
    setAnnounce("");
    window.setTimeout(() => setAnnounce(words), 30);
  }, []);

  /** The part of the canvas not under the step list or the settings drawer, in canvas pixels. */
  const visibleArea = useCallback(() => {
    const box = flowBox.current?.getBoundingClientRect();
    if (!box) return null;
    const stage = flowBox.current!.parentElement!;
    const list = stage.querySelector<HTMLElement>(".cv-palette-panel, .cv-flyout")?.getBoundingClientRect();
    const drawer = stage.querySelector<HTMLElement>(".cv-drawer")?.getBoundingClientRect();
    const left = list && list.right > box.left ? list.right - box.left : 0;
    const right = drawer && drawer.left < box.right && getComputedStyle(stage.querySelector(".cv-drawer")!).position === "absolute" ? box.right - drawer.left : 0;
    return { left, top: 0, width: Math.max(120, box.width - left - right), height: box.height };
  }, []);

  /** Focuses a step, moving the view to it when it is outside. */
  const showNode = useCallback(
    (id: string): boolean => {
      const el = flowBox.current?.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(id)}"]`);
      if (!el) return false;
      el.focus({ preventScroll: true });
      const node = flow.getNode(id);
      const box = flowBox.current?.getBoundingClientRect();
      const area = visibleArea();
      const r = el.getBoundingClientRect();
      if (node && box && area) {
        const l = box.left + area.left;
        const out = r.left < l || r.right > l + area.width || r.top < box.top || r.bottom > box.bottom;
        if (out) {
          // Centred in the part of the canvas not under the step list or the drawer.
          const z = flow.getZoom();
          const cx = area.left + area.width / 2;
          const cy = area.height / 2;
          void flow.setViewport({ x: cx - (node.position.x + 106) * z, y: cy - (node.position.y + 60) * z, zoom: z }, { duration });
        }
      }
      return true;
    },
    [flow, duration, visibleArea],
  );
  const focusNode = useCallback(
    (id: string) => {
      if (!showNode(id)) pendingFocus.current = id;
    },
    [showNode],
  );

  // A step added or renamed is focused, and brought into view, once React Flow has drawn it.
  useEffect(() => {
    const id = pendingFocus.current;
    if (!id) return;
    const t = window.setTimeout(() => {
      if (showNode(id)) pendingFocus.current = null;
    }, 60);
    return () => window.clearTimeout(t);
  });

  /** Applies a change, or says why it could not be made. Structural changes keep every step where it is drawn. */
  const apply = useCallback(
    (c: Change, label: string, opts: { pin?: Record<string, Point> | true; coalesce?: string } = {}): string | null => {
      if ("error" in c) {
        setRefusal(c.error);
        say(c.error);
        return null;
      }
      let text = c.text;
      if (opts.pin) {
        const extra = opts.pin === true ? {} : opts.pin;
        const pinned = setPositions(text, { ...positions, ...extra });
        if (!("error" in pinned)) text = pinned.text;
      }
      setRefusal(null);
      yamlRef.current = text;
      onEdit(text, label, opts.coalesce);
      say(label);
      return c.id ?? null;
    },
    [onEdit, positions, say],
  );

  // Typing in a setting is written to the YAML a moment after it stops (or when anything else changes).
  const pending = useRef(new Map<string, { id: string; key: string; value: Value | undefined; kind: string }>());
  const pendingTimer = useRef<number | undefined>(undefined);
  const flush = useCallback(() => {
    window.clearTimeout(pendingTimer.current);
    if (pending.current.size === 0) return;
    let text = yamlRef.current;
    let last: { id: string; key: string } | null = null;
    for (const w of pending.current.values()) {
      const c = setSetting(text, w.id, w.key, w.value, w.kind);
      if ("error" in c) {
        setRefusal(c.error);
        continue;
      }
      text = c.text;
      last = w;
    }
    pending.current.clear();
    if (last && text !== yamlRef.current) {
      apply({ text }, `Changed ${last.key} of ${last.id}.`, { coalesce: `${last.id}:${last.key}` });
    }
  }, [apply]);
  const flushRef = useRef(flush);
  flushRef.current = flush;
  useEffect(() => () => flushRef.current(), []);

  // ---- steps ----

  const add = (type: string, at?: Point) => {
    if (readOnly || !graph) return;
    flush();
    rememberUsed(type);
    const sel = !at && selected ? graph.steps.find((s) => s.id === selected) : undefined;
    const outs = sel ? portsOf(sel, catalogue).outputs : [];
    const t = catalogue.get(type);
    const after = sel && outs.length > 0 && t && t.inputs.length > 0 ? { step: sel.id, port: outs.includes("out") ? "out" : outs[0] } : null;
    const spot = at ?? spotAfter(shown, sel?.id ?? null);
    const c = addStep(yamlRef.current, type, catalogue, after, spot);
    const id = "id" in c ? c.id! : null;
    const label = id
      ? `Added ${t?.label ?? type} step ${id}${after ? `, reading from ${refText(after.step, after.port)}` : ""}.`
      : "";
    const made = apply(c, label, { pin: id ? { [id]: spot } : true });
    if (made) {
      onSelect(made);
      pendingFocus.current = made;
    }
  };

  const remove = (id: string) => {
    if (readOnly || !graph) return;
    flush();
    const order = graph.steps.map((s) => s.id);
    const next = order[order.indexOf(id) + 1] ?? order[order.indexOf(id) - 1] ?? null;
    const c = removeStep(yamlRef.current, id, catalogue);
    apply(c, `Removed step ${id} and its connections.`, { pin: true });
    if (!("error" in c)) {
      onSelect(next);
      if (next) pendingFocus.current = next;
      else flowBox.current?.focus();
    }
  };

  const duplicate = (id: string) => {
    if (readOnly) return;
    flush();
    const at = positions[id];
    const c = duplicateStep(yamlRef.current, id);
    const newId = "id" in c ? c.id! : null;
    const spot = at ? { x: at.x + 40, y: at.y + 140 } : undefined;
    const made = apply(c, newId ? `Duplicated ${id} as ${newId}.` : "", { pin: newId && spot ? { [newId]: spot } : true });
    if (made) {
      onSelect(made);
      pendingFocus.current = made;
    }
  };

  const rename = (id: string, to: string): string | null => {
    flush();
    const c = renameStep(yamlRef.current, id, to);
    if ("error" in c) return c.error;
    const moved = { ...positions, [to]: positions[id] };
    delete moved[id];
    const pinned = setPositions(c.text, moved);
    yamlRef.current = "error" in pinned ? c.text : pinned.text;
    onEdit(yamlRef.current, `Renamed step ${id} to ${to}.`);
    say(`Renamed step ${id} to ${to}.`);
    onSelect(to);
    return null;
  };

  const link = (e: GraphEdge) => {
    flush();
    return apply(connect(yamlRef.current, e, catalogue), `Connected ${edgeWords(e)}.`, { pin: true });
  };
  const unlink = (e: GraphEdge) => {
    flush();
    apply(disconnect(yamlRef.current, e, catalogue), `Disconnected ${edgeWords(e)}.`, { pin: true });
    setSelectedEdge(null);
  };

  const setOne = (id: string, key: string, value: Value | undefined, kind: string) => {
    pending.current.set(`${id}:${key}`, { id, key, value, kind });
    window.clearTimeout(pendingTimer.current);
    if (TYPED.has(kind)) pendingTimer.current = window.setTimeout(flush, 300);
    else flush();
  };

  /** Adds a suggestion's steps between the step rows come from and the write, as one change. */
  const addSuggestion = (sg: Suggestion): string | null => {
    if (readOnly || !graph) return "The canvas cannot change this pipeline now.";
    flush();
    const from = positions[sg.placement.from.split(".")[0]];
    const to = positions[sg.placement.before];
    const base = from && to ? { x: (from.x + to.x) / 2, y: Math.max(from.y, to.y) + 170 } : spotAfter(shown, null);
    const spots = Object.fromEntries(sg.steps.map((n, i) => [n.id, { x: base.x + i * 250, y: base.y }]));
    const c = insertSteps(yamlRef.current, catalogue, sg.steps, sg.placement);
    if ("error" in c) return c.error;
    // The list stays open (it is worked out again for the changed pipeline); the change is announced.
    apply(c, `Added ${sg.steps.map((n) => n.id).join(" and ")} before ${sg.placement.before}: ${sg.title}.`, { pin: spots });
    return null;
  };

  const layOut = () => {
    if (readOnly || !graph) return;
    flush();
    apply(setPositions(yamlRef.current, autoLayout(graph, catalogue, sizes)), "Laid the steps out again, left to right.");
    window.setTimeout(() => readable(), 50);
  };

  // ---- React Flow's view of the graph ----

  const derived = useMemo<StepNodeType[]>(() => {
    if (!graph) return [];
    return graph.steps.map((s) => {
      const t = s.type ? catalogue.get(s.type) : undefined;
      const ports = portsOf(s, catalogue);
      const outline = check?.steps.find((o) => o.id === s.id);
      const n = problems.get(s.id)?.length ?? 0;
      const runInfo = runSteps.get(s.id) ?? null;
      const quality = runQuality.get(s.id) ?? null;
      const qualityText = stepQualityWords(quality);
      const estimate = predicted.get(s.id) ?? null;
      const summary = stepSummary(s);
      const columns = outline ? columnsText(outline) : "Columns known once the pipeline has been checked";
      return {
        id: s.id,
        type: "step",
        position: shown[s.id] ?? { x: 0, y: 0 },
        selected: s.id === selected,
        data: {
          step: s,
          kind: t?.kind ?? null,
          label: t?.label ?? s.type ?? "no type",
          summary,
          inputs: ports.inputs,
          outputs: ports.outputs,
          problems: n,
          columns,
          run: runInfo,
          quality,
          predicted: estimate,
          connectable: !readOnly,
        },
        ariaLabel: [
          `Step ${s.id}, ${kindWord(t?.kind)}: ${stepLabel(s, t?.label ?? s.type ?? "no type")}`,
          summary,
          s.inputs.length ? `reads from ${s.inputs.map((l) => refText(l.from, l.fromPort)).join(" and ")}` : "",
          n ? `${n} ${n === 1 ? "problem" : "problems"}` : "",
          runInfo ? `${runInfo.live ? "running now" : "last run"}: ${runWords(runInfo)}` : "",
          qualityText ?? "",
          predictedWords(estimate) ?? "",
          estimate?.unknown.length
            ? `${estimate.unknown.length} ${estimate.unknown.length === 1 ? "thing" : "things"} the estimate could not tell, listed in its settings`
            : "",
        ]
          .filter(Boolean)
          .join(". "),
        // The columns leaving it, and the keys, are its description rather than its name.
        domAttributes: { "aria-describedby": `cv-cols-${s.id} cv-node-help` },
      };
    });
  }, [graph, catalogue, check, problems, runSteps, runQuality, predicted, shown, selected, readOnly]);

  const [nodes, setNodes] = useState<StepNodeType[]>(derived);
  useEffect(() => {
    setNodes((prev) =>
      derived.map((n) => {
        const old = prev.find((p) => p.id === n.id);
        return old?.measured ? { ...n, measured: old.measured } : n;
      }),
    );
  }, [derived]);

  const onNodesChange = useCallback((changes: NodeChange<StepNodeType>[]) => {
    // Selection is the page's; React Flow's own select and remove changes are not applied.
    setNodes((ns) => applyNodeChanges(changes.filter((c) => c.type !== "select" && c.type !== "remove"), ns));
    // Each step's drawn height, for making room (only when it changes by more than a pixel).
    const drawn = changes.flatMap((c) => (c.type === "dimensions" && c.dimensions ? [[c.id, Math.ceil(c.dimensions.height)] as const] : []));
    if (drawn.length)
      setDrawnHeights((old) => (drawn.some(([id, h]) => Math.abs((old[id] ?? 0) - h) > 1) ? { ...old, ...Object.fromEntries(drawn) } : old));
  }, []);

  const edges = useMemo<Edge[]>(() => {
    if (!graph) return [];
    const ids = new Set(graph.steps.map((s) => s.id));
    return graph.edges
      .filter((e) => ids.has(e.from) && ids.has(e.to))
      .map((e) => {
        const id = edgeId(e);
        const label = edgeLabels.get(id) ?? "";
        return {
          id,
          source: e.from,
          target: e.to,
          sourceHandle: `out:${e.fromPort}`,
          targetHandle: `in:${e.toPort}`,
          type: "smoothstep",
          label: label || undefined,
          labelBgPadding: [6, 3] as [number, number],
          labelBgBorderRadius: 4,
          selected: id === selectedEdge,
          className: `cv-edge${e.fromPort === "out" ? "" : " cv-edge-other"}${id === selectedEdge ? " is-selected" : ""}`,
          markerEnd: { type: MarkerType.ArrowClosed, color: "var(--km-ink-soft)" },
          ariaLabel: `Connection from ${edgeWords(e)}${label ? `, ${label}` : ""}`,
          data: e,
        };
      });
  }, [graph, edgeLabels, selectedEdge]);

  /** Shows the nodes' bounds in the visible area at `zoom` (at most 1), centred. */
  const frame = useCallback(
    (fitInside: boolean, animate: boolean) => {
      const area = visibleArea();
      const all = flow.getNodes();
      if (!area || all.length === 0) return;
      const b = getNodesBounds(all);
      const pad = 28;
      const fits = Math.min(1, (area.width - 2 * pad) / Math.max(b.width, 1), (area.height - 2 * pad) / Math.max(b.height, 1));
      const opts = { duration: animate ? duration : 0 };
      if (fitInside || fits >= MIN_READABLE) {
        const z = Math.max(0.1, fits);
        void flow.setViewport(
          { x: area.left + (area.width - b.width * z) / 2 - b.x * z, y: area.top + (area.height - b.height * z) / 2 - b.y * z, zoom: z },
          opts,
        );
        return;
      }
      // Too big to read whole: its first steps at the smallest readable size, centred on them.
      const z = MIN_READABLE;
      const first = all.filter((n) => n.position.x < b.x + (area.width - 2 * pad) / z);
      const fb = getNodesBounds(first.length ? first : all);
      const y = fb.height * z < area.height - 2 * pad ? (area.height - fb.height * z) / 2 - fb.y * z : pad - fb.y * z;
      void flow.setViewport({ x: area.left + pad - b.x * z, y, zoom: z }, opts);
    },
    [flow, duration, visibleArea],
  );
  /** Fits the whole pipeline into the visible part of the canvas, however small that makes it. */
  const fitAll = useCallback((animate = true) => frame(true, animate), [frame]);
  /** The pipeline at a size its words can be read: all of it if it fits, else its first steps. */
  const readable = useCallback((animate = true) => frame(false, animate), [frame]);

  // Fit the pipeline into view when it first appears.
  const fitted = useRef(false);
  useEffect(() => {
    if (fitted.current || nodes.length === 0 || !nodes.every((n) => n.measured)) return;
    fitted.current = true;
    readable(false);
  }, [nodes, readable]);

  // A problem chosen in the problems list: select its step and go to its setting.
  useEffect(() => {
    const r = props.focusRequest;
    if (!r) return;
    onSelect(r.step);
    window.setTimeout(() => {
      const panel = wrap.current?.querySelector<HTMLElement>(".cv-drawer");
      const field = r.key ? panel?.querySelector<HTMLElement>(`[data-setting="${CSS.escape(r.key)}"]`) : null;
      const target = field?.querySelector<HTMLElement>("input, textarea, select, button") ?? null;
      if (target) {
        target.focus();
        field?.scrollIntoView({ block: "nearest" });
      } else {
        focusNode(r.step);
      }
    }, 80);
    // Only a new request moves the focus.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.focusRequest?.n]);

  // ---- mouse ----

  const onConnect = (c: Connection) => {
    const e = fromHandles(c);
    if (e && !readOnly) link(e);
  };

  const isValidConnection = (c: Connection | Edge) => {
    const e = fromHandles(c);
    return !!e && !!graph && connectProblem(graph, e, catalogue) == null;
  };

  /** A connection dropped on a port that refused it: say why. */
  const onConnectEnd = (ev: MouseEvent | TouchEvent, state: FinalConnectionState) => {
    if (state.isValid || !state.fromHandle || !graph) return;
    const point = "changedTouches" in ev ? ev.changedTouches[0] : ev;
    const el = point ? document.elementFromPoint(point.clientX, point.clientY)?.closest<HTMLElement>(".react-flow__handle") : null;
    const toNode = state.toHandle?.nodeId ?? el?.dataset.nodeid ?? null;
    const toId = state.toHandle?.id ?? el?.dataset.handleid ?? null;
    if (!toNode || !toId) return;
    const a = { nodeId: state.fromHandle.nodeId, id: state.fromHandle.id ?? "" };
    const b = { nodeId: toNode, id: toId };
    const aOut = a.id.startsWith("out:");
    const bOut = b.id.startsWith("out:");
    if (aOut === bOut) {
      const why = "Connect an output port (on the right of a step) to an input port (on the left of another).";
      setRefusal(why);
      say(why);
      return;
    }
    const [src, dst] = aOut ? [a, b] : [b, a];
    const e = fromHandles({ source: src.nodeId, sourceHandle: src.id, target: dst.nodeId, targetHandle: dst.id });
    const why = e ? connectProblem(graph, e, catalogue) : null;
    if (why) {
      setRefusal(why);
      say(why);
    }
  };

  const onDrop = (ev: DragEvent) => {
    const type = ev.dataTransfer.getData("application/x-kilnmark-step");
    if (!type) return;
    ev.preventDefault();
    const p = flow.screenToFlowPosition({ x: ev.clientX, y: ev.clientY });
    add(type, { x: p.x - 106, y: p.y - 40 });
  };

  // ---- keyboard ----

  const neighbour = (id: string, key: string): string | null => {
    if (!graph) return null;
    const here = shown[id];
    if (key === "ArrowRight") return graph.edges.find((e) => e.from === id)?.to ?? null;
    if (key === "ArrowLeft") return graph.edges.find((e) => e.to === id)?.from ?? null;
    // Up and down: the nearest step above or below, preferring the same column.
    const down = key === "ArrowDown";
    let best: string | null = null;
    let score = Infinity;
    for (const s of graph.steps) {
      const p = shown[s.id];
      if (s.id === id || !p || !here) continue;
      const dy = p.y - here.y;
      if (down ? dy <= 0 : dy >= 0) continue;
      const d = Math.abs(dy) + Math.abs(p.x - here.x) * 2;
      if (d < score) {
        score = d;
        best = s.id;
      }
    }
    return best;
  };

  /** After an undo or redo: the focus goes back to the selected step, else to the canvas. */
  const restoreFocus = useCallback(() => {
    window.setTimeout(() => {
      const a = document.activeElement as HTMLElement | null;
      const lost = !a || a === document.body || !a.isConnected || (a as HTMLButtonElement).disabled;
      if (!lost) return;
      const id = selectedRef.current;
      if (id && flowBox.current?.querySelector(`.react-flow__node[data-id="${CSS.escape(id)}"]`)) focusNode(id);
      else flowBox.current?.focus();
    }, 80);
  }, [focusNode]);

  const undo = () => {
    if (readOnly) {
      say("The canvas cannot change this pipeline now, so there is nothing to undo here.");
      return;
    }
    flush();
    const label = props.onUndo();
    say(label ? `Undid: ${label}` : "Nothing to undo.");
    restoreFocus();
  };
  const redo = () => {
    if (readOnly) return;
    flush();
    const label = props.onRedo();
    say(label ? `Redid: ${label}` : "Nothing to redo.");
    restoreFocus();
  };

  const onKeyDown = (ev: KeyboardEvent<HTMLDivElement>) => {
    if (isTyping(ev.target)) return;
    // Shortcuts belong to the canvas and its tools, not to the panel, the step list or a dialog.
    const t = ev.target as Node;
    const onCanvas = !!(flowBox.current?.contains(t) || toolbar.current?.contains(t));
    const mod = ev.ctrlKey || ev.metaKey;
    const nodeEl = (ev.target as HTMLElement).closest?.(".react-flow__node") as HTMLElement | null;
    const id = nodeEl?.dataset.id ?? null;
    const k = ev.key;
    const done = () => {
      ev.preventDefault();
      ev.stopPropagation();
    };
    if (mod && (k === "z" || k === "Z") && !ev.shiftKey) {
      done();
      undo();
      return;
    }
    if (mod && (((k === "z" || k === "Z") && ev.shiftKey) || k === "y")) {
      done();
      redo();
      return;
    }
    if (!onCanvas) return;
    if (k === "?") {
      done();
      keysDialog.current?.showModal();
      return;
    }
    if (ev.altKey && k.startsWith("Arrow")) {
      done();
      const v = flow.getViewport();
      const d = 80;
      void flow.setViewport(
        { x: v.x + (k === "ArrowLeft" ? d : k === "ArrowRight" ? -d : 0), y: v.y + (k === "ArrowUp" ? d : k === "ArrowDown" ? -d : 0), zoom: v.zoom },
        { duration },
      );
      return;
    }
    if (k === "+" || k === "=") {
      done();
      void flow.zoomIn({ duration });
      return;
    }
    if (k === "-") {
      done();
      void flow.zoomOut({ duration });
      return;
    }
    if ((k === "f" || k === "F") && !mod) {
      done();
      fitAll();
      return;
    }
    if ((k === "r" || k === "R") && !mod) {
      done();
      readable();
      return;
    }
    if ((k === "l" || k === "L") && !mod) {
      done();
      layOut();
      return;
    }
    if ((k === "a" || k === "A") && !mod && !readOnly) {
      done();
      // Drawn at once, so the keys typed next land in the search.
      flushSync(() => setPaletteOpen(true));
      searchBox.current?.focus();
      searchBox.current?.select();
      return;
    }
    if (k === "Escape") {
      if (selectedEdge) setSelectedEdge(null);
      else onSelect(null);
      return;
    }
    if ((k === "Delete" || k === "Backspace") && !readOnly) {
      if (selectedEdge) {
        done();
        const e = edges.find((x) => x.id === selectedEdge)?.data as GraphEdge | undefined;
        if (e) unlink(e);
        return;
      }
      if (id) {
        done();
        remove(id);
      }
      return;
    }
    if (!id) return;
    if (mod && (k === "d" || k === "D")) {
      done();
      duplicate(id);
      return;
    }
    if ((k === "c" || k === "C") && !mod && !readOnly) {
      done();
      openConnect(id);
      return;
    }
    if (k === "Enter" || k === " ") {
      done();
      onSelect(id);
      window.setTimeout(() => wrap.current?.querySelector<HTMLElement>(".cv-drawer input, .cv-drawer select, .cv-drawer textarea")?.focus(), 30);
      return;
    }
    if (k.startsWith("Arrow")) {
      done();
      if (ev.shiftKey && !readOnly) {
        // From where it is shown, so it moves from where it is seen.
        const p = shown[id];
        if (!p) return;
        const step = 20;
        const to = { x: p.x + (k === "ArrowRight" ? step : k === "ArrowLeft" ? -step : 0), y: p.y + (k === "ArrowDown" ? step : k === "ArrowUp" ? -step : 0) };
        apply(setPositions(yamlRef.current, { ...positions, [id]: to }), `Moved ${id} to ${Math.round(to.x)}, ${Math.round(to.y)}.`, { coalesce: `move:${id}` });
        return;
      }
      const next = neighbour(id, k);
      if (next) {
        onSelect(next);
        focusNode(next);
      } else {
        say(k === "ArrowRight" ? `Nothing reads from ${id}.` : k === "ArrowLeft" ? `${id} reads from no step.` : `No step ${k === "ArrowUp" ? "above" : "below"} ${id}.`);
      }
    }
  };

  const openConnect = (id: string) => {
    setConnectFor(id);
    window.setTimeout(() => connectDialog.current?.showModal(), 0);
  };

  const selectedStep = graph?.steps.find((s) => s.id === selected) ?? null;
  const offers = connectFor && graph ? possibleConnections(graph, connectFor, catalogue) : [];

  return (
    <div className="cv-wrap" ref={wrap} onKeyDown={onKeyDown}>
      {broken && (
        <p className="warning cv-banner" role="status">
          The YAML cannot be read{!read.ok && read.line > 0 ? ` (line ${read.line}: ${read.error})` : !read.ok ? ` (${read.error})` : ""}. The canvas
          shows the last pipeline that could be read, and cannot be changed until the YAML is fixed in the YAML tab.
        </p>
      )}
      {typesError && (
        <p className="error" role="alert">
          Could not load the step types: {typesError}
        </p>
      )}
      <div className="cv-toolbar" ref={toolbar} role="group" aria-label="Canvas tools">
        <button type="button" className="cv-tool" onClick={undo} disabled={readOnly || !props.canUndo}>
          <IconArrowBackUp size={18} stroke={1.8} aria-hidden="true" />
          Undo
        </button>
        <button type="button" className="cv-tool" onClick={redo} disabled={readOnly || !props.canRedo}>
          <IconArrowForwardUp size={18} stroke={1.8} aria-hidden="true" />
          Redo
        </button>
        <span className="cv-tool-sep" aria-hidden="true" />
        <button type="button" className="cv-tool" onClick={() => void flow.zoomIn({ duration })} aria-label="Zoom in">
          <IconZoomIn size={18} stroke={1.8} aria-hidden="true" />
        </button>
        <button type="button" className="cv-tool" onClick={() => void flow.zoomOut({ duration })} aria-label="Zoom out">
          <IconZoomOut size={18} stroke={1.8} aria-hidden="true" />
        </button>
        <button type="button" className="cv-tool" onClick={() => fitAll()}>
          <IconFocus2 size={18} stroke={1.8} aria-hidden="true" />
          Fit
        </button>
        <button type="button" className="cv-tool" onClick={() => readable()}>
          <IconTextSize size={18} stroke={1.8} aria-hidden="true" />
          Readable size
        </button>
        <button type="button" className="cv-tool" onClick={layOut} disabled={readOnly || !graph?.steps.length}>
          <IconLayoutDistributeHorizontal size={18} stroke={1.8} aria-hidden="true" />
          Auto-layout
        </button>
        <span className="cv-tool-sep" aria-hidden="true" />
        <button type="button" className="cv-tool" onClick={() => selected && openConnect(selected)} disabled={readOnly || !selected}>
          <IconPlug size={18} stroke={1.8} aria-hidden="true" />
          Connect to…
        </button>
        <button type="button" className="cv-tool" onClick={() => keysDialog.current?.showModal()}>
          <IconKeyboard size={18} stroke={1.8} aria-hidden="true" />
          Shortcuts
        </button>
        <button
          type="button"
          className={`cv-tool${suggestOpen ? " is-on" : ""}`}
          aria-pressed={suggestOpen}
          onClick={() => {
            flush();
            setSuggestOpen((o) => !o);
          }}
          disabled={!graph?.steps.length}
        >
          <IconSparkles size={18} stroke={1.8} aria-hidden="true" />
          Suggestions
        </button>
        {run && (
          <span className="small cv-run-legend">
            Showing run {run.id} (version {run.version}, {run.status}).
          </span>
        )}
      </div>

      <div className={`cv-stage${selectedStep || suggestOpen ? " has-drawer" : ""}`}>
        <Palette
          types={types}
          usedTypes={graph?.steps.map((s) => s.type ?? "") ?? []}
          readOnly={readOnly}
          expanded={paletteOpen}
          onExpand={setPaletteOpen}
          onAdd={(type) => add(type)}
          searchRef={searchBox}
          hint={
            readOnly
              ? lockedByPage
                ? "This pipeline is read-only here."
                : "Steps can be added once the YAML can be read."
              : selected
                ? `Choose a step to add it after ${selected}, or drag it onto the canvas.`
                : "Choose a step to add it, or drag it onto the canvas."
          }
        />
        <div
          className="cv-flow dg-frame"
          ref={flowBox}
          tabIndex={-1}
          onDragOver={(e) => {
            if (e.dataTransfer.types.includes("application/x-kilnmark-step")) {
              e.preventDefault();
              e.dataTransfer.dropEffect = "copy";
            }
          }}
          onDrop={onDrop}
          onFocus={(e) => {
            const el = (e.target as HTMLElement).closest?.(".react-flow__node") as HTMLElement | null;
            const id = el?.dataset.id;
            if (id && quietFocus.current === id) {
              quietFocus.current = null;
              return;
            }
            if (id && id !== selected) onSelect(id);
          }}
          role="region"
          aria-label={`Canvas of ${props.title}: ${graph?.steps.length ?? 0} steps. Press question mark for keyboard shortcuts.`}
        >
          <ReactFlow<StepNodeType, Edge>
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onNodeClick={(_, n) => {
              setSelectedEdge(null);
              onSelect(n.id);
            }}
            onEdgeClick={(_, e) => {
              setSelectedEdge(e.id);
              say(`Selected the connection from ${edgeWords(e.data as GraphEdge)}. Press Delete to remove it.`);
            }}
            onPaneClick={() => {
              setSelectedEdge(null);
              onSelect(null);
            }}
            onNodeDragStop={(_, __, moved) => {
              if (readOnly) return;
              flush();
              const next = { ...positions };
              for (const n of moved) next[n.id] = n.position;
              apply(setPositions(yamlRef.current, next), moved.length === 1 ? `Moved ${moved[0].id}.` : `Moved ${moved.length} steps.`);
            }}
            onConnect={onConnect}
            onConnectEnd={onConnectEnd}
            isValidConnection={isValidConnection}
            nodesDraggable={!readOnly}
            nodesConnectable={!readOnly}
            nodesFocusable
            edgesFocusable={false}
            elementsSelectable
            disableKeyboardA11y
            deleteKeyCode={null}
            selectionKeyCode={null}
            multiSelectionKeyCode={null}
            minZoom={0.1}
            maxZoom={2}
            colorMode={scheme}
            proOptions={{ hideAttribution: false }}
          >
            <Background gap={20} size={1} />
            <MiniMap
              pannable
              zoomable
              position="bottom-right"
              ariaLabel="Overview of the pipeline: drag to move the view"
              nodeBorderRadius={6}
              nodeStrokeWidth={2}
              nodeClassName={(n) => `cv-mini-${(n.data as { kind?: string | null }).kind ?? "unknown"}`}
            />
          </ReactFlow>
          <p id="cv-node-help" className="visually-hidden">
            Arrow keys move to connected steps; Shift and an arrow key moves the step; C connects it; Delete removes
            it; Enter goes to its settings. Question mark lists every shortcut.
          </p>
          {graph && graph.steps.length === 0 && (
            <div className="cv-empty">
              <p>
                <strong>An empty canvas.</strong> Add a step from the list: start with a source, such as{" "}
                {catalogue.get("read")?.label ?? "a read"}.
              </p>
            </div>
          )}
        </div>

        {suggestOpen && (
          <Suspense fallback={<aside className="cv-drawer cv-suggest" aria-busy="true"><p className="small">Loading suggestions…</p></aside>}>
            <SuggestionsPanel yaml={yaml} readOnly={readOnly} onAdd={addSuggestion} onClose={() => setSuggestOpen(false)} />
          </Suspense>
        )}
        {selectedStep && !suggestOpen && (
          <aside
            className="cv-drawer"
            aria-labelledby="cv-panel-h"
            onBlur={(e) => {
              if (!e.currentTarget.contains(e.relatedTarget as Node | null)) flush();
            }}
          >
            <div className="cv-drawer-head">
              <h3 id="cv-panel-h" className="cv-side-title">
                Selected step
              </h3>
              <button
                type="button"
                className="cv-mini"
                aria-label="Close the settings"
                onClick={() => {
                  const id = selectedStep.id;
                  flush();
                  onSelect(null);
                  // Back to the step, without selecting it again (which would reopen the drawer).
                  quietFocus.current = id;
                  focusNode(id);
                }}
              >
                <IconX size={16} stroke={2} aria-hidden="true" />
              </button>
            </div>
            <SettingsPanel
              key={selectedStep.id}
              step={selectedStep}
              type={selectedStep.type ? catalogue.get(selectedStep.type) : undefined}
              catalogue={catalogue}
              check={check}
              problems={problems.get(selectedStep.id) ?? []}
              sources={sources}
              readOnly={readOnly}
              run={runSteps.get(selectedStep.id) ?? null}
              quality={runQuality.get(selectedStep.id) ?? null}
              predicted={predicted.get(selectedStep.id) ?? null}
              onSetting={(key, value, kind) => setOne(selectedStep.id, key, value, kind)}
              onRename={(to) => rename(selectedStep.id, to)}
              onDisconnect={unlink}
              onConnectMenu={() => openConnect(selectedStep.id)}
              onDelete={() => remove(selectedStep.id)}
              onDuplicate={() => duplicate(selectedStep.id)}
            />
          </aside>
        )}
      </div>
      <p className="visually-hidden" aria-live="polite" aria-atomic="true">
        {announce}
      </p>
      {refusal && (
        <p className="error cv-refusal" role="alert">
          {refusal}{" "}
          <button type="button" className="link-button" onClick={() => setRefusal(null)}>
            Dismiss
          </button>
        </p>
      )}
      <p className="hint cv-hint">
        Drag from an output port (right) to an input port (left) to connect steps, or use Connect to…. Press ? for keyboard
        shortcuts.
      </p>

      <dialog
        ref={connectDialog}
        className="cv-dialog"
        aria-labelledby="cv-connect-h"
        onClose={() => {
          const id = connectFor;
          setConnectFor(null);
          if (id) focusNode(id);
        }}
      >
        <h3 id="cv-connect-h">Connect {connectFor}</h3>
        {offers.length === 0 ? (
          <p>There is nothing {connectFor} can connect to: every input it could feed or read from is already connected.</p>
        ) : (
          <>
            {(["out", "in"] as const).map((dir) => {
              const list = offers.filter((e) => (dir === "out" ? e.from === connectFor : e.to === connectFor));
              if (list.length === 0) return null;
              return (
                <div key={dir} className="cv-offer-group">
                  <h4>{dir === "out" ? `Send rows from ${connectFor} to` : `Read rows into ${connectFor} from`}</h4>
                  <ul className="cv-offers">
                    {list.map((e) => (
                      <li key={edgeId(e)}>
                        <button
                          type="button"
                          onClick={() => {
                            link(e);
                            connectDialog.current?.close();
                          }}
                        >
                          {dir === "out" ? (
                            <>
                              <span className="mono">{e.to}</span>, input {e.toPort}
                              {e.fromPort !== "out" && <> (from {e.fromPort})</>}
                            </>
                          ) : (
                            <>
                              <span className="mono">{e.from}</span>, output {e.fromPort}
                              {e.toPort !== "in" && <> (into {e.toPort})</>}
                            </>
                          )}
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })}
          </>
        )}
        <form method="dialog" className="buttons">
          <button type="submit">Close</button>
        </form>
      </dialog>

      <dialog ref={keysDialog} className="cv-dialog" aria-labelledby="cv-keys-h">
        <h3 id="cv-keys-h">Keyboard shortcuts on the canvas</h3>
        <table className="small">
          <thead>
            <tr>
              <th scope="col">Keys</th>
              <th scope="col">What they do</th>
            </tr>
          </thead>
          <tbody>
            {SHORTCUTS.map(([keys, what]) => (
              <tr key={keys}>
                <th scope="row">
                  <kbd>{keys}</kbd>
                </th>
                <td>{what}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="small muted">On a Mac, use Cmd for Ctrl.</p>
        <form method="dialog" className="buttons">
          <button type="submit">Close</button>
        </form>
      </dialog>
    </div>
  );
}

/** The pipeline canvas: palette, graph and settings, editing the same YAML as the YAML editor. */
export default function CanvasView(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  );
}
