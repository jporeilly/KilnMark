import { useEffect, useMemo, useRef, useState } from "react";
import {
  applyNodeChanges,
  Background,
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type EdgeChange,
  type EdgeProps,
  type Node,
  type NodeChange,
  type NodeProps,
} from "@xyflow/react";
import { Graph, layout } from "@dagrejs/dagre";
import { IconClockSearch, IconPlugConnected, IconBell, IconHandStop, IconHourglass, IconPlayerPlay, IconRepeat, IconSql, IconBox } from "@tabler/icons-react";
import "@xyflow/react/dist/style.css";
import type { FlowNodeStatus } from "../api";
import { useColourScheme } from "../theme/appearance";
import { FlowStatus, flowStatusWords } from "./RunFlow";
import { bowOffsets, connectionSentence, TYPE_WORDS, whenWords, type ConnectionKind, type FlowGraph, type FlowGraphNode, type Point } from "./graph";
import { inColumn, roundedPath, steps, type Columns } from "./route";
import "../pipelines/canvas/canvas.css";
import "./flows.css";

/** A node is drawn as a pipeline's is (NodeCard): the same width, head, ports and foot. */
const WIDTH = 212;
const HEIGHT = 118;
/** The foot that holds a node's status in a run (its chip and padding). */
const STATUS_ROW = 29;
/** How far down a card its `in` and `out` connectors are: the middle of its ports row. */
const PORT_Y = 102;
const LABEL_HEIGHT = 20;
/** How round a connection's corners are, as on a pipeline's canvas. */
const CORNER = 8;

/** Each node type's icon. */
const ICONS: Record<string, typeof IconBox> = {
  "run-pipeline": IconPlayerPlay,
  "for-each": IconRepeat,
  notify: IconBell,
  wait: IconHourglass,
  stop: IconHandStop,
  sql: IconSql,
  "check-source": IconPlugConnected,
  "wait-for": IconClockSearch,
};

/** About how wide a connection's label is drawn (11px semibold, with its padding). */
const labelWidth = (text: string) => Math.ceil(text.length * 6.6) + 16;

/**
 * What is chosen on the diagram: a node by id, or a connection by its place in the YAML list with
 * its ends, so that a list changed since (in the YAML) never puts another connection in its place.
 */
export type FlowSelection = { node: string } | { connection: number; from: string; to: string };

type NodeData = { node: FlowGraphNode; status: FlowNodeStatus | null };
type FlowCard = Node<NodeData, "flowNode">;
/** An unseen point at a corner of the routes, so fitting the view takes in every connection, not only the nodes. */
type FitAnchor = Node<Record<string, never>, "fitAnchor">;
type LabelData = { label: string; kind: ConnectionKind; full: string; index: number };
/**
 * A connection between nodes where the layout put them: the route it found (its first and last
 * points on the boxes' borders) and the label where it made room for one.
 */
type RouteData = LabelData & { points: Point[]; labelAt: Point; columns: Columns; bracket: number };
type RoutedEdge = Edge<RouteData, "routed">;
/** A connection between nodes placed by hand: bowed to one side when two nodes have several. */
type BowData = LabelData & { offset: number };
type BowedEdge = Edge<BowData, "bowed">;

/**
 * A flow node, drawn as a pipeline's node is: its type's icon and words, its id, what it does,
 * and a row with its connectors, `in` on the left and `out` on the right. In a run, its status
 * is in the foot.
 */
function FlowNodeBox({ data, selected }: NodeProps<FlowCard>) {
  const { node, status } = data;
  const Icon = ICONS[node.type] ?? IconBox;
  return (
    <div className={`cv-node fd-card fd-type-${node.type}${status ? ` fd-status-${status}` : ""}${selected ? " is-selected" : ""}`}>
      <div className="cv-node-head">
        <span className="cv-node-icon">
          <Icon size={18} stroke={1.8} aria-hidden="true" />
        </span>
        <span className="cv-node-kind">{TYPE_WORDS[node.type] ?? node.type}</span>
      </div>
      <div className="cv-node-id mono">{node.id}</div>
      <div className="cv-node-summary" title={node.summary}>
        {node.summary}
      </div>
      <div className="cv-node-ports">
        <ul className="cv-port-list">
          <li className="cv-port cv-port-in">
            <Handle type="target" position={Position.Left} className="cv-handle" />
            in
          </li>
        </ul>
        <ul className="cv-port-list cv-port-list-out">
          <li className="cv-port cv-port-out">
            out
            <Handle type="source" position={Position.Right} className="cv-handle" />
          </li>
        </ul>
      </div>
      {status && (
        <div className="cv-node-foot">
          <div className="cv-node-chips">
            <FlowStatus status={status} />
          </div>
        </div>
      )}
    </div>
  );
}

function EdgeLabel({ data, at }: { data: LabelData; at: Point }) {
  return (
    <EdgeLabelRenderer>
      <div
        className={`fd-edge-label fd-edge-label-${data.kind} nodrag nopan`}
        style={{ transform: `translate(-50%, -50%) translate(${at.x}px, ${at.y}px)` }}
        title={data.full}
      >
        {data.label}
      </div>
    </EdgeLabelRenderer>
  );
}

/**
 * A connection where the layout put its nodes: in steps, as on a pipeline's canvas, from the node's
 * `out` connector to the next node's `in`, through the layout's route. It turns only in the gaps
 * between columns (between neighbours, in the gap the layout kept for its label, where the label
 * goes), so it never crosses a node it does not join.
 */
function Routed({ data, markerEnd, interactionWidth, sourceX, sourceY, targetX, targetY }: EdgeProps<RoutedEdge>) {
  if (!data) return null;
  if (targetX <= sourceX + 2 * CORNER) {
    // Backwards (a loop, while the YAML is being written, which the check refuses): a plain curve.
    const mid = { x: (sourceX + targetX) / 2, y: Math.min(sourceY, targetY) - 60 };
    const path = `M ${sourceX} ${sourceY} C ${sourceX + 60} ${mid.y} ${targetX - 60} ${mid.y} ${targetX} ${targetY}`;
    return (
      <>
        <BaseEdge path={path} markerEnd={markerEnd} interactionWidth={interactionWidth} />
        <EdgeLabel data={data} at={mid} />
      </>
    );
  }
  const route = [{ x: sourceX, y: sourceY }, ...data.points.slice(1, -1), { x: targetX, y: targetY }];
  return (
    <>
      <BaseEdge path={roundedPath(steps(route, data.columns, data.bracket), CORNER)} markerEnd={markerEnd} interactionWidth={interactionWidth} />
      <EdgeLabel data={data} at={data.labelAt} />
    </>
  );
}

function Bowed({ data, markerEnd, sourceX, sourceY, targetX, targetY, interactionWidth }: EdgeProps<BowedEdge>) {
  if (!data) return null;
  const dx = targetX - sourceX;
  const dy = targetY - sourceY;
  const len = Math.hypot(dx, dy) || 1;
  // The quadratic curve's middle lies half way to its control point: there goes the label.
  const mid = { x: (sourceX + targetX) / 2 + (-dy / len) * data.offset, y: (sourceY + targetY) / 2 + (dx / len) * data.offset };
  const control = { x: 2 * mid.x - (sourceX + targetX) / 2, y: 2 * mid.y - (sourceY + targetY) / 2 };
  const path = `M ${sourceX} ${sourceY} Q ${control.x} ${control.y} ${targetX} ${targetY}`;
  return (
    <>
      <BaseEdge path={path} markerEnd={markerEnd} interactionWidth={interactionWidth} />
      <EdgeLabel data={data} at={mid} />
    </>
  );
}

/** A fit point: nothing to see. */
const FitPoint = () => null;

const nodeTypes = { flowNode: FlowNodeBox, fitAnchor: FitPoint };
const edgeTypes = { routed: Routed, bowed: Bowed };

const markerFor = (kind: ConnectionKind) => ({
  type: MarkerType.ArrowClosed,
  color: kind === "failure" ? "var(--km-bad)" : kind === "condition" ? "var(--km-warn)" : "var(--km-ink)",
});

/**
 * A flow as a graph: a card per node, drawn as a pipeline's node is, and a labelled arrow per
 * connection from a node's `out` connector to another's `in` (solid on success, dashed on failure,
 * dotted always, and a condition's text). Without a `layout:` block it is laid out left to right
 * with room for each label, each connection drawn in steps as on a pipeline's canvas; with one,
 * nodes are where it puts them (any it does not name, where the layout would), and connections
 * between the same two nodes bow apart. With `statuses`, each node shows how it
 * stands in a run, in words and colour. The list under it says the same in sentences.
 *
 * With `editable`, nodes can be dragged (`onMove` is given every node's position, so the picture
 * stays as it is seen), connections drawn from a node's `out` connector to another's `in`
 * (`onConnect`), and a node or connection chosen (`onSelect`); changing them is for the page.
 */
export default function FlowDiagram({
  graph,
  statuses,
  label,
  editable = false,
  selected = null,
  onSelect,
  onMove,
  onConnect,
}: {
  graph: FlowGraph;
  statuses?: Record<string, FlowNodeStatus>;
  label: string;
  editable?: boolean;
  selected?: FlowSelection | null;
  onSelect?: (s: FlowSelection | null) => void;
  onMove?: (positions: Record<string, Point>) => void;
  onConnect?: (from: string, to: string) => void;
}) {
  const scheme = useColourScheme();
  const height = HEIGHT + (statuses ? STATUS_ROW : 0);
  const byHand = Object.keys(graph.positions).length > 0;
  const [moving, setMoving] = useState(false);
  // The layout depends only on the flow: a run's page asks for statuses every two seconds, and
  // only the cards change with them.
  const { where, routes, columns } = useMemo(() => {
    const g = new Graph({ multigraph: true });
    g.setGraph({ rankdir: "LR", nodesep: 30, edgesep: 20, ranksep: 60, marginx: 16, marginy: 16 });
    for (const n of graph.nodes) g.setNode(n.id, { width: WIDTH, height });
    graph.connections.forEach((c, i) =>
      g.setEdge(c.from, c.to, { width: labelWidth(whenWords(c)), height: LABEL_HEIGHT, labelpos: "c" }, `c${i}`),
    );
    layout(g);
    const where: Record<string, Point> = {};
    for (const n of graph.nodes) {
      const p = g.node(n.id);
      where[n.id] = graph.positions[n.id] ?? { x: p.x - WIDTH / 2, y: p.y - height / 2 };
    }
    // Dagre routes from the middle of each box; the connectors are lower, in the ports row. The bends
    // in the gaps between columns, where the labels are, move down by as much, so a label stays on
    // its line; a bend beside a column's nodes keeps the height dagre gave it to clear them.
    const down = PORT_Y - height / 2;
    const columns: Columns = graph.nodes.map((n) => [where[n.id].x, where[n.id].x + WIDTH]);
    const routes = graph.connections.map((c, i) => {
      const r = g.edge({ v: c.from, w: c.to, name: `c${i}` }) as { points: Point[]; x: number; y: number };
      const points = r.points.map((p) => (inColumn(p.x, columns) ? p : { x: p.x, y: p.y + down }));
      return { points, x: r.x, y: inColumn(r.x, columns) ? r.y : r.y + down };
    });
    return { where, routes, columns };
  }, [graph, height]);

  const links = useMemo(() => {
    const offsets = bowOffsets(graph.connections);
    const chosen = selected && "connection" in selected ? selected.connection : null;
    return graph.connections.map((c, i): RoutedEdge | BowedEdge => {
      const common = {
        id: `c${i}`,
        source: c.from,
        target: c.to,
        className: `fd-edge fd-edge-${c.kind}`,
        markerEnd: markerFor(c.kind),
        ariaLabel: connectionSentence(c),
        selected: chosen === c.index,
      };
      const words = { label: whenWords(c), kind: c.kind, full: c.when, index: c.index };
      // The layout's routes hold only while nodes are where it put them.
      return byHand || moving
        ? { ...common, type: "bowed", data: { ...words, offset: offsets[i] } }
        : { ...common, type: "routed", data: { ...words, points: routes[i].points, labelAt: { x: routes[i].x, y: routes[i].y }, columns, bracket: labelWidth(words.label) / 2 + CORNER } };
    });
  }, [graph, routes, columns, byHand, moving, selected]);

  // The layout may take a connection round the outside of the nodes: two unseen points at the corners
  // of every route make fitting the view take them in.
  const anchors = useMemo((): FitAnchor[] => {
    if (byHand) return [];
    const all = routes.flatMap((r) => r.points);
    if (all.length === 0) return [];
    const xs = all.map((p) => p.x);
    const ys = all.map((p) => p.y);
    const corner = (id: string, x: number, y: number): FitAnchor => ({
      id,
      type: "fitAnchor",
      position: { x, y },
      data: {},
      // Drawn, so it is measured and fitting counts it, but never seen or read out.
      style: { visibility: "hidden", width: 1, height: 1 },
      selectable: false,
      draggable: false,
      focusable: false,
    });
    return [corner("__fit-min", Math.min(...xs), Math.min(...ys)), corner("__fit-max", Math.max(...xs), Math.max(...ys))];
  }, [routes, byHand]);

  const cards = useMemo(
    () =>
      graph.nodes.map((n): FlowCard => {
        const status = statuses?.[n.id] ?? null;
        return {
          id: n.id,
          type: "flowNode",
          position: where[n.id],
          data: { node: n, status },
          draggable: editable,
          selected: !!selected && "node" in selected && selected.node === n.id,
          ariaLabel: `Node ${n.id}, ${TYPE_WORDS[n.type] ?? n.type}: ${n.summary}${status ? `. ${flowStatusWords(status)}` : ""}`,
        };
      }),
    [graph, statuses, where, editable, selected],
  );
  // React Flow moves a node while it is dragged through these; the YAML takes the place it is dropped at.
  const [nodes, setNodes] = useState(cards);
  useEffect(() => setNodes(cards), [cards]);
  // A drag is written when it stops (onNodeDragStop). A chosen node moved with the arrow keys is
  // written once the keys have been still for a moment, so each press is not a change of its own.
  const dragging = useRef(false);
  const keyMoves = useRef<Record<string, Point>>({});
  const keyTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(keyTimer.current), []);
  // Choosing (by click, or Enter on a focused node or connection) is the page's to keep.
  const onNodesChange = (all: NodeChange<FlowCard | FitAnchor>[]) => {
    // The fit points are not the flow's: nothing about them is kept.
    const changes = all.filter((c) => !("id" in c) || !c.id.startsWith("__fit-")) as NodeChange<FlowCard>[];
    const chosen = changes.find((c) => c.type === "select" && c.selected);
    if (chosen && "id" in chosen) onSelect?.({ node: chosen.id });
    if (!dragging.current) {
      let moved = false;
      for (const c of changes) {
        if (c.type === "position" && c.position && !c.dragging) {
          keyMoves.current[c.id] = c.position;
          moved = true;
        }
      }
      if (moved) {
        window.clearTimeout(keyTimer.current);
        keyTimer.current = window.setTimeout(() => {
          const next = { ...where, ...keyMoves.current };
          keyMoves.current = {};
          onMove?.(next);
        }, 400);
      }
    }
    setNodes((ns) => applyNodeChanges(changes.filter((c) => c.type === "position" || c.type === "dimensions"), ns));
  };
  const onEdgesChange = (changes: EdgeChange<RoutedEdge | BowedEdge>[]) => {
    const chosen = changes.find((c) => c.type === "select" && c.selected);
    const edge = chosen && "id" in chosen ? links.find((l) => l.id === chosen.id) : null;
    if (edge?.data) onSelect?.({ connection: edge.data.index, from: edge.source, to: edge.target });
  };

  return (
    <>
      <div className={`dg-frame fd-frame${editable ? " fd-editable" : ""}`} role="group" aria-label={label}>
        <ReactFlow<FlowCard | FitAnchor, RoutedEdge | BowedEdge>
          nodes={[...nodes, ...anchors]}
          edges={links}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          fitView
          fitViewOptions={{ minZoom: 0.5, maxZoom: 1.2 }}
          minZoom={0.4}
          maxZoom={2}
          colorMode={scheme}
          // The wheel scrolls the page, as on the rest of it; zoom with the buttons or a pinch.
          zoomOnScroll={false}
          preventScrolling={false}
          nodesDraggable={editable}
          nodesConnectable={editable}
          elementsSelectable={editable}
          edgesFocusable={editable}
          // Removing is done with the buttons under the diagram, which change the YAML.
          deleteKeyCode={null}
          isValidConnection={(c) => c.source !== c.target}
          onConnect={(c) => onConnect?.(c.source, c.target)}
          onPaneClick={() => onSelect?.(null)}
          onNodeDragStart={() => {
            dragging.current = true;
            setMoving(true);
          }}
          onNodeDragStop={(_, __, moved) => {
            const next = { ...where };
            for (const n of moved) next[n.id] = n.position;
            dragging.current = false;
            setMoving(false);
            onMove?.(next);
          }}
          ariaLabelConfig={{
            "node.a11yDescription.default": editable
              ? "A node of the flow. Press Enter to choose it, then the arrow keys to move it; the buttons under the diagram change it."
              : "A node of the flow. The list under the diagram says the same in words.",
          }}
        >
          <Background gap={20} size={1} />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
      <details className="fd-words small">
        <summary>The connections in words</summary>
        {graph.connections.length === 0 ? (
          <p>No connections: every node starts at once.</p>
        ) : (
          <ul>
            {graph.connections.map((c, i) => (
              <li key={i}>
                {connectionSentence(c)}.
                {editable && (
                  <>
                    {" "}
                    <button
                      type="button"
                      className="link-button"
                      aria-label={`Change the connection: ${connectionSentence(c)}`}
                      onClick={() => {
                        onSelect?.({ connection: c.index, from: c.from, to: c.to });
                        // Its settings are in the panel under the diagram: go there, once shown, to change it.
                        window.setTimeout(() => document.getElementById("fd-chosen")?.focus(), 30);
                      }}
                    >
                      Change
                    </button>
                  </>
                )}
              </li>
            ))}
          </ul>
        )}
      </details>
    </>
  );
}
