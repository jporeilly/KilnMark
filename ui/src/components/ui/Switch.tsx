import { useId, useState, type ReactNode } from "react";

/**
 * A real switch for an on/off setting: a button with role="switch" and aria-checked, labelled by
 * its visible label and described by its help text. The state is also written beside it ("On",
 * "Off"), so it never depends on colour or position alone. `defaultNote` says what applies when
 * the setting has not been set ("Default: on").
 */
export function Switch({
  label,
  help,
  checked,
  onChange,
  disabled,
  defaultNote,
  id,
  compact = false,
  describedBy,
}: {
  label: ReactNode;
  help?: ReactNode;
  checked: boolean;
  onChange: (on: boolean) => void;
  disabled?: boolean;
  defaultNote?: ReactNode;
  id?: string;
  /** In a table cell: the label is read out but not shown (the column header says it). */
  compact?: boolean;
  /** An extra hint elsewhere on the page that describes this switch. */
  describedBy?: string;
}) {
  const auto = useId();
  const base = id ?? auto;
  // Long help shows two lines, with More to read the rest (it is read out in full regardless).
  const long = typeof help === "string" && help.length > 110;
  const [more, setMore] = useState(false);
  const described = [help || defaultNote ? `${base}-help` : null, describedBy ?? null].filter(Boolean).join(" ") || undefined;
  if (compact) {
    return (
      <span className="km-switch-compact">
        <span id={`${base}-label`} className="visually-hidden">
          {label}
        </span>
        <button
          type="button"
          id={base}
          role="switch"
          className="km-switch"
          aria-checked={checked}
          aria-labelledby={`${base}-label`}
          aria-describedby={describedBy}
          disabled={disabled}
          onClick={() => onChange(!checked)}
        >
          <span className="km-switch-thumb" />
        </button>
        <span className="km-switch-state" aria-hidden="true">
          {checked ? "On" : "Off"}
        </span>
      </span>
    );
  }
  return (
    <div className="km-switch-row">
      <div className="km-switch-text">
        <span className="km-switch-label">
          <span id={`${base}-label`}>{label}</span>
          {defaultNote && <span className="km-switch-default">{defaultNote}</span>}
        </span>
        {(help || defaultNote) && (
          <span id={`${base}-help`} className={`hint${long && !more ? " km-clamp-2" : ""}`}>
            {help}
            {defaultNote && <span className="visually-hidden"> {defaultNote}</span>}
          </span>
        )}
        {long && (
          <button type="button" className="link-button km-more" aria-expanded={more} onClick={() => setMore(!more)}>
            {more ? "Less" : "More"}
            <span className="visually-hidden"> about {label}</span>
          </button>
        )}
      </div>
      <span className="km-switch-state" aria-hidden="true">
        {checked ? "On" : "Off"}
      </span>
      <button
        type="button"
        id={base}
        role="switch"
        className="km-switch"
        aria-checked={checked}
        aria-labelledby={`${base}-label`}
        aria-describedby={described}
        disabled={disabled}
        onClick={() => onChange(!checked)}
      >
        <span className="km-switch-thumb" />
      </button>
    </div>
  );
}
