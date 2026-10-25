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
import type { PipelineEdge, PipelineStepOutline } from "../api";
import { useColourScheme } from "../theme/appearance";
import { columnsText } from "./outline";

const WIDTH = 230;
const HEADER = 52;
const PORT = 20;

type StepData = { step: PipelineStepOutline; problem: boolean };
type StepNode = Node<StepData, "step">;

function StepBox({ data }: NodeProps<StepNode>) {
  const { step, problem } = data;
  const rows = Math.max(step.inputs.length, step.outputs.length, 1);
  return (
    <div className={`pl-step pl-kind-${step.kind ?? "unknown"}${problem ? " has-problem" : ""}`}>
      <div className="pl-step-head">
        <span className="pl-step-id mono">{step.id}</span>
        <span className="pl-step-type">
          {step.type ?? "no type"}
          {problem && " · has a problem"}
        </span>
      </div>
      <div className="pl-ports" style={{ minHeight: rows * PORT }}>
        <ul className="pl-port-list">
          {step.inputs.map((p) => (
            <li key={p} className="pl-port pl-port-in">
              <Handle type="target" position={Position.Left} id={`in-${p}`} className="pl-handle" />
              {p}
            </li>
          ))}
        </ul>
        <ul className="pl-port-list pl-port-list-out">
          {step.outputs.map((p) => (
            <li key={p} className="pl-port pl-port-out">
              {p}
              <Handle type="source" position={Position.Right} id={`out-${p}`} className="pl-handle" />
            </li>
          ))}
        </ul>
      </div>
      <div className="pl-step-columns small" role="tooltip">
        {columnsText(step)}
      </div>
    </div>
  );
}

const nodeTypes = { step: StepBox };

/**
 * The pipeline's steps as a read-only graph: a box per step with its input ports on the left and
 * output ports on the right, edges from port to port (labelled when they use a port other than
 * out and in), laid out left to right with dagre. Hovering or focusing a step shows the columns
 * leaving it; its label carries them too. The steps table beside it says the same in text.
 */
export default function PipelineDiagram({
  steps,
  edges,
  problemSteps,
}: {
  steps: PipelineStepOutline[];
  edges: PipelineEdge[];
  problemSteps: Set<string>;
}) {
  const scheme = useColourScheme();
  const { nodes, links } = useMemo(() => {
    const g = new Graph();
    g.setGraph({ rankdir: "LR", nodesep: 30, ranksep: 80, marginx: 16, marginy: 16 });
    g.setDefaultEdgeLabel(() => ({}));
    const ids = new Set(steps.map((s) => s.id));
    for (const s of steps) {
      g.setNode(s.id, { width: WIDTH, height: HEADER + PORT * Math.max(s.inputs.length, s.outputs.length, 1) + 12 });
    }
    const drawn = edges.filter((e) => ids.has(e.fromStep) && ids.has(e.toStep));
    for (const e of drawn) g.setEdge(e.fromStep, e.toStep);
    layout(g);
    const nodes: StepNode[] = steps.map((s) => {
      const n = g.node(s.id);
      return {
        id: s.id,
        type: "step",
        position: { x: n.x - n.width / 2, y: n.y - n.height / 2 },
        data: { step: s, problem: problemSteps.has(s.id) },
        draggable: true,
        ariaLabel: `Step ${s.id}, ${s.type ?? "no type"}${problemSteps.has(s.id) ? ", has a problem" : ""}. ${columnsText(s)}`,
      };
    });
    const links: Edge[] = drawn.map((e, i) => {
      const plain = e.fromPort === "out" && e.toPort === "in";
      return {
        id: `e${i}`,
        source: e.fromStep,
        target: e.toStep,
        sourceHandle: `out-${e.fromPort}`,
        targetHandle: `in-${e.toPort}`,
        type: "smoothstep",
        label: plain ? undefined : e.toPort === "in" ? e.fromPort : `${e.fromPort} → ${e.toPort}`,
        className: e.fromPort === "out" ? "pl-edge" : "pl-edge pl-edge-other",
        style: e.fromPort === "out" ? undefined : { strokeDasharray: "6 4" },
        markerEnd: { type: MarkerType.ArrowClosed, color: "var(--km-ink)" },
        ariaLabel: `${e.fromStep}.${e.fromPort} feeds ${e.toStep}.${e.toPort}`,
      };
    });
    return { nodes, links };
  }, [steps, edges, problemSteps]);

  return (
    <div className="dg-frame pl-frame" role="group" aria-label="Graph of the pipeline's steps">
      <ReactFlow
        nodes={nodes}
        edges={links}
        nodeTypes={nodeTypes}
        fitView
        minZoom={0.2}
        maxZoom={2}
        colorMode={scheme}
        nodesConnectable={false}
        edgesFocusable={false}
        elementsSelectable={false}
        proOptions={{ hideAttribution: false }}
        ariaLabelConfig={{
          "node.a11yDescription.default":
            "A step of the pipeline. Its label lists the columns leaving it. The steps table beside the graph says the same.",
        }}
      >
        <Background gap={20} size={1} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
