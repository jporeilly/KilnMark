import type { ReactNode } from "react";

/** Tones map to the tints in tokens.css. Status is always said in words; the dot is extra. */
export type Tone = "good" | "warn" | "bad" | "info" | "neutral" | "orange" | "violet" | "sky" | "accent";

/**
 * A pill with a tinted background and dark text: run and rule status, severity, roles. `dot` adds
 * a small dot before the text; `upper` is the small uppercase label used for severity; `outline`
 * adds a dashed edge in the text colour, for something that is an estimate rather than measured.
 * `className` and `kind` (a data-kind attribute) let a page style and find its own chips.
 */
export function StatusChip({
  tone = "neutral",
  dot = false,
  upper = false,
  outline = false,
  className,
  kind,
  children,
  title,
}: {
  tone?: Tone;
  dot?: boolean;
  upper?: boolean;
  outline?: boolean;
  className?: string;
  kind?: string;
  children: ReactNode;
  title?: string;
}) {
  return (
    <span
      className={`km-chip km-chip--${tone}${upper ? " km-chip--upper" : ""}${outline ? " km-chip--outline" : ""}${className ? ` ${className}` : ""}`}
      title={title}
      data-kind={kind}
    >
      {dot && <span className="km-chip-dot" aria-hidden="true" />}
      {children}
    </span>
  );
}

const SEVERITY_TONE: Record<string, Tone> = { high: "bad", medium: "orange", low: "neutral", info: "info" };
const SEVERITY_WORD: Record<string, string> = { high: "High", medium: "Medium", low: "Low", info: "Info" };

/** Severity of an issue, drift or notification: High, Medium, Low or Info, as words. */
export function SeverityChip({ severity }: { severity: string }) {
  return (
    <StatusChip tone={SEVERITY_TONE[severity] ?? "neutral"} upper>
      {SEVERITY_WORD[severity] ?? severity}
    </StatusChip>
  );
}
