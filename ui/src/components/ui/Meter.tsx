export type FillTone = "accent" | "sky" | "violet" | "green" | "amber" | "red" | "good" | "warn" | "bad";

/**
 * A thin bar: a track with a fill. Decorative (aria-hidden): the value is always written next to
 * it as text. Fills are checked at 3:1 against the track (check-contrast.mjs). `gradient` is the
 * accent-to-amber fill used for progress.
 */
export function Meter({
  value,
  tone = "accent",
  size = "md",
  gradient = false,
  width,
}: {
  value: number;
  tone?: FillTone;
  size?: "sm" | "md" | "lg";
  gradient?: boolean;
  width?: number | string;
}) {
  const pct = Math.max(0, Math.min(1, value)) * 100;
  return (
    <span className={`km-meter km-meter--${size}`} style={width != null ? { width } : undefined} aria-hidden="true">
      <span className={`km-meter-fill km-fill-${tone}${gradient ? " km-meter-fill--gradient" : ""}`} style={{ width: `${pct}%` }} />
    </span>
  );
}

/** The overall score ring: an accent arc on a track, the percentage in the middle. */
export function ScoreRing({ value, size = 78, label }: { value: number; size?: number; label: string }) {
  const r = size / 2 - 7;
  const c = 2 * Math.PI * r;
  const pct = Math.round(Math.max(0, Math.min(1, value)) * 100);
  return (
    <svg className="km-ring" width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={label}>
      <circle className="km-ring-track" cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={9} />
      <circle
        className="km-ring-fill"
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        strokeWidth={9}
        strokeLinecap="round"
        strokeDasharray={`${(c * pct) / 100} ${c}`}
        transform={`rotate(-90 ${size / 2} ${size / 2})`}
      />
      <text x="50%" y="50%" dy="0.35em" textAnchor="middle" className="km-ring-text">
        {pct}%
      </text>
    </svg>
  );
}
