import { memo } from "react";
import { Handle, Position, type Node, type NodeProps } from "@xyflow/react";
import {
  IconAlertTriangle,
  IconArrowMerge,
  IconArrowsJoin,
  IconArrowsSplit,
  IconBox,
  IconColumns,
  IconCopyOff,
  IconDatabaseExport,
  IconDatabaseImport,
  IconFilter,
  IconListNumbers,
  IconMathFunction,
  IconShieldCheck,
  IconSum,
} from "@tabler/icons-react";
import { formatNumber } from "../../api";
import { kindWord } from "./insight";
import type { GraphStep } from "./model";

export type StepRunInfo = { rowsIn: number | null; rowsOut: number | null; outcome: string | null; error: string | null };

export type StepData = {
  step: GraphStep;
  kind: string | null;
  label: string;
  summary: string;
  inputs: string[];
  outputs: string[];
  problems: number;
  columns: string;
  run: StepRunInfo | null;
  connectable: boolean;
};
export type StepNodeType = Node<StepData, "step">;

const ICONS: Record<string, typeof IconBox> = {
  read: IconDatabaseImport,
  write: IconDatabaseExport,
  filter: IconFilter,
  compute: IconMathFunction,
  select: IconColumns,
  lookup: IconArrowsJoin,
  aggregate: IconSum,
  union: IconArrowMerge,
  dedupe: IconCopyOff,
  route: IconArrowsSplit,
  limit: IconListNumbers,
  gate: IconShieldCheck,
};

export function StepIcon({ type, kind, size = 18 }: { type: string | null; kind?: string | null; size?: number }) {
  const Icon = (type && ICONS[type]) || (kind === "gate" ? IconShieldCheck : IconBox);
  return <Icon size={size} stroke={1.8} aria-hidden="true" />;
}

export const OUTCOME_WORDS: Record<string, string> = {
  finished: "Finished",
  ended_early: "Ended early",
  stopped: "Stopped by the engine",
  failed: "Failed",
};

/** In words, for the node's label and the panel. */
export function runWords(run: StepRunInfo): string {
  const rows = run.rowsOut != null ? `${formatNumber(run.rowsOut)} rows out` : run.rowsIn != null ? `${formatNumber(run.rowsIn)} rows in` : "";
  const outcome = run.outcome ? OUTCOME_WORDS[run.outcome] ?? run.outcome : "";
  return [rows, outcome].filter(Boolean).join(" · ");
}

/**
 * One step on the canvas: its type icon and kind, its id, a line summing up its settings, its
 * named input ports (left) and output ports (right), and a badge with its problems or, after a run,
 * its rows and how it ended. The columns leaving it show on hover and focus, and are in its label.
 */
function StepBox({ data, selected }: NodeProps<StepNodeType>) {
  const { step, kind, label, summary, inputs, outputs, problems, run, columns, connectable } = data;
  const rows = Math.max(inputs.length, outputs.length, 1);
  return (
    <div
      className={`cv-node cv-kind-${kind ?? "unknown"}${problems ? " has-problem" : ""}${selected ? " is-selected" : ""}${
        run?.outcome === "failed" ? " has-failed" : ""
      }`}
    >
      <div className="cv-node-head">
        <span className="cv-node-icon">
          <StepIcon type={step.type} kind={kind} />
        </span>
        <span className="cv-node-kind">
          {kindWord(kind)} · {label}
        </span>
      </div>
      <div className="cv-node-id mono">{step.id}</div>
      <div className="cv-node-summary" title={summary || undefined}>
        {summary || <span className="muted">No settings yet</span>}
      </div>
      <div className="cv-node-ports" style={{ minHeight: rows * 22 }}>
        <ul className="cv-port-list">
          {inputs.map((p) => (
            <li key={p} className="cv-port cv-port-in">
              <Handle
                type="target"
                position={Position.Left}
                id={`in:${p}`}
                className="cv-handle"
                isConnectable={connectable}
              />
              {p}
            </li>
          ))}
        </ul>
        <ul className="cv-port-list cv-port-list-out">
          {outputs.map((p) => (
            <li key={p} className="cv-port cv-port-out">
              {p}
              <Handle
                type="source"
                position={Position.Right}
                id={`out:${p}`}
                className="cv-handle"
                isConnectable={connectable}
              />
            </li>
          ))}
        </ul>
      </div>
      {(problems > 0 || run) && (
        <div className="cv-node-foot">
          {problems > 0 && (
            <span className="cv-badge cv-badge-bad">
              <IconAlertTriangle size={14} stroke={2} aria-hidden="true" />
              {problems} {problems === 1 ? "problem" : "problems"}
            </span>
          )}
          {run && (
            <span className={`cv-badge cv-run-${run.outcome ?? "unknown"}`} title={run.error ?? undefined}>
              {runWords(run)}
            </span>
          )}
        </div>
      )}
      <div className="cv-node-columns small" id={`cv-cols-${step.id}`} aria-hidden="true">
        {columns}
      </div>
    </div>
  );
}

export const StepNode = memo(StepBox);
