import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent } from "react";
import {
  applyNodeChanges,
  Background,
  getNodesBounds,
  MarkerType,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type Edge,
  type FinalConnectionState,
  type NodeChange,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  IconArrowBackUp,
  IconArrowForwardUp,
  IconFocus2,
  IconKeyboard,
  IconLayoutDistributeHorizontal,
  IconPlug,
  IconSearch,
  IconZoomIn,
  IconZoomOut,
} from "@tabler/icons-react";
import { api, formatNumber, pipelinesApi, type PipelineStepRun, type PipelineValidation, type Source, type StepType } from "../../api";
import { useColourScheme } from "../../theme/appearance";
import { columnsText } from "../outline";
import { KINDS, kindWord, problemsByStep, stepSummary } from "./insight";
import { autoLayout, placed, spotAfter } from "./layout";
import {
  addStep,
  catalogueOf,
  connect,
  connectProblem,
  disconnect,
  duplicateStep,
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
import { SettingsPanel } from "./SettingsPanel";
import { StepIcon, StepNode, runWords, type StepNodeType, type StepRunInfo } from "./StepNode";
import "./canvas.css";

export type CanvasRun = { id: number; version: number; status: string; steps: PipelineStepRun[] };

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
  ["A", "Add a step: go to the step search"],
  ["Enter", "Go to the step's settings"],
  ["Delete or Backspace", "Remove the step, or the selected connection"],
  ["Ctrl+D", "Duplicate the step"],
  ["Ctrl+Z, Ctrl+Shift+Z or Ctrl+Y", "Undo, redo"],
  ["+ and -", "Zoom in and out"],
  ["F", "Fit the pipeline in view"],
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
  const [search, setSearch] = useState("");
  const [selectedEdge, setSelectedEdge] = useState<string | null>(null);
  const [connectFor, setConnectFor] = useState<string | null>(null);
  const wrap = useRef<HTMLDivElement>(null);
  const flowBox = useRef<HTMLDivElement>(null);
  const searchBox = useRef<HTMLInputElement>(null);
  const connectDialog = useRef<HTMLDialogElement>(null);
  const keysDialog = useRef<HTMLDialogElement>(null);
  const pendingFocus = useRef<string | null>(null);
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
  const positions = useMemo(() => (graph ? placed(graph, catalogue) : {}), [graph, catalogue]);
  const problems = useMemo(() => problemsByStep(check?.problems ?? []), [check]);
  const runSteps = useMemo(() => {
    const m = new Map<string, StepRunInfo>();
    for (const s of run?.steps ?? []) m.set(s.step, { rowsIn: s.rowsIn, rowsOut: s.rowsOut, outcome: s.outcome, error: s.error });
    return m;
  }, [run]);

  const say = useCallback((words: string) => {
    // Cleared first, so the same words twice are read out twice.
    setAnnounce("");
    window.setTimeout(() => setAnnounce(words), 30);
  }, []);

  /** Focuses a step, moving the view to it when it is outside. */
  const showNode = useCallback(
    (id: string): boolean => {
      const el = flowBox.current?.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(id)}"]`);
      if (!el) return false;
      el.focus({ preventScroll: true });
      const node = flow.getNode(id);
      const box = flowBox.current?.getBoundingClientRect();
      const r = el.getBoundingClientRect();
      if (node && box && (r.left < box.left || r.right > box.right || r.top < box.top || r.bottom > box.bottom)) {
        void flow.setCenter(node.position.x + 106, node.position.y + 60, { zoom: flow.getZoom(), duration });
      }
      return true;
    },
    [flow, duration],
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
      onEdit(text, label, opts.coalesce);
      say(label);
      return c.id ?? null;
    },
    [onEdit, positions, say],
  );

  // ---- steps ----

  const add = (type: string, at?: Point) => {
    if (readOnly || !graph) return;
    const sel = !at && selected ? graph.steps.find((s) => s.id === selected) : undefined;
    const outs = sel ? portsOf(sel, catalogue).outputs : [];
    const t = catalogue.get(type);
    const after = sel && outs.length > 0 && t && t.inputs.length > 0 ? { step: sel.id, port: outs.includes("out") ? "out" : outs[0] } : null;
    const spot = at ?? spotAfter(positions, sel?.id ?? null);
    const c = addStep(yaml, type, catalogue, after, spot);
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
    const order = graph.steps.map((s) => s.id);
    const next = order[order.indexOf(id) + 1] ?? order[order.indexOf(id) - 1] ?? null;
    const c = removeStep(yaml, id, catalogue);
    apply(c, `Removed step ${id} and its connections.`, { pin: true });
    if (!("error" in c)) {
      onSelect(next);
      if (next) pendingFocus.current = next;
      else flowBox.current?.focus();
    }
  };

  const duplicate = (id: string) => {
    if (readOnly) return;
    const at = positions[id];
    const c = duplicateStep(yaml, id);
    const newId = "id" in c ? c.id! : null;
    const spot = at ? { x: at.x + 40, y: at.y + 140 } : undefined;
    const made = apply(c, newId ? `Duplicated ${id} as ${newId}.` : "", { pin: newId && spot ? { [newId]: spot } : true });
    if (made) {
      onSelect(made);
      pendingFocus.current = made;
    }
  };

  const rename = (id: string, to: string): string | null => {
    const c = renameStep(yaml, id, to);
    if ("error" in c) return c.error;
    const moved = { ...positions, [to]: positions[id] };
    delete moved[id];
    const pinned = setPositions(c.text, moved);
    onEdit("error" in pinned ? c.text : pinned.text, `Renamed step ${id} to ${to}.`);
    say(`Renamed step ${id} to ${to}.`);
    onSelect(to);
    return null;
  };

  const link = (e: GraphEdge) => apply(connect(yaml, e, catalogue), `Connected ${edgeWords(e)}.`, { pin: true });
  const unlink = (e: GraphEdge) => {
    apply(disconnect(yaml, e, catalogue), `Disconnected ${edgeWords(e)}.`, { pin: true });
    setSelectedEdge(null);
  };

  const setOne = (id: string, key: string, value: Value | undefined, kind: string) =>
    apply(setSetting(yaml, id, key, value, kind), `Changed ${key} of ${id}.`, { coalesce: `${id}:${key}` });

  const layOut = () => {
    if (readOnly || !graph) return;
    apply(setPositions(yaml, autoLayout(graph, catalogue)), "Laid the steps out again, left to right.");
    window.setTimeout(() => fit(), 50);
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
      const summary = stepSummary(s);
      const columns = outline ? columnsText(outline) : "Columns known once the pipeline has been checked";
      return {
        id: s.id,
        type: "step",
        position: positions[s.id] ?? { x: 0, y: 0 },
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
          connectable: !readOnly,
        },
        ariaLabel: [
          `Step ${s.id}, ${kindWord(t?.kind)}: ${t?.label ?? s.type ?? "no type"}`,
          summary,
          s.inputs.length ? `reads from ${s.inputs.map((l) => refText(l.from, l.fromPort)).join(" and ")}` : "",
          n ? `${n} ${n === 1 ? "problem" : "problems"}` : "",
          runInfo ? `last run: ${runWords(runInfo)}` : "",
          columns,
        ]
          .filter(Boolean)
          .join(". "),
      };
    });
  }, [graph, catalogue, check, problems, runSteps, positions, selected, readOnly]);

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
  }, []);

  const edges = useMemo<Edge[]>(() => {
    if (!graph) return [];
    const ids = new Set(graph.steps.map((s) => s.id));
    const rows = new Map(run?.steps.map((s) => [s.step, s]) ?? []);
    return graph.edges
      .filter((e) => ids.has(e.from) && ids.has(e.to))
      .map((e) => {
        const id = edgeId(e);
        const plain = e.fromPort === "out" && e.toPort === "in";
        const counted = rows.get(e.from)?.ports?.out?.[e.fromPort] ?? (e.fromPort === "out" ? rows.get(e.from)?.rowsOut : null);
        const parts = [plain ? "" : e.toPort === "in" || e.toPort === "main" ? e.fromPort : `${e.fromPort} → ${e.toPort}`];
        if (run && counted != null) parts.push(`${formatNumber(counted)} rows`);
        const label = parts.filter(Boolean).join(" · ");
        return {
          id,
          source: e.from,
          target: e.to,
          sourceHandle: `out:${e.fromPort}`,
          targetHandle: `in:${e.toPort}`,
          type: "smoothstep",
          label: label || undefined,
          selected: id === selectedEdge,
          className: `cv-edge${e.fromPort === "out" ? "" : " cv-edge-other"}${id === selectedEdge ? " is-selected" : ""}`,
          markerEnd: { type: MarkerType.ArrowClosed, color: "var(--km-ink-soft)" },
          ariaLabel: `Connection from ${edgeWords(e)}${label ? `, ${label}` : ""}`,
          data: e,
        };
      });
  }, [graph, run, selectedEdge]);

  /**
   * Fits the pipeline into view, but never smaller than can be read: a pipeline too wide for the
   * canvas is shown from its first steps, at the smallest readable size, and can be panned.
   */
  const fit = useCallback(
    (animate = true) => {
      const box = flowBox.current?.getBoundingClientRect();
      const all = flow.getNodes();
      if (!box || all.length === 0) return;
      const b = getNodesBounds(all);
      const pad = 32;
      const zoom = Math.min(1, (box.width - 2 * pad) / Math.max(b.width, 1), (box.height - 2 * pad) / Math.max(b.height, 1));
      const opts = { duration: animate ? duration : 0 };
      if (zoom >= MIN_READABLE) {
        void flow.fitView({ padding: 0.12, maxZoom: 1, ...opts });
        return;
      }
      const z = MIN_READABLE;
      const y = b.height * z < box.height - 2 * pad ? (box.height - b.height * z) / 2 - b.y * z : pad - b.y * z;
      void flow.setViewport({ x: pad - b.x * z, y, zoom: z }, opts);
    },
    [flow, duration],
  );

  // Fit the pipeline into view when it first appears.
  const fitted = useRef(false);
  useEffect(() => {
    if (fitted.current || nodes.length === 0 || !nodes.every((n) => n.measured)) return;
    fitted.current = true;
    fit(false);
  }, [nodes, fit]);

  // A problem chosen in the problems list: select its step and go to its setting.
  useEffect(() => {
    const r = props.focusRequest;
    if (!r) return;
    onSelect(r.step);
    window.setTimeout(() => {
      const panel = wrap.current?.querySelector<HTMLElement>(".cv-panel");
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
    const here = positions[id];
    if (key === "ArrowRight") return graph.edges.find((e) => e.from === id)?.to ?? null;
    if (key === "ArrowLeft") return graph.edges.find((e) => e.to === id)?.from ?? null;
    // Up and down: the nearest step above or below, preferring the same column.
    const down = key === "ArrowDown";
    let best: string | null = null;
    let score = Infinity;
    for (const s of graph.steps) {
      const p = positions[s.id];
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

  const onKeyDown = (ev: KeyboardEvent<HTMLDivElement>) => {
    if (isTyping(ev.target)) return;
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
      const label = props.onUndo();
      say(label ? `Undid: ${label}` : "Nothing to undo.");
      return;
    }
    if (mod && ((k === "z" || k === "Z") && ev.shiftKey || k === "y")) {
      done();
      const label = props.onRedo();
      say(label ? `Redid: ${label}` : "Nothing to redo.");
      return;
    }
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
    if (k === "f" || k === "F") {
      done();
      fit();
      return;
    }
    if ((k === "l" || k === "L") && !mod) {
      done();
      layOut();
      return;
    }
    if ((k === "a" || k === "A") && !mod) {
      done();
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
      window.setTimeout(() => wrap.current?.querySelector<HTMLElement>(".cv-panel input, .cv-panel select, .cv-panel textarea")?.focus(), 30);
      return;
    }
    if (k.startsWith("Arrow")) {
      done();
      if (ev.shiftKey && !readOnly) {
        const p = positions[id];
        if (!p) return;
        const step = 20;
        const to = { x: p.x + (k === "ArrowRight" ? step : k === "ArrowLeft" ? -step : 0), y: p.y + (k === "ArrowDown" ? step : k === "ArrowUp" ? -step : 0) };
        apply(setPositions(yaml, { ...positions, [id]: to }), `Moved ${id} to ${Math.round(to.x)}, ${Math.round(to.y)}.`, { coalesce: `move:${id}` });
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

  // ---- palette ----

  const shownTypes = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (types ?? []).filter((t) => !q || `${t.type} ${t.label} ${t.help}`.toLowerCase().includes(q));
  }, [types, search]);

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
      <div className="cv-grid">
        <aside className="cv-palette" aria-labelledby="cv-palette-h">
          <h3 id="cv-palette-h" className="cv-side-title">
            Steps
          </h3>
          <label className="cv-search">
            <span className="visually-hidden">Find a step type</span>
            <IconSearch size={16} stroke={1.8} aria-hidden="true" />
            <input
              ref={searchBox}
              type="search"
              placeholder="Find a step"
              value={search}
              disabled={readOnly}
              aria-describedby="cv-palette-hint"
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>
          <p id="cv-palette-hint" className="hint">
            {readOnly
              ? lockedByPage
                ? "This pipeline is read-only here."
                : "Steps can be added once the YAML can be read."
              : selected
                ? `Choose a step to add it after ${selected}, or drag it onto the canvas.`
                : "Choose a step to add it, or drag it onto the canvas."}
          </p>
          {types == null && !typesError && <p className="small muted">Loading the step types…</p>}
          {KINDS.map(({ kind, label }) => {
            const list = shownTypes.filter((t) => t.kind === kind);
            if (list.length === 0) return null;
            return (
              <div key={kind} className="cv-group">
                <h4 className="cv-group-title" id={`cv-group-${kind}`}>
                  {label}
                </h4>
                <ul className="cv-type-list" aria-labelledby={`cv-group-${kind}`}>
                  {list.map((t) => (
                    <li key={t.type}>
                      <button
                        type="button"
                        className={`cv-type cv-kind-${t.kind}`}
                        draggable={!readOnly}
                        disabled={readOnly}
                        title={t.help}
                        aria-description={t.help}
                        onDragStart={(e) => {
                          e.dataTransfer.setData("application/x-kilnmark-step", t.type);
                          e.dataTransfer.effectAllowed = "copy";
                        }}
                        onClick={() => add(t.type)}
                      >
                        <span className="cv-type-icon">
                          <StepIcon type={t.type} kind={t.kind} />
                        </span>
                        <span>{t.label}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
          {types && shownTypes.length === 0 && <p className="small muted">No step type matches “{search}”.</p>}
        </aside>

        <div className="cv-main">
          <div className="cv-toolbar" role="group" aria-label="Canvas tools">
            <button type="button" className="cv-tool" onClick={() => say(props.onUndo() ? "Undone." : "Nothing to undo.")} disabled={!props.canUndo}>
              <IconArrowBackUp size={18} stroke={1.8} aria-hidden="true" />
              Undo
            </button>
            <button type="button" className="cv-tool" onClick={() => say(props.onRedo() ? "Redone." : "Nothing to redo.")} disabled={!props.canRedo}>
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
            <button type="button" className="cv-tool" onClick={() => fit()}>
              <IconFocus2 size={18} stroke={1.8} aria-hidden="true" />
              Fit
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
          </div>
          {run && (
            <p className="small cv-run-legend">
              Showing run {run.id} (version {run.version}, {run.status}): rows and how each step ended.
            </p>
          )}
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
                const next = { ...positions };
                for (const n of moved) next[n.id] = n.position;
                apply(setPositions(yaml, next), moved.length === 1 ? `Moved ${moved[0].id}.` : `Moved ${moved.length} steps.`);
              }}
              onConnect={onConnect}
              onConnectEnd={onConnectEnd}
              isValidConnection={isValidConnection}
              nodesDraggable={!readOnly}
              nodesConnectable={!readOnly}
              nodesFocusable
              edgesFocusable
              elementsSelectable
              disableKeyboardA11y
              deleteKeyCode={null}
              selectionKeyCode={null}
              multiSelectionKeyCode={null}
              minZoom={0.3}
              maxZoom={2}
              colorMode={scheme}
              proOptions={{ hideAttribution: false }}
              ariaLabelConfig={{
                "node.a11yDescription.default":
                  "A step. Arrow keys move to connected steps; Shift and an arrow key moves it; C connects it; Delete removes it; Enter goes to its settings. Question mark lists every shortcut.",
                "node.a11yDescription.keyboardDisabled":
                  "A step. Arrow keys move to connected steps; Shift and an arrow key moves it; C connects it; Delete removes it; Enter goes to its settings. Question mark lists every shortcut.",
                "edge.a11yDescription.default": "A connection. Press Delete to remove it.",
              }}
            >
              <Background gap={20} size={1} />
            </ReactFlow>
            {graph && graph.steps.length === 0 && (
              <div className="cv-empty">
                <p>
                  <strong>An empty canvas.</strong> Add a step from the list: start with a source, such as{" "}
                  {catalogue.get("read")?.label ?? "a read"}.
                </p>
              </div>
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
            Drag from an output port (right) to an input port (left) to connect steps, or use Connect to…. Press ? for keyboard shortcuts.
          </p>
        </div>

        <aside className="cv-panel" aria-labelledby="cv-panel-h">
          <h3 id="cv-panel-h" className="cv-side-title">
            {selectedStep ? "Selected step" : "Settings"}
          </h3>
          {selectedStep ? (
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
              onSetting={(key, value, kind) => setOne(selectedStep.id, key, value, kind)}
              onRename={(to) => rename(selectedStep.id, to)}
              onDisconnect={unlink}
              onConnectMenu={() => openConnect(selectedStep.id)}
              onDelete={() => remove(selectedStep.id)}
              onDuplicate={() => duplicate(selectedStep.id)}
            />
          ) : (
            <p className="small muted">Select a step to see and change its settings.</p>
          )}
        </aside>
      </div>

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
