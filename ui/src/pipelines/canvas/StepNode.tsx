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
  IconDatabase,
  IconDeviceFloppy,
  IconFilter,
  IconGauge,
  IconHourglass,
  IconListNumbers,
  IconMathFunction,
  IconShieldCheck,
  IconSparkles,
  IconSum,
  IconActivity,
  IconTableRow,
} from "@tabler/icons-react";
import { formatNumber, formatScore, type PipelineStepQuality } from "../../api";
import { StatusChip, type Tone } from "../../components/ui";
import { gateWords, scoreTone, scoreWords } from "../quality";
import { ESTIMATE, type PredictedQuality } from "../prediction";
import { rateWords, type LiveStep } from "../live";
import { kindWord, stepLabel } from "./insight";
import type { BadgeRows } from "./layout";
import type { GraphStep } from "./model";

export type StepRunInfo = {
  rowsIn: number | null;
  rowsOut: number | null;
  outcome: string | null;
  error: string | null;
  /** While the run goes: its rate, what it is doing and whether it is holding the run back. */
  live?: Pick<LiveStep, "rate" | "state" | "slowest"> | null;
};

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
  /** After a run: the step's measured quality (writes and gates), or null. */
  quality: PipelineStepQuality | null;
  /** The step's predicted quality as the pipeline stands (writes and gates; an estimate), or null. */
  predicted?: PredictedQuality | null;
  connectable: boolean;
};
export type StepNodeType = Node<StepData, "step">;

const ICONS: Record<string, typeof IconBox> = {
  read: IconDatabase,
  write: IconDeviceFloppy,
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

const LIVE_WORDS: Record<LiveStep["state"], string> = {
  not_started: "Not started",
  waiting: "Waiting for rows",
  running: "Running",
  ended: "Ended",
};

/** In words, for the node's label and the panel. */
export function runWords(run: StepRunInfo): string {
  if (run.live) return liveWords(run).join(" · ");
  const rows = rowsWords(run);
  const outcome = run.outcome ? OUTCOME_WORDS[run.outcome] ?? run.outcome : "";
  return [rows, outcome].filter(Boolean).join(" · ");
}

/** "5,000 rows out", else "4,771 rows in", else nothing. */
const rowsWords = (run: StepRunInfo) =>
  run.rowsOut != null ? `${formatNumber(run.rowsOut)} rows out` : run.rowsIn != null ? `${formatNumber(run.rowsIn)} rows in` : "";

/** While the run goes: "1,200 rows out", "340 rows/s" (or what it is doing), and "Slowest". */
function liveWords(run: StepRunInfo): string[] {
  const l = run.live!;
  const moved = (run.rowsOut ?? 0) > 0 || (run.rowsIn ?? 0) > 0;
  const rows = (run.rowsOut ?? 0) > 0 || run.rowsIn == null ? `${formatNumber(run.rowsOut ?? 0)} rows out` : `${formatNumber(run.rowsIn)} rows in`;
  const doing = l.state === "running" && l.rate != null ? rateWords(l.rate) : LIVE_WORDS[l.state];
  return [moved || l.state === "running" ? rows : "", doing, l.slowest ? "Slowest" : ""].filter(Boolean);
}

/** A score's chip tone, from the same Good / Fair / Poor as its words. */
const TONE: Record<string, Tone> = { good: "good", fair: "warn", poor: "bad", none: "neutral" };

/**
 * The chips under a step after a run and while it is edited, each kind in its own visual
 * language, and each saying what it is in words, so none depends on colour:
 *
 *   rows      neutral (grey), a rows icon: "5,000 rows out · Finished". The run's outcome takes a
 *             status tone only when it is not a success: a separate "Failed" or "Ended early" chip.
 *   measured  solid, a gauge icon, toned by score: "Quality 99% (Good)".
 *   predicted dashed outline in the violet used for AI and estimates, a sparkles icon:
 *             "Predicted ≈ 100%", with "(estimate)" for screen readers and in its title.
 *   gate      plain text, no chip: "4,771 passed · 229 quarantined".
 *
 * Each kind sits on a row of its own (see BadgeRows), so a step's height is known before it is drawn.
 */
export type Chip = {
  kind: "problems" | "rows" | "outcome" | "live" | "slowest" | "measured" | "predicted" | "gate";
  tone: Tone;
  text: string;
  hidden?: string;
  more?: string;
  title?: string;
};

export function stepChips(d: Pick<StepData, "problems" | "run" | "quality" | "predicted">): Chip[][] {
  const { problems, run, quality, predicted } = d;
  const first: Chip[] = [];
  if (problems > 0) first.push({ kind: "problems", tone: "bad", text: `${problems} ${problems === 1 ? "problem" : "problems"}` });
  if (run?.live) {
    // While the run goes: rows so far and the rate in one chip, and the step holding the run back.
    // Short on the chip (a step is narrow): "23,370 out · 12,400 rows/s"; in full in its title and the step's name.
    const [a, b] = liveWords({ ...run, live: { ...run.live, slowest: false } });
    const short = a ? a.replace(/ rows (out|in)$/, " $1") : "";
    first.push({
      kind: "live",
      tone: run.live.state === "running" ? "info" : "neutral",
      text: [short, b].filter(Boolean).join(" · "),
      title: [a, b].filter(Boolean).join(" · "),
    });
    if (run.live.slowest)
      first.push({
        kind: "slowest",
        tone: "orange",
        text: "Slowest",
        title: "Rows are queuing in front of this step while the steps after it keep up: it is holding the run back",
      });
  } else if (run) {
    const ok = run.outcome === "finished";
    const rows = rowsWords(run);
    const outcome = run.outcome ? OUTCOME_WORDS[run.outcome] ?? run.outcome : "";
    if (rows || ok) first.push({ kind: "rows", tone: "neutral", text: ok ? [rows, outcome].filter(Boolean).join(" · ") : rows });
    if (outcome && !ok)
      first.push({ kind: "outcome", tone: run.outcome === "failed" ? "bad" : run.outcome === "ended_early" || run.outcome === "stopped" ? "warn" : "neutral", text: outcome, title: run.error ?? undefined });
  }
  const measured: Chip[] = [];
  if (quality?.measured) {
    measured.push({
      kind: "measured",
      tone: quality.gate?.decision === "stopped" ? "bad" : TONE[scoreTone(quality.measured.overall)],
      text: scoreWords(quality.measured.overall),
      title: quality.note ?? quality.gate?.stopReason ?? undefined,
    });
  } else if (quality && !quality.gate && quality.role === "output") {
    measured.push({ kind: "measured", tone: "neutral", text: "Quality not measured", title: quality.note ?? undefined });
  }
  const estimate: Chip[] = [];
  if (predicted) {
    const n = predicted.unknown.length;
    // Short on the chip (a step is narrow); in full in its title, the step's name and its settings.
    const unknown = n ? ` · ${n} unknown` : "";
    const untold = n ? `; ${n} ${n === 1 ? "thing" : "things"} it could not tell, listed in the step's settings` : "";
    const text = predicted.overall == null ? "Predicted: unknown" : `Predicted ≈ ${formatScore(predicted.overall)}`;
    estimate.push({
      kind: "predicted",
      tone: "violet",
      text,
      hidden: ` ${ESTIMATE}`,
      more: unknown || undefined,
      title: `${predicted.overall == null ? "Predicted quality unknown" : `Predicted quality ${formatScore(predicted.overall)}`} ${ESTIMATE}, from the catalogue's metadata; no data was read${untold}`,
    });
  }
  const gate: Chip[] = quality?.gate ? [{ kind: "gate", tone: quality.gate.decision === "stopped" ? "bad" : "neutral", text: gateWords(quality.gate) }] : [];
  return [first, measured, estimate, gate];
}

/** Which rows of chips a step shows, for its height on the canvas. */
export function badgeRows(d: Pick<StepData, "problems" | "run" | "quality" | "predicted">): BadgeRows {
  const [first, measured, predicted, gate] = stepChips(d);
  return { first: first.length > 0, measured: measured.length > 0, predicted: predicted.length > 0, gate: gate.length > 0 };
}

const CHIP_ICON: Record<Chip["kind"], typeof IconBox> = {
  problems: IconAlertTriangle,
  rows: IconTableRow,
  outcome: IconAlertTriangle,
  live: IconActivity,
  slowest: IconHourglass,
  measured: IconGauge,
  predicted: IconSparkles,
  gate: IconShieldCheck,
};

function ChipView({ c }: { c: Chip }) {
  const Icon = CHIP_ICON[c.kind];
  const icon = <Icon size={13} stroke={2} aria-hidden="true" className="cv-node-chip-icon" />;
  const words = (
    <span className="cv-node-chip-text">
      {c.text}
      {c.hidden && <span className="visually-hidden">{c.hidden}</span>}
      {c.more}
    </span>
  );
  if (c.kind === "gate") {
    return (
      <span className={`cv-node-gate${c.tone === "bad" ? " is-stopped" : ""}`} data-kind="gate" title={c.title}>
        {icon}
        {words}
      </span>
    );
  }
  return (
    <StatusChip
      tone={c.tone}
      outline={c.kind === "predicted"}
      kind={c.kind}
      className={`cv-node-chip${c.kind === "predicted" ? " cv-predicted-badge" : c.kind === "measured" ? " cv-quality" : ""}`}
      title={c.title}
    >
      {icon}
      {words}
    </StatusChip>
  );
}

/**
 * One step on the canvas: its type icon and kind, its id, a line summing up its settings, its
 * named input ports (left) and output ports (right), and rows of chips: its problems or, after a
 * run, its rows and how it ended; its measured quality; its predicted quality; and what a gate did
 * with the rows (see stepChips). The columns leaving it show on hover and focus, and are in its label.
 */
function StepBox({ data, selected }: NodeProps<StepNodeType>) {
  const { step, kind, label, summary, inputs, outputs, problems, run, columns, connectable } = data;
  const chips = stepChips(data).filter((row) => row.length > 0);
  const rows = Math.max(inputs.length, outputs.length, 1);
  return (
    <div
      className={`cv-node cv-kind-${kind ?? "unknown"}${problems ? " has-problem" : ""}${selected ? " is-selected" : ""}${
        run?.outcome === "failed" ? " has-failed" : ""
      }${run?.live?.slowest ? " is-slowest" : ""}`}
    >
      <div className="cv-node-head">
        <span className="cv-node-icon">
          <StepIcon type={step.type} kind={kind} />
        </span>
        <span className="cv-node-kind">
          {kindWord(kind)} · {stepLabel(step, label)}
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
      {chips.length > 0 && (
        <div className="cv-node-foot">
          {chips.map((row) => (
            <div key={row[0].kind} className="cv-node-chips">
              {row.map((c) => (
                <ChipView key={c.kind} c={c} />
              ))}
            </div>
          ))}
        </div>
      )}
      <div className="cv-node-columns small" id={`cv-cols-${step.id}`} aria-hidden="true">
        {columns}
      </div>
    </div>
  );
}

export const StepNode = memo(StepBox);
