import { Fragment, type ReactNode } from "react";
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

/** How long a name may be before a tile sets it smaller, and smaller again. */
export const STAT_NAME_LONG = 12;
export const STAT_NAME_LONGER = 24;

/**
 * A name (a node's id, say) as a tile's value. A tile's figures are large, and a name can be 64
 * characters: it is set smaller the longer it is, wraps after _ and - (and anywhere, if one word is
 * still wider than the tile), and never runs past the tile's edge. The full name is its tooltip.
 */
export function StatName({ name }: { name: string }) {
  const size = name.length > STAT_NAME_LONGER ? " km-stat-name--longer" : name.length > STAT_NAME_LONG ? " km-stat-name--long" : "";
  const pieces = name.split(/(?<=[_-])(?=[^_-])/);
  return (
    <span className={`km-stat-name mono${size}`} title={name}>
      {pieces.map((p, i) => (
        <Fragment key={i}>
          {i > 0 && <wbr />}
          {p}
        </Fragment>
      ))}
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
