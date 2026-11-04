import { useMemo } from "react";
import {
  Background,
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from "@xyflow/react";
import { Graph, layout } from "@dagrejs/dagre";
import "@xyflow/react/dist/style.css";
import type { FlowNodeStatus } from "../api";
import { useColourScheme } from "../theme/appearance";
import { FlowStatus, flowStatusWords } from "./RunFlow";
import { connectionSentence, TYPE_WORDS, whenWords, type ConnectionKind, type FlowGraph, type FlowGraphNode, type Point } from "./graph";
import "./flows.css";

const WIDTH = 220;
const HEIGHT = 74;
const STATUS_ROW = 26;
const LABEL_HEIGHT = 20;

/** About how wide a connection's label is drawn (11px semibold, with its padding). */
const labelWidth = (text: string) => Math.ceil(text.length * 6.6) + 16;

type NodeData = { node: FlowGraphNode; status: FlowNodeStatus | null };
type FlowCard = Node<NodeData, "flowNode">;
/** A connection drawn along the route the layout found, its label where the layout made room for it. */
type RouteData = { points: Point[]; label: string; labelAt: Point; kind: ConnectionKind; full: string };
type RoutedEdge = Edge<RouteData, "routed">;

function FlowNodeBox({ data }: NodeProps<FlowCard>) {
  const { node, status } = data;
  return (
    <div className={`fd-node fd-type-${node.type}${status ? ` fd-status-${status}` : ""}`}>
      <Handle type="target" position={Position.Left} className="pl-handle" />
      <div className="fd-node-head">
        <span className="pl-node-id mono">{node.id}</span>
        <span className="pl-node-type">{TYPE_WORDS[node.type] ?? node.type}</span>
      </div>
      <div className="fd-node-summary small" title={node.summary}>
        {node.summary}
      </div>
      {status && (
        <div className="fd-node-status">
          <FlowStatus status={status} />
        </div>
      )}
      <Handle type="source" position={Position.Right} className="pl-handle" />
    </div>
  );
}

function Routed({ data, markerEnd }: EdgeProps<RoutedEdge>) {
  if (!data) return null;
  const [first, ...rest] = data.points;
  const path = `M ${first.x} ${first.y} ${rest.map((p) => `L ${p.x} ${p.y}`).join(" ")}`;
  return (
    <>
      <BaseEdge path={path} markerEnd={markerEnd} />
      <EdgeLabelRenderer>
        <div
          className={`fd-edge-label fd-edge-label-${data.kind} nodrag nopan`}
          style={{ transform: `translate(-50%, -50%) translate(${data.labelAt.x}px, ${data.labelAt.y}px)` }}
          title={data.full}
        >
          {data.label}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

const nodeTypes = { flowNode: FlowNodeBox };
const edgeTypes = { routed: Routed };

/**
 * A flow as a read-only graph: a box per node with what it does, a labelled arrow per connection
 * (solid on success, dashed on failure, dotted always, and a condition's text), laid out left to
 * right with room for each label, and each connection routed round the nodes it passes. The
 * `layout:` block is not used yet: it is for editing on the canvas, which places nodes by hand.
 * With `statuses`, each node shows how it stands in a run, in words and colour. The list under it
 * says the same in sentences.
 */
export default function FlowDiagram({
  graph,
  statuses,
  label,
}: {
  graph: FlowGraph;
  statuses?: Record<string, FlowNodeStatus>;
  label: string;
}) {
  const scheme = useColourScheme();
  const height = HEIGHT + (statuses ? STATUS_ROW : 0);
  // The layout depends only on the flow: a run's page asks for statuses every two seconds, and
  // only the cards change with them.
  const { where, links } = useMemo(() => {
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
      where[n.id] = { x: p.x - WIDTH / 2, y: p.y - height / 2 };
    }
    const links = graph.connections.map((c, i): RoutedEdge => {
      const e = g.edge({ v: c.from, w: c.to, name: `c${i}` }) as { points: Point[]; x: number; y: number };
      return {
        id: `c${i}`,
        source: c.from,
        target: c.to,
        type: "routed",
        className: `fd-edge fd-edge-${c.kind}`,
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: c.kind === "failure" ? "var(--km-bad)" : c.kind === "condition" ? "var(--km-warn)" : "var(--km-ink)",
        },
        ariaLabel: connectionSentence(c),
        data: { points: e.points, label: whenWords(c), labelAt: { x: e.x, y: e.y }, kind: c.kind, full: c.when },
      };
    });
    return { where, links };
  }, [graph, height]);

  const cards = useMemo(
    () =>
      graph.nodes.map((n): FlowCard => {
        const status = statuses?.[n.id] ?? null;
        return {
          id: n.id,
          type: "flowNode",
          position: where[n.id],
          data: { node: n, status },
          // The routes are fixed by the layout: nodes stay where it put them.
          draggable: false,
          ariaLabel: `Node ${n.id}, ${TYPE_WORDS[n.type] ?? n.type}: ${n.summary}${status ? `. ${flowStatusWords(status)}` : ""}`,
        };
      }),
    [graph, statuses, where],
  );

  return (
    <>
      <div className="dg-frame fd-frame" role="group" aria-label={label}>
        <ReactFlow
          nodes={cards}
          edges={links}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          fitView
          fitViewOptions={{ minZoom: 0.5, maxZoom: 1.2 }}
          minZoom={0.4}
          maxZoom={2}
          colorMode={scheme}
          // The wheel scrolls the page, as on the rest of it; zoom with the buttons or a pinch.
          zoomOnScroll={false}
          preventScrolling={false}
          nodesConnectable={false}
          edgesFocusable={false}
          elementsSelectable={false}
          ariaLabelConfig={{
            "node.a11yDescription.default": "A node of the flow. The list under the diagram says the same in words.",
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
              <li key={i}>{connectionSentence(c)}.</li>
            ))}
          </ul>
        )}
      </details>
    </>
  );
}
