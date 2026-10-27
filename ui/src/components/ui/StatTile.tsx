import type { ReactNode } from "react";
import type { Tone } from "./StatusChip";

export type Tint = "sky" | "violet" | "green" | "orange" | "red" | "amber" | "teal" | "pink" | "slate" | "blue";

/** A tinted square holding an icon (or letters): KPI tiles, card headings, list rows. Decorative. */
export function IconTile({ tint, size = "md", children }: { tint: Tint; size?: "sm" | "md" | "lg"; children: ReactNode }) {
  return (
    <span className={`km-icon-tile km-icon-tile--${size} km-tint-${tint}`} aria-hidden="true">
      {children}
    </span>
  );
}

/**
 * A KPI tile: a tinted icon square and label, the value large in Manrope with tabular figures, and
 * a trend or note underneath (with its tone said in words, never by colour alone).
 */
export function StatTile({
  label,
  value,
  icon,
  tint,
  note,
  noteTone,
  href,
}: {
  label: string;
  value: ReactNode;
  icon: ReactNode;
  tint: Tint;
  note?: ReactNode;
  noteTone?: Extract<Tone, "good" | "warn" | "bad" | "neutral">;
  href?: string;
}) {
  const body = (
    <>
      <span className="km-stat-head">
        <IconTile tint={tint}>{icon}</IconTile>
        <span className="km-stat-label">{label}</span>
      </span>
      <span className="km-stat-value num">{value}</span>
      {note && <span className={`km-stat-note km-text-${noteTone ?? "neutral"}`}>{note}</span>}
    </>
  );
  return href ? (
    <a className="km-card km-stat km-stat--link" href={href}>
      {body}
    </a>
  ) : (
    <div className="km-card km-stat">{body}</div>
  );
}
