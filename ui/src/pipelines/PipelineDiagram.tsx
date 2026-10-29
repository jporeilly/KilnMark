import { useMemo } from "react";
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from "@xyflow/react";
import { Graph, layout } from "@dagrejs/dagre";
import "@xyflow/react/dist/style.css";
import type { PipelineEdge, PipelineNodeOutline } from "../api";
import { useColourScheme } from "../theme/appearance";
import { columnsText } from "./outline";

const WIDTH = 230;
const HEADER = 52;
const PORT = 20;

type NodeData = { node: PipelineNodeOutline; problem: boolean };
type NodeCard = Node<NodeData, "node">;

function NodeBox({ data }: NodeProps<NodeCard>) {
  const { node, problem } = data;
  const rows = Math.max(node.inputs.length, node.outputs.length, 1);
  return (
    <div className={`pl-node pl-kind-${node.kind ?? "unknown"}${problem ? " has-problem" : ""}`}>
      <div className="pl-node-head">
        <span className="pl-node-id mono">{node.id}</span>
        <span className="pl-node-type">
          {node.type ?? "no type"}
          {problem && " · has a problem"}
        </span>
      </div>
      <div className="pl-ports" style={{ minHeight: rows * PORT }}>
        <ul className="pl-port-list">
          {node.inputs.map((p) => (
            <li key={p} className="pl-port pl-port-in">
              <Handle type="target" position={Position.Left} id={`in-${p}`} className="pl-handle" />
              {p}
            </li>
          ))}
        </ul>
        <ul className="pl-port-list pl-port-list-out">
          {node.outputs.map((p) => (
            <li key={p} className="pl-port pl-port-out">
              {p}
              <Handle type="source" position={Position.Right} id={`out-${p}`} className="pl-handle" />
            </li>
          ))}
        </ul>
      </div>
      <div className="pl-node-columns small">
        {columnsText(node)}
      </div>
    </div>
  );
}

const nodeTypes = { node: NodeBox };

/**
 * The pipeline's nodes as a read-only graph: a box per node with its input ports on the left and
 * output ports on the right, edges from port to port (labelled when they use a port other than
 * out and in), laid out left to right with dagre. Hovering or focusing a node shows the columns
 * leaving it; its label carries them too. The nodes table beside it says the same in text.
 */
export default function PipelineDiagram({
  nodes,
  edges,
  problemNodes,
}: {
  nodes: PipelineNodeOutline[];
  edges: PipelineEdge[];
  problemNodes: Set<string>;
}) {
  const scheme = useColourScheme();
  const { flowNodes, links } = useMemo(() => {
    const g = new Graph();
    g.setGraph({ rankdir: "LR", nodesep: 30, ranksep: 80, marginx: 16, marginy: 16 });
    g.setDefaultEdgeLabel(() => ({}));
    const ids = new Set(nodes.map((s) => s.id));
    for (const s of nodes) {
      g.setNode(s.id, { width: WIDTH, height: HEADER + PORT * Math.max(s.inputs.length, s.outputs.length, 1) + 12 });
    }
    const drawn = edges.filter((e) => ids.has(e.fromNode) && ids.has(e.toNode));
    for (const e of drawn) g.setEdge(e.fromNode, e.toNode);
    layout(g);
    const flowNodes: NodeCard[] = nodes.map((s) => {
      const n = g.node(s.id);
      return {
        id: s.id,
        type: "node",
        position: { x: n.x - n.width / 2, y: n.y - n.height / 2 },
        data: { node: s, problem: problemNodes.has(s.id) },
        draggable: true,
        ariaLabel: `Node ${s.id}, ${s.type ?? "no type"}${problemNodes.has(s.id) ? ", has a problem" : ""}. ${columnsText(s)}`,
      };
    });
    const links: Edge[] = drawn.map((e, i) => {
      const plain = e.fromPort === "out" && e.toPort === "in";
      return {
        id: `e${i}`,
        source: e.fromNode,
        target: e.toNode,
        sourceHandle: `out-${e.fromPort}`,
        targetHandle: `in-${e.toPort}`,
        type: "smoothstep",
        label: plain ? undefined : e.toPort === "in" ? e.fromPort : `${e.fromPort} → ${e.toPort}`,
        className: e.fromPort === "out" ? "pl-edge" : "pl-edge pl-edge-other",
        style: e.fromPort === "out" ? undefined : { strokeDasharray: "6 4" },
        markerEnd: { type: MarkerType.ArrowClosed, color: "var(--km-ink)" },
        ariaLabel: `${e.fromNode}.${e.fromPort} feeds ${e.toNode}.${e.toPort}`,
      };
    });
    return { flowNodes, links };
  }, [nodes, edges, problemNodes]);

  return (
    <div className="dg-frame pl-frame" role="group" aria-label="Graph of the pipeline's nodes">
      <ReactFlow
        nodes={flowNodes}
        edges={links}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ minZoom: 0.6 }}
        minZoom={0.6}
        maxZoom={2}
        colorMode={scheme}
        nodesConnectable={false}
        edgesFocusable={false}
        elementsSelectable={false}
        proOptions={{ hideAttribution: false }}
        ariaLabelConfig={{
          "node.a11yDescription.default":
            "A node of the pipeline. Its label lists the columns leaving it. The nodes table beside the graph says the same.",
        }}
      >
        <Background gap={20} size={1} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
