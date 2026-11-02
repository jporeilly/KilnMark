import { useMemo, useState } from "react";
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
import "@xyflow/react/dist/style.css";
import { layoutBoxes } from "./diagramLayout";
import { formatNumber } from "../api";
import { go, href } from "../router";
import type { DiagramTable, Relationship } from "./browseApi";
import { formatLabel } from "./icons";
import { useColourScheme } from "../theme/appearance";

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
 * Laid out to fill the frame (diagramLayout.ts); pan, zoom and fit with the controls.
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
  const scheme = useColourScheme();
  // The table pointed at, or pinned with a click: its relationships stand out and the rest fade.
  const [hovered, setHovered] = useState<string | null>(null);
  const [pinned, setPinned] = useState<string | null>(null);
  const focus = pinned ?? hovered;
  const { nodes, edges } = useMemo(() => {
    const boxes = new Map(tables.map((t) => [t.urn, { table: t, ...visibleColumns(t) }]));
    const drawn = relationships.filter((r) => r.status !== "rejected" && boxes.has(r.from.urn) && boxes.has(r.to.urn));

    const at = layoutBoxes(
      [...boxes.entries()].map(([urn, b]) => ({
        id: urn,
        width: WIDTH,
        height: HEADER + ROW * (b.columns.length + (b.hidden > 0 ? 1 : 0)) + 8,
      })),
      drawn.map((r): [string, string] => [r.from.urn, r.to.urn]),
    );

    const nodes: TableNode[] = [...boxes.entries()].map(([urn, b]) => {
      return {
        id: urn,
        type: "table",
        position: at.get(urn) ?? { x: 0, y: 0 },
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
      // The confidence as a pill in the line's colour: a tick for accepted, a question mark for proposed.
      const text =
        r.kind === "declared" ? undefined : `${proposed ? "?" : "✓"} ${r.confidence != null ? percent.format(r.confidence) : ""}`.trim();
      // The badges' tints, whose contrast the theme check covers, edged in the line's colour.
      const tint = proposed ? "amber" : "green";
      return {
        id: `r${r.id}`,
        source: r.from.urn,
        target: r.to.urn,
        sourceHandle: handle(r.from.urn, r.from.fields[0], "out"),
        targetHandle: handle(r.to.urn, r.to.fields[0], "in"),
        // Curves rather than steps: steps share the same lanes and lie on top of each other.
        type: "default",
        label: text,
        labelStyle: { fill: `var(--km-tint-${tint}-fg)`, fontWeight: 700, fontSize: 12 },
        labelBgStyle: {
          fill: `var(--km-tint-${tint}-bg)`,
          fillOpacity: 1,
          stroke: proposed ? "var(--km-warn)" : "var(--km-good)",
          strokeWidth: 1,
        },
        labelBgPadding: [6, 3] as [number, number],
        labelBgBorderRadius: 10,
        className: `dg-edge dg-edge-${r.kind} dg-edge-${r.status}`,
        style: proposed ? { strokeDasharray: "6 4" } : undefined,
        // Arrowheads take the edge's colour from the tokens (xyflow's default grey is too faint).
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: proposed
            ? "var(--km-warn)"
            : r.kind === "declared"
              ? "var(--km-ink)"
              : r.status === "accepted"
                ? "var(--km-good)"
                : "var(--km-muted)",
        },
        ariaLabel: `${r.from.name} (${r.from.fields.join(", ")}) refers to ${r.to.name} (${r.to.fields.join(", ")}), ${
          r.kind
        }, ${r.status}`,
      };
    });
    return { nodes, edges };
  }, [tables, relationships, centre]);

  const shownEdges = useMemo(
    () =>
      focus == null
        ? edges
        : edges.map((e) => {
            const near = e.source === focus || e.target === focus;
            return { ...e, className: `${e.className}${near ? " is-near" : " is-faded"}`, zIndex: near ? 10 : 0 };
          }),
    [edges, focus],
  );

  return (
    <div className="dg-frame" role="group" aria-label={label}>
      <ReactFlow
        nodes={nodes}
        edges={shownEdges}
        nodeTypes={nodeTypes}
        fitView
        minZoom={0.1}
        maxZoom={2}
        colorMode={scheme}
        nodesConnectable={false}
        edgesFocusable={false}
        elementsSelectable={false}
        onNodeDoubleClick={(_, n) => go(href.asset(n.id))}
        onNodeMouseEnter={(_, n) => setHovered(n.id)}
        onNodeMouseLeave={() => setHovered(null)}
        onNodeClick={(_, n) => setPinned((p) => (p === n.id ? null : n.id))}
        onPaneClick={() => setPinned(null)}
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
