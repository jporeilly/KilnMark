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
import { formatNumber } from "../api";
import { go, href } from "../router";
import type { DiagramTable, Relationship } from "./browseApi";
import { formatLabel } from "./icons";

/** Columns a box lists before "and N more"; key columns are always listed. */
const MAX_COLUMNS = 12;
const WIDTH = 260;
const HEADER = 44;
const ROW = 22;

type TableData = { table: DiagramTable; columns: DiagramTable["columns"]; hidden: number; centre: boolean };
type TableNode = Node<TableData, "table">;

const percent = new Intl.NumberFormat("en-GB", { style: "percent", maximumFractionDigits: 0 });

/** The columns a box shows: keys first among the first rows, then the rest up to the limit. */
function visibleColumns(t: DiagramTable) {
  const keys = t.columns.filter((c) => c.primaryKey || c.foreignKey);
  const rest = t.columns.filter((c) => !c.primaryKey && !c.foreignKey);
  const shown = [...keys, ...rest.slice(0, Math.max(0, MAX_COLUMNS - keys.length))];
  const order = new Map(t.columns.map((c, i) => [c.name, i]));
  shown.sort((a, b) => (order.get(a.name) ?? 0) - (order.get(b.name) ?? 0));
  return { columns: shown, hidden: t.columns.length - shown.length };
}

function TableBox({ data }: NodeProps<TableNode>) {
  const { table, columns, hidden, centre } = data;
  return (
    <div className={`dg-table${table.external ? " is-external" : ""}${centre ? " is-centre" : ""}`}>
      <Handle type="target" position={Position.Left} id="in" className="dg-handle" />
      <Handle type="source" position={Position.Right} id="out" className="dg-handle" />
      <div className="dg-head">
        <a href={href.asset(table.urn)} className="dg-name mono nodrag">
          {table.name}
        </a>
        <span className="dg-sub">
          {table.external || centre ? `${table.source}: ${table.container || "(top level)"} · ` : ""}
          {formatLabel(table.format)}
          {table.rowCount != null ? ` · ${formatNumber(table.rowCount)} rows` : ""}
        </span>
      </div>
      <ul className="dg-cols">
        {columns.map((c) => (
          <li key={c.name} className="dg-col">
            <Handle type="target" position={Position.Left} id={`in-${c.name}`} className="dg-handle" />
            <span className="dg-keys">
              {c.primaryKey && <abbr title="Primary key">PK</abbr>}
              {c.foreignKey && <abbr title="Refers to another table">FK</abbr>}
            </span>
            <span className="dg-col-name mono">{c.name}</span>
            <span className="dg-col-type mono">{c.sourceType}</span>
            <Handle type="source" position={Position.Right} id={`out-${c.name}`} className="dg-handle" />
          </li>
        ))}
        {hidden > 0 && <li className="dg-col dg-more">and {hidden} more columns</li>}
      </ul>
    </div>
  );
}

const nodeTypes = { table: TableBox };

/**
 * A schema diagram: tables as boxes with their columns (keys marked PK and FK), relationships as
 * lines from the referring column to the referred one. Declared keys are solid, proposed ones
 * dashed with their confidence, accepted ones solid and labelled; rejected ones are not drawn.
 * Laid out left to right with dagre; pan, zoom and fit with the controls.
 */
export default function SchemaDiagram({
  tables,
  relationships,
  centre,
  label,
}: {
  tables: DiagramTable[];
  relationships: Relationship[];
  centre?: string;
  label: string;
}) {
  const { nodes, edges } = useMemo(() => {
    const boxes = new Map(tables.map((t) => [t.urn, { table: t, ...visibleColumns(t) }]));
    const drawn = relationships.filter((r) => r.status !== "rejected" && boxes.has(r.from.urn) && boxes.has(r.to.urn));

    const g = new Graph();
    g.setGraph({ rankdir: "LR", nodesep: 28, ranksep: 90, marginx: 16, marginy: 16 });
    g.setDefaultEdgeLabel(() => ({}));
    for (const [urn, b] of boxes) {
      g.setNode(urn, { width: WIDTH, height: HEADER + ROW * (b.columns.length + (b.hidden > 0 ? 1 : 0)) + 8 });
    }
    for (const r of drawn) {
      if (r.from.urn !== r.to.urn) g.setEdge(r.from.urn, r.to.urn);
    }
    layout(g);

    const nodes: TableNode[] = [...boxes.entries()].map(([urn, b]) => {
      const n = g.node(urn);
      return {
        id: urn,
        type: "table",
        position: { x: n.x - n.width / 2, y: n.y - n.height / 2 },
        data: { table: b.table, columns: b.columns, hidden: b.hidden, centre: urn === centre },
        draggable: true,
        ariaLabel: `${b.table.name}, ${formatLabel(b.table.format)}, ${b.table.columns.length} columns`,
      };
    });
    const handle = (urn: string, field: string, side: "in" | "out") => {
      const b = boxes.get(urn);
      return b?.columns.some((c) => c.name === field) ? `${side}-${field}` : side;
    };
    const edges: Edge[] = drawn.map((r) => {
      const proposed = r.status === "proposed";
      const text =
        r.kind === "declared"
          ? undefined
          : proposed
            ? `proposed ${r.confidence != null ? percent.format(r.confidence) : ""}`.trim()
            : "accepted";
      return {
        id: `r${r.id}`,
        source: r.from.urn,
        target: r.to.urn,
        sourceHandle: handle(r.from.urn, r.from.fields[0], "out"),
        targetHandle: handle(r.to.urn, r.to.fields[0], "in"),
        type: "smoothstep",
        label: text,
        className: `dg-edge dg-edge-${r.kind} dg-edge-${r.status}`,
        style: proposed ? { strokeDasharray: "6 4" } : undefined,
        markerEnd: { type: MarkerType.ArrowClosed },
        ariaLabel: `${r.from.name} (${r.from.fields.join(", ")}) refers to ${r.to.name} (${r.to.fields.join(", ")}), ${
          r.kind
        }, ${r.status}`,
      };
    });
    return { nodes, edges };
  }, [tables, relationships, centre]);

  return (
    <div className="dg-frame" role="group" aria-label={label}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        fitView
        minZoom={0.1}
        maxZoom={2}
        colorMode="system"
        nodesConnectable={false}
        edgesFocusable={false}
        elementsSelectable={false}
        onNodeDoubleClick={(_, n) => go(href.asset(n.id))}
        proOptions={{ hideAttribution: false }}
        ariaLabelConfig={{
          "node.a11yDescription.default":
            "A table. Tab to its name and press Enter to open it. The same relationships are listed in the table below the diagram.",
        }}
      >
        <Background gap={20} size={1} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
