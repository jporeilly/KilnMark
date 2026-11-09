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
import { createPortal, flushSync } from "react-dom";
import {
  IconArrowBackUp,
  IconArrowForwardUp,
  IconFocus2,
  IconKeyboard,
  IconLayoutDistributeHorizontal,
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconPlug,
  IconSparkles,
  IconTextSize,
  IconZoomIn,
  IconZoomOut,
} from "@tabler/icons-react";
import {
  allows,
  api,
  pipelinesApi,
  type PipelineRunProgress,
  type PipelineNodeQuality,
  type PipelineNodeRun,
  type PipelineValidation,
  type Source,
  type NodeType,
  plural,
} from "../../api";
import { useColourScheme } from "../../theme/appearance";
import { columnsText } from "../outline";
import { nodeQualityWords } from "../quality";
import { predictedWords, predictionApi, predictionsByNode, type Prediction, type Suggestion } from "../prediction";
import { liveEdgeRows, liveNodes } from "../live";
import { kindWord, problemsByNode, nodeLabel, nodeSummary } from "./insight";
import { autoLayout, makeRoom, nodeHeight, placed, spotAfter, type EdgeLabel, type LayoutSizes } from "./layout";
import { FIT_PAD, fitBesideMap, fitZoom, type Box } from "./fit";
import {
  addNode,
  catalogueOf,
  connect,
  connectProblem,
  disconnect,
  duplicateNode,
  insertNodes,
  portsOf,
  possibleConnections,
  readGraph,
  refText,
  removeNode,
  renameNode,
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
import { NodeMenu, type NodeMenuItem } from "./NodeMenu";
import { NodePreviewPanel, type PreviewAsk, type PreviewResult } from "./NodePreviewPanel";
import { CANVAS_TABS, NODE_TABS, type DockControl } from "../PipelineDock";
import { useRole } from "../../components/role";
import { NodeCard, badgeRows, runWords, type NodeCardType, type NodeRunInfo } from "./NodeCard";
import { nodeMetrics, type NodeMetrics } from "../nodeMetrics";
import "./canvas.css";

export type CanvasRun = {
  id: number;
  version: number;
  status: string;
  nodes: PipelineNodeRun[];
  /** Each write's and gate's measured quality; empty from servers before measured quality. */
  quality?: PipelineNodeQuality[];
  /** While the run goes: where its rows are (rows and rate per node, the slowest node, rows per connection). */
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
  /** Set by the problems list: select this node and move to its setting. */
  focusRequest: { node: string; key: string | null; n: number } | null;
  run: CanvasRun | null;
  title: string;
  /** Kept by the page across switches to the YAML tab: the last graph that could be read. */
  memory: { lastGood?: Graph | null };
  /** The panel under the canvas: the canvas shows its Settings, Preview and Suggestions tabs there. */
  dock: DockControl;
};

let catalogueLoad: Promise<NodeType[]> | null = null;
const loadCatalogue = () => (catalogueLoad ??= pipelinesApi.nodes().catch((e) => {
  catalogueLoad = null;
  throw e;
}));

const nodeTypes = { node: NodeCard };
const SuggestionsPanel = lazy(() => import("./SuggestionsPanel"));
/** How long after the last change the predicted scores are worked out again. */
const PREDICT_AFTER_MS = 700;
/** Setting kinds typed a character at a time: written to the YAML once typing pauses. */
const TYPED = new Set(["text", "number", "expression", "expressions", "conditions", "column_map", "asset", "rules"]);
/** The smallest zoom at which a node's words can still be read. */
const MIN_READABLE = 0.7;
/** On a Mac, Ctrl+click opens a context menu; elsewhere it is a click. */
const IS_MAC = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
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
  ["Tab, Shift+Tab", "Move between nodes (and on to the settings)"],
  ["Arrow keys", "Move to a connected node: right to the next, left to the one before, up and down between nodes beside each other"],
  ["Shift+Arrow keys", "Move the node on the canvas"],
  ["Alt+Arrow keys", "Pan the canvas"],
  ["C", "Connect to… : connect the node to another"],
  ["A", "Add a node: open the node list and go to its search"],
  ["Arrow keys in the node list", "Move through the folders and nodes; Right opens a folder, Left closes it; Enter adds the node"],
  ["Enter", "Open the node's settings and go to them (a double-click does the same)"],
  ["Shift+F10 or the menu key", "Open the node's menu: preview its rows, open its settings, connect, duplicate or remove it"],
  ["Delete or Backspace", "Remove the node (or a connection selected with the mouse)"],
  ["Ctrl+D", "Duplicate the node"],
  ["Ctrl+Z, Ctrl+Shift+Z or Ctrl+Y", "Undo, redo"],
  ["+ and -", "Zoom in and out"],
  ["F", "Fit the whole pipeline in view"],
  ["R", "Readable size: the first nodes at a size you can read"],
  ["L", "Lay the nodes out again, left to right"],
  ["?", "These shortcuts"],
  ["Escape", "Clear the selection, or close a menu"],
];

function Canvas(props: CanvasProps) {
  const { yaml, onEdit, readOnly: lockedByPage, check, selected, onSelect, run } = props;
  const flow = useReactFlow();
  const scheme = useColourScheme();
  const [types, setTypes] = useState<NodeType[] | null>(null);
  const [typesError, setTypesError] = useState<string | null>(null);
  const [sources, setSources] = useState<Source[] | null>(null);
  const [announce, setAnnounce] = useState("");
  const [refusal, setRefusal] = useState<string | null>(null);
  const [paletteOpen, setPaletteOpen] = useState(true);
  const [selectedEdge, setSelectedEdge] = useState<string | null>(null);
  const [connectFor, setConnectFor] = useState<string | null>(null);
  const [prediction, setPrediction] = useState<Prediction | null>(null);
  // The panel under the canvas: selecting a node shows its settings there (Enter, a double-click and
  // the node's menu also move the focus into them).
  const { dock } = props;
  const dockRef = useRef(dock);
  dockRef.current = dock;
  const suggestOpen = dock.tab === "suggestions";
  // Set when Suggestions on the toolbar opens them, so their heading takes the focus once drawn.
  const [suggestFocus, setSuggestFocus] = useState(0);
  // The node's context menu, and previews at a node.
  const [menu, setMenu] = useState<{ node: string; x: number; y: number } | null>(null);
  const [previewAsk, setPreviewAsk] = useState<PreviewAsk | null>(null);
  const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null);
  const role = useRole();
  // A right-click (or Ctrl+click on a Mac) focuses the node without selecting it, so its settings do
  // not change under the pointer before the menu opens. Cleared however the press ends.
  const menuPress = useRef(false);
  const askCount = useRef(0);
  useEffect(() => {
    const clear = () => {
      menuPress.current = false;
    };
    document.addEventListener("pointerup", clear, true);
    document.addEventListener("pointercancel", clear, true);
    document.addEventListener("contextmenu", clear);
    return () => {
      document.removeEventListener("pointerup", clear, true);
      document.removeEventListener("pointercancel", clear, true);
      document.removeEventListener("contextmenu", clear);
    };
  }, []);
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
  // An empty canvas opens the node list, even if it was hidden. (A saved pipeline is empty text
  // until it has loaded, which is not an empty canvas.)
  const nodeCount = graph?.nodes.length ?? -1;
  const loaded = yaml.trim() !== "" && types != null;
  useEffect(() => {
    if (loaded && nodeCount === 0 && !lockedByPage) setPaletteOpen(true);
  }, [loaded, nodeCount, lockedByPage]);
  // A selected node that is gone (undone, removed in the YAML) is no longer selected.
  useEffect(() => {
    if (selected && graph && !graph.nodes.some((s) => s.id === selected)) onSelect(null);
  }, [graph, selected, onSelect]);
  const problems = useMemo(() => problemsByNode(check?.problems ?? []), [check]);
  const runNodes = useMemo(() => {
    const m = new Map<string, NodeRunInfo>();
    const going = run != null && (run.status === "queued" || run.status === "running");
    if (going && run.nodes.length === 0) {
      // Live: rows so far and the rate, from the run's latest progress.
      for (const [id, l] of liveNodes(run.progress))
        m.set(id, { rowsIn: l.rowsIn, rowsOut: l.rowsOut, outcome: null, error: null, live: { rate: l.rate, state: l.state, slowest: l.slowest } });
      return m;
    }
    for (const s of run?.nodes ?? []) m.set(s.node, { rowsIn: s.rowsIn, rowsOut: s.rowsOut, outcome: s.outcome, error: s.error });
    return m;
  }, [run]);
  const runQuality = useMemo(() => new Map((run?.quality ?? []).map((q) => [q.node, q])), [run]);
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
  const predicted = useMemo(() => predictionsByNode(read.ok ? prediction : null), [prediction, read.ok]);

  // ---- where the nodes go: their real heights and the connections' labels ----

  // Each node's height as drawn by React Flow, once it has been; until then (and while it grows
  // after a run) the height worked out from its ports and rows of chips, whichever is larger.
  const [drawnHeights, setDrawnHeights] = useState<Record<string, number>>({});
  const heights = useMemo(() => {
    const out: Record<string, number> = {};
    for (const s of graph?.nodes ?? []) {
      const rows = badgeRows({
        problems: problems.get(s.id)?.length ?? 0,
        run: runNodes.get(s.id) ?? null,
        quality: runQuality.get(s.id) ?? null,
        predicted: predicted.get(s.id) ?? null,
      });
      out[s.id] = Math.max(nodeHeight(portsOf(s, catalogue), rows), drawnHeights[s.id] ?? 0);
    }
    return out;
  }, [graph, catalogue, problems, runNodes, runQuality, predicted, drawnHeights]);
  const edgeLabels = useMemo(() => {
    const out = new Map<string, string>();
    if (!graph) return out;
    const rows = new Map(run?.nodes.map((s) => [s.node, s]) ?? []);
    const live = run && run.nodes.length === 0 ? liveEdgeRows(run.progress) : null;
    for (const e of graph.edges) {
      const plain = e.fromPort === "out" && e.toPort === "in";
      const counted = live
        ? (live.get(`${e.from}.${e.fromPort}>${e.to}.${e.toPort}`) ?? null)
        : (rows.get(e.from)?.ports?.out?.[e.fromPort] ?? (e.fromPort === "out" ? rows.get(e.from)?.rowsOut : null));
      const parts = [plain ? "" : e.toPort === "in" || e.toPort === "main" ? e.fromPort : `${e.fromPort} → ${e.toPort}`];
      if (run && counted != null) parts.push(plural(counted, "row"));
      out.set(edgeId(e), parts.filter(Boolean).join(" · "));
    }
    return out;
  }, [graph, run]);
  const sizes = useMemo<LayoutSizes>(() => {
    const labels: EdgeLabel[] = (graph?.edges ?? []).map((e) => ({ from: e.from, to: e.to, text: edgeLabels.get(edgeId(e)) ?? "" }));
    return { heights, labels };
  }, [graph, heights, edgeLabels]);
  // Where each node is placed: its saved position (what the YAML's layout says), else laid out.
  const positions = useMemo(() => (graph ? placed(graph, catalogue, sizes) : {}), [graph, catalogue, sizes]);
  // Where it is shown: moved apart in the view only where its badges or a connection's label would
  // not fit between saved positions (a layout saved before a run). The YAML is not changed until a
  // node is moved or Auto-layout is chosen.
  const shown = useMemo(() => makeRoom(positions, heights, sizes.labels), [positions, heights, sizes]);
  const say = useCallback((words: string) => {
    // Cleared first, so the same words twice are read out twice.
    setAnnounce("");
    window.setTimeout(() => setAnnounce(words), 30);
  }, []);

  /** Where the overview map is, in canvas pixels; null while it is not shown (narrow screens). */
  const minimapBox = useCallback((): Box | null => {
    const box = flowBox.current?.getBoundingClientRect();
    const map = flowBox.current?.querySelector<HTMLElement>(".react-flow__minimap")?.getBoundingClientRect();
    if (!box || !map || map.width === 0 || map.height === 0) return null;
    return { x: map.left - box.left, y: map.top - box.top, width: map.width, height: map.height };
  }, []);

  /** The part of the canvas not under the node list, in canvas pixels. */
  const visibleArea = useCallback(() => {
    const box = flowBox.current?.getBoundingClientRect();
    if (!box) return null;
    const stage = flowBox.current!.parentElement!;
    const list = stage.querySelector<HTMLElement>(".cv-palette-panel")?.getBoundingClientRect();
    const left = list && list.right > box.left ? list.right - box.left : 0;
    return { left, top: 0, width: Math.max(120, box.width - left), height: box.height };
  }, []);

  /**
   * Selects a node and shows the panel under the canvas with its Settings (its Preview stays shown if
   * it was): a click, the focus moving to a node and the arrow keys all do this. The focus stays where
   * it is; Enter moves it into the settings.
   */
  const selectNode = useCallback(
    (id: string) => {
      onSelect(id);
      const d = dockRef.current;
      d.show(NODE_TABS.has(d.tab) ? d.tab : "settings");
    },
    [onSelect],
  );

  /** Moves the focus to the first field of the settings in the panel under the canvas, once drawn. */
  const focusSettings = useCallback(() => {
    window.setTimeout(() => dockRef.current.host?.querySelector<HTMLElement>("input, select, textarea")?.focus(), 30);
  }, []);

  /** Focuses a node, moving the view to it when it is outside. */
  const showNode = useCallback(
    (id: string): boolean => {
      const el = flowBox.current?.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(id)}"]`);
      if (!el) return false;
      el.focus({ preventScroll: true });
      const flowNode = flow.getNode(id);
      const box = flowBox.current?.getBoundingClientRect();
      const area = visibleArea();
      const r = el.getBoundingClientRect();
      if (flowNode && box && area) {
        const l = box.left + area.left;
        const out = r.left < l || r.right > l + area.width || r.top < box.top || r.bottom > box.bottom;
        if (out) {
          // Centred in the part of the canvas not under the node list.
          const z = flow.getZoom();
          const cx = area.left + area.width / 2;
          const cy = area.height / 2;
          void flow.setViewport({ x: cx - (flowNode.position.x + 106) * z, y: cy - (flowNode.position.y + 60) * z, zoom: z }, { duration });
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

  // A node added or renamed is focused, and brought into view, once React Flow has drawn it.
  useEffect(() => {
    const id = pendingFocus.current;
    if (!id) return;
    const t = window.setTimeout(() => {
      if (showNode(id)) pendingFocus.current = null;
    }, 60);
    return () => window.clearTimeout(t);
  });

  /** Applies a change, or says why it could not be made. Structural changes keep every node where it is drawn. */
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

  // ---- nodes ----

  const add = (type: string, at?: Point) => {
    if (readOnly || !graph) return;
    flush();
    rememberUsed(type);
    const sel = !at && selected ? graph.nodes.find((s) => s.id === selected) : undefined;
    const outs = sel ? portsOf(sel, catalogue).outputs : [];
    const t = catalogue.get(type);
    const after = sel && outs.length > 0 && t && t.inputs.length > 0 ? { node: sel.id, port: outs.includes("out") ? "out" : outs[0] } : null;
    const spot = at ?? spotAfter(shown, sel?.id ?? null);
    const c = addNode(yamlRef.current, type, catalogue, after, spot);
    const id = "id" in c ? c.id! : null;
    const label = id
      ? `Added ${t?.label ?? type} node ${id}${after ? `, reading from ${refText(after.node, after.port)}` : ""}.`
      : "";
    const made = apply(c, label, { pin: id ? { [id]: spot } : true });
    if (made) {
      onSelect(made);
      dock.show("settings");
      pendingFocus.current = made;
    }
  };

  const remove = (id: string) => {
    if (readOnly || !graph) return;
    flush();
    const order = graph.nodes.map((s) => s.id);
    const next = order[order.indexOf(id) + 1] ?? order[order.indexOf(id) - 1] ?? null;
    const c = removeNode(yamlRef.current, id, catalogue);
    apply(c, `Removed node ${id} and its connections.`, { pin: true });
    if (!("error" in c)) {
      if (next) {
        selectNode(next);
        pendingFocus.current = next;
      } else {
        onSelect(null);
        flowBox.current?.focus();
      }
    }
  };

  const duplicate = (id: string) => {
    if (readOnly) return;
    flush();
    const at = positions[id];
    const c = duplicateNode(yamlRef.current, id);
    const newId = "id" in c ? c.id! : null;
    const spot = at ? { x: at.x + 40, y: at.y + 140 } : undefined;
    const made = apply(c, newId ? `Duplicated ${id} as ${newId}.` : "", { pin: newId && spot ? { [newId]: spot } : true });
    if (made) {
      selectNode(made);
      pendingFocus.current = made;
    }
  };

  const rename = (id: string, to: string): string | null => {
    flush();
    const c = renameNode(yamlRef.current, id, to);
    if ("error" in c) return c.error;
    const moved = { ...positions, [to]: positions[id] };
    delete moved[id];
    const pinned = setPositions(c.text, moved);
    yamlRef.current = "error" in pinned ? c.text : pinned.text;
    onEdit(yamlRef.current, `Renamed node ${id} to ${to}.`);
    say(`Renamed node ${id} to ${to}.`);
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

  /** Adds a suggestion's nodes between the node rows come from and the write, as one change. */
  const addSuggestion = (sg: Suggestion): string | null => {
    if (readOnly || !graph) return "The canvas cannot change this pipeline now.";
    flush();
    const from = positions[sg.placement.from.split(".")[0]];
    const to = positions[sg.placement.before];
    const base = from && to ? { x: (from.x + to.x) / 2, y: Math.max(from.y, to.y) + 170 } : spotAfter(shown, null);
    const spots = Object.fromEntries(sg.nodes.map((n, i) => [n.id, { x: base.x + i * 250, y: base.y }]));
    const c = insertNodes(yamlRef.current, catalogue, sg.nodes, sg.placement);
    if ("error" in c) return c.error;
    // The list stays open (it is worked out again for the changed pipeline); the change is announced.
    apply(c, `Added ${sg.nodes.map((n) => n.id).join(" and ")} before ${sg.placement.before}: ${sg.title}.`, { pin: spots });
    return null;
  };

  const layOut = () => {
    if (readOnly || !graph) return;
    flush();
    apply(setPositions(yamlRef.current, autoLayout(graph, catalogue, sizes)), "Laid the nodes out again, left to right.");
    window.setTimeout(() => readable(), 50);
  };

  // ---- React Flow's view of the graph ----

  const derived = useMemo<NodeCardType[]>(() => {
    if (!graph) return [];
    return graph.nodes.map((s) => {
      const t = s.type ? catalogue.get(s.type) : undefined;
      const ports = portsOf(s, catalogue);
      const outline = check?.nodes.find((o) => o.id === s.id);
      const n = problems.get(s.id)?.length ?? 0;
      const runInfo = runNodes.get(s.id) ?? null;
      const quality = runQuality.get(s.id) ?? null;
      const qualityText = nodeQualityWords(quality);
      const estimate = predicted.get(s.id) ?? null;
      const summary = nodeSummary(s);
      const columns = outline ? columnsText(outline) : "Columns known once the pipeline has been checked";
      return {
        id: s.id,
        type: "node",
        position: shown[s.id] ?? { x: 0, y: 0 },
        selected: s.id === selected,
        data: {
          node: s,
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
          `Node ${s.id}, ${kindWord(t?.kind)}: ${nodeLabel(s, t?.label ?? s.type ?? "no type")}`,
          summary,
          s.inputs.length ? `reads from ${s.inputs.map((l) => refText(l.from, l.fromPort)).join(" and ")}` : "",
          n ? `${plural(n, "problem")}` : "",
          runInfo ? `${runInfo.live ? "running now" : "last run"}: ${runWords(runInfo)}` : "",
          qualityText ?? "",
          predictedWords(estimate) ?? "",
          estimate?.unknown.length
            ? `${plural(estimate.unknown.length, "thing")} the estimate could not tell, listed in its settings`
            : "",
        ]
          .filter(Boolean)
          .join(". "),
        // The columns leaving it, and the keys, are its description rather than its name.
        domAttributes: { "aria-describedby": `cv-cols-${s.id} cv-node-help` },
      };
    });
  }, [graph, catalogue, check, problems, runNodes, runQuality, predicted, shown, selected, readOnly]);

  const [flowNodes, setFlowNodes] = useState<NodeCardType[]>(derived);
  useEffect(() => {
    setFlowNodes((prev) =>
      derived.map((n) => {
        const old = prev.find((p) => p.id === n.id);
        return old?.measured ? { ...n, measured: old.measured } : n;
      }),
    );
  }, [derived]);

  const onNodesChange = useCallback((changes: NodeChange<NodeCardType>[]) => {
    // Selection is the page's; React Flow's own select and remove changes are not applied.
    setFlowNodes((ns) => applyNodeChanges(changes.filter((c) => c.type !== "select" && c.type !== "remove"), ns));
    // Each node's drawn height, for making room (only when it changes by more than a pixel).
    const drawn = changes.flatMap((c) => (c.type === "dimensions" && c.dimensions ? [[c.id, Math.ceil(c.dimensions.height)] as const] : []));
    if (drawn.length)
      setDrawnHeights((old) => (drawn.some(([id, h]) => Math.abs((old[id] ?? 0) - h) > 1) ? { ...old, ...Object.fromEntries(drawn) } : old));
  }, []);

  const edges = useMemo<Edge[]>(() => {
    if (!graph) return [];
    const ids = new Set(graph.nodes.map((s) => s.id));
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
      const pad = FIT_PAD;
      // The overview map covers the bottom-right corner: the nodes are fitted beside it, never under it.
      const map = minimapBox();
      const fits = fitZoom(b, area, map);
      const opts = { duration: animate ? duration : 0 };
      if (fitInside || fits >= MIN_READABLE) {
        void flow.setViewport(fitBesideMap(b, area, map), opts);
        return;
      }
      // Too big to read whole: its first nodes at the smallest readable size, centred on them.
      const z = MIN_READABLE;
      const first = all.filter((n) => n.position.x < b.x + (area.width - 2 * pad) / z);
      const fb = getNodesBounds(first.length ? first : all);
      const y = fb.height * z < area.height - 2 * pad ? (area.height - fb.height * z) / 2 - fb.y * z : pad - fb.y * z;
      void flow.setViewport({ x: area.left + pad - b.x * z, y, zoom: z }, opts);
    },
    [flow, duration, visibleArea, minimapBox],
  );
  /** Fits the whole pipeline into the visible part of the canvas, however small that makes it. */
  const fitAll = useCallback((animate = true) => frame(true, animate), [frame]);
  /** The pipeline at a size its words can be read: all of it if it fits, else its first nodes. */
  const readable = useCallback((animate = true) => frame(false, animate), [frame]);

  // Fit the pipeline into view when it first appears.
  const fitted = useRef(false);
  useEffect(() => {
    if (fitted.current || flowNodes.length === 0 || !flowNodes.every((n) => n.measured)) return;
    fitted.current = true;
    readable(false);
  }, [flowNodes, readable]);

  // A problem chosen in the problems list: select its node and go to its setting.
  useEffect(() => {
    const r = props.focusRequest;
    if (!r) return;
    onSelect(r.node);
    dock.show("settings");
    window.setTimeout(() => {
      const panel = dockRef.current.host;
      const field = r.key ? panel?.querySelector<HTMLElement>(`[data-setting="${CSS.escape(r.key)}"]`) : null;
      const target = field?.querySelector<HTMLElement>("input, textarea, select, button") ?? null;
      if (target) {
        target.focus();
        field?.scrollIntoView({ block: "nearest" });
      } else {
        focusNode(r.node);
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
      const why = "Connect an output port (on the right of a node) to an input port (on the left of another).";
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
    const type = ev.dataTransfer.getData("application/x-kilnmark-node");
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
    // Up and down: the nearest node above or below, preferring the same column.
    const down = key === "ArrowDown";
    let best: string | null = null;
    let score = Infinity;
    for (const s of graph.nodes) {
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

  /** After an undo or redo: the focus goes back to the selected node, else to the canvas. */
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
    // Shortcuts belong to the canvas and its tools, not to the panel, the node list or a dialog.
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
    if (k === "ContextMenu" || (k === "F10" && ev.shiftKey)) {
      done();
      const r = nodeEl?.getBoundingClientRect();
      setMenu({ node: id, x: (r?.left ?? 0) + 16, y: (r?.top ?? 0) + 28 });
      return;
    }
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
      dock.show("settings");
      focusSettings();
      return;
    }
    if (k.startsWith("Arrow")) {
      done();
      if (ev.shiftKey && !readOnly) {
        // From where it is shown, so it moves from where it is seen.
        const p = shown[id];
        if (!p) return;
        const node = 20;
        const to = { x: p.x + (k === "ArrowRight" ? node : k === "ArrowLeft" ? -node : 0), y: p.y + (k === "ArrowDown" ? node : k === "ArrowUp" ? -node : 0) };
        apply(setPositions(yamlRef.current, { ...positions, [id]: to }), `Moved ${id} to ${Math.round(to.x)}, ${Math.round(to.y)}.`, { coalesce: `move:${id}` });
        return;
      }
      const next = neighbour(id, k);
      if (next) {
        selectNode(next);
        focusNode(next);
      } else {
        say(k === "ArrowRight" ? `Nothing reads from ${id}.` : k === "ArrowLeft" ? `${id} reads from no node.` : `No node ${k === "ArrowUp" ? "above" : "below"} ${id}.`);
      }
    }
  };

  const openConnect = (id: string) => {
    setConnectFor(id);
    window.setTimeout(() => connectDialog.current?.showModal(), 0);
  };

  const selectedNode = graph?.nodes.find((s) => s.id === selected) ?? null;
  const kindOf = (id: string) => {
    const t = graph?.nodes.find((s) => s.id === id)?.type;
    return t ? (catalogue.get(t)?.kind ?? null) : null;
  };
  const previewNode = (id: string) => {
    flush();
    onSelect(id);
    dock.show("preview");
    // Counted here, not from the last ask, which the panel clears once it has run it.
    askCount.current += 1;
    setPreviewAsk({ node: id, n: askCount.current });
    // The menu has closed: the focus goes to the preview, under the canvas.
    window.setTimeout(() => dockRef.current.panel()?.focus(), 30);
  };
  const menuItems = (id: string): NodeMenuItem[] => {
    const writes = kindOf(id) === "sink";
    const designer = allows(role, "designer");
    const items: NodeMenuItem[] = [
      {
        id: "preview",
        label: "Preview rows",
        onSelect: () => previewNode(id),
        disabled: writes || !designer || broken,
        note: writes
          ? "A write passes no rows on"
          : !designer
            ? "For designers and admins"
            : broken
              ? "Fix the YAML first"
              : undefined,
      },
      {
        id: "settings",
        label: "Settings",
        onSelect: () => {
          onSelect(id);
          dock.show("settings");
          focusSettings();
        },
      },
    ];
    if (!readOnly) {
      items.push(
        { id: "connect", label: "Connect to…", onSelect: () => openConnect(id) },
        { id: "duplicate", label: "Duplicate", onSelect: () => duplicate(id) },
        { id: "remove", label: "Remove", onSelect: () => remove(id) },
      );
    }
    return items;
  };
  const offers = connectFor && graph ? possibleConnections(graph, connectFor, catalogue) : [];

  /** A node's metrics from the run shown, once it has ended (a run that goes shows its rows live on the card). */
  const metricsOf = (id: string): NodeMetrics | null => {
    if (run == null || run.status === "queued" || run.status === "running") return null;
    const r = run.nodes.find((n) => n.node === id);
    return r ? nodeMetrics(r, run.progress?.slowest ?? null) : null;
  };

  /** The Settings, Preview or Suggestions tab of the panel under the canvas. */
  const dockBody = () => {
    let body;
    if (dock.tab === "suggestions") {
      body = graph?.nodes.length ? (
        <Suspense fallback={<p className="small" role="status">Loading suggestions…</p>}>
          <SuggestionsPanel
            yaml={yaml}
            readOnly={readOnly}
            onAdd={addSuggestion}
            focusAsk={suggestFocus}
            onFocused={() => setSuggestFocus(0)}
          />
        </Suspense>
      ) : (
        <p className="hint cv-dock-none">Suggestions appear here once the pipeline has nodes.</p>
      );
    } else if (!selectedNode) {
      body = (
        <p className="hint cv-dock-none">
          No node is selected. Click a node on the canvas, or move to one with Tab and press Enter, to{" "}
          {dock.tab === "preview" ? "preview the rows leaving it" : "see its settings"} here.
        </p>
      );
    } else if (dock.tab === "preview") {
      body = (
        <>
          <h3 className="cv-side-title">
            Preview of <span className="mono">{selectedNode.id}</span>
          </h3>
          <NodePreviewPanel
            key={selectedNode.id}
            yaml={yaml}
            node={selectedNode.id}
            outputs={portsOf(selectedNode, catalogue).outputs}
            kind={kindOf(selectedNode.id)}
            role={role}
            ask={previewAsk}
            last={previewResult}
            onResult={setPreviewResult}
            onAsked={() => setPreviewAsk(null)}
          />
        </>
      );
    } else {
      body = (
        <SettingsPanel
          key={selectedNode.id}
          node={selectedNode}
          type={selectedNode.type ? catalogue.get(selectedNode.type) : undefined}
          catalogue={catalogue}
          check={check}
          problems={problems.get(selectedNode.id) ?? []}
          sources={sources}
          readOnly={readOnly}
          run={runNodes.get(selectedNode.id) ?? null}
          quality={runQuality.get(selectedNode.id) ?? null}
          predicted={predicted.get(selectedNode.id) ?? null}
          metrics={metricsOf(selectedNode.id)}
          parameters={graph?.parameters ?? []}
          yaml={yaml}
          onSetting={(key, value, kind) => setOne(selectedNode.id, key, value, kind)}
          onRename={(to) => rename(selectedNode.id, to)}
          onDisconnect={unlink}
          onConnectMenu={() => openConnect(selectedNode.id)}
          onDelete={() => remove(selectedNode.id)}
          onDuplicate={() => duplicate(selectedNode.id)}
        />
      );
    }
    return (
      <div
        className={`cv-dock-body cv-dock-${dock.tab}`}
        onBlur={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) flush();
        }}
      >
        {body}
      </div>
    );
  };

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
          Could not load the node types: {typesError}
        </p>
      )}
      <div className="cv-toolbar" ref={toolbar} role="group" aria-label="Canvas tools">
        <button
          type="button"
          className={`cv-tool${paletteOpen ? " is-on" : ""}`}
          aria-pressed={paletteOpen}
          aria-controls="cv-palette-panel"
          title={paletteOpen ? "Hide the node list" : "Show the node list"}
          onClick={() => setPaletteOpen((o) => !o)}
        >
          {paletteOpen ? (
            <IconLayoutSidebarLeftCollapse size={18} stroke={1.8} aria-hidden="true" />
          ) : (
            <IconLayoutSidebarLeftExpand size={18} stroke={1.8} aria-hidden="true" />
          )}
          Nodes
        </button>
        <span className="cv-tool-sep" aria-hidden="true" />
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
        <button type="button" className="cv-tool" onClick={layOut} disabled={readOnly || !graph?.nodes.length}>
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
          className={`cv-tool${suggestOpen && dock.open ? " is-on" : ""}`}
          title="Show the suggestions in the panel under the canvas"
          onClick={() => {
            flush();
            dock.show("suggestions");
            setSuggestFocus((n) => n + 1);
          }}
          disabled={!graph?.nodes.length}
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

      <div className={`cv-stage${paletteOpen ? "" : " no-list"}`}>
        <Palette
          types={types}
          usedTypes={graph?.nodes.map((s) => s.type ?? "") ?? []}
          readOnly={readOnly}
          expanded={paletteOpen}
          onAdd={(type) => add(type)}
          searchRef={searchBox}
          hint={
            readOnly
              ? lockedByPage
                ? "This pipeline is read-only here."
                : "Nodes can be added once the YAML can be read."
              : selected
                ? `Choose a node to add it after ${selected}, or drag it onto the canvas.`
                : "Choose a node to add it, or drag it onto the canvas."
          }
        />
        <div
          className="cv-flow dg-frame"
          ref={flowBox}
          tabIndex={-1}
          onDragOver={(e) => {
            if (e.dataTransfer.types.includes("application/x-kilnmark-node")) {
              e.preventDefault();
              e.dataTransfer.dropEffect = "copy";
            }
          }}
          onDrop={onDrop}
          onPointerDownCapture={(e) => {
            menuPress.current = e.button === 2 || (e.button === 0 && e.ctrlKey && !e.metaKey && IS_MAC);
          }}
          onFocus={(e) => {
            if (menuPress.current) return;
            const el = (e.target as HTMLElement).closest?.(".react-flow__node") as HTMLElement | null;
            const id = el?.dataset.id;
            if (id && quietFocus.current === id) {
              quietFocus.current = null;
              return;
            }
            if (id && id !== selected) selectNode(id);
          }}
          role="region"
          aria-label={`Canvas of ${props.title}: ${plural(graph?.nodes.length ?? 0, "node")}. Press question mark for keyboard shortcuts.`}
        >
          <ReactFlow<NodeCardType, Edge>
            nodes={flowNodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onNodeClick={(_, n) => {
              setSelectedEdge(null);
              selectNode(n.id);
            }}
            onNodeDoubleClick={(_, n) => {
              setSelectedEdge(null);
              flush();
              onSelect(n.id);
              dock.show("settings");
            }}
            onEdgeClick={(_, e) => {
              setSelectedEdge(e.id);
              say(`Selected the connection from ${edgeWords(e.data as GraphEdge)}. Press Delete to remove it.`);
            }}
            onPaneClick={() => {
              setSelectedEdge(null);
              onSelect(null);
            }}
            onNodeContextMenu={(e, n) => {
              e.preventDefault();
              menuPress.current = false;
              setSelectedEdge(null);
              setMenu({ node: n.id, x: e.clientX, y: e.clientY });
            }}
            onNodeDragStop={(_, __, moved) => {
              if (readOnly) return;
              flush();
              const next = { ...positions };
              for (const n of moved) next[n.id] = n.position;
              apply(setPositions(yamlRef.current, next), moved.length === 1 ? `Moved ${moved[0].id}.` : `Moved ${plural(moved.length, "node")}.`);
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
            Arrow keys move to connected nodes; Shift and an arrow key moves the node; C connects it; Delete removes
            it; Enter goes to its settings. Question mark lists every shortcut.
          </p>
          {graph && graph.nodes.length === 0 && (
            <div className="cv-empty">
              <p>
                <strong>An empty canvas.</strong> Add a node from the list: start with a source, such as{" "}
                {catalogue.get("read")?.label ?? "a read"}.
              </p>
            </div>
          )}
        </div>

      </div>
      {dock.host && CANVAS_TABS.has(dock.tab) && createPortal(dockBody(), dock.host)}
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
        Drag from an output port (right) to an input port (left) to connect nodes, or use Connect to…. Press ? for keyboard
        shortcuts.
      </p>

      {menu && graph?.nodes.some((s) => s.id === menu.node) && (
        <NodeMenu
          node={menu.node}
          at={{ x: menu.x, y: menu.y }}
          items={menuItems(menu.node)}
          onClose={(back) => {
            const id = menu.node;
            setMenu(null);
            if (back) {
              quietFocus.current = id;
              focusNode(id);
            }
          }}
        />
      )}

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
