import { useId, type ReactNode } from "react";

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
}: {
  label: ReactNode;
  help?: ReactNode;
  checked: boolean;
  onChange: (on: boolean) => void;
  disabled?: boolean;
  defaultNote?: ReactNode;
  id?: string;
}) {
  const auto = useId();
  const base = id ?? auto;
  return (
    <div className="km-switch-row">
      <div className="km-switch-text">
        <span id={`${base}-label`} className="km-switch-label">
          {label}
        </span>
        {(help || defaultNote) && (
          <span id={`${base}-help`} className="hint">
            {help}
            {help && defaultNote ? " " : ""}
            {defaultNote}
          </span>
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
        aria-describedby={help || defaultNote ? `${base}-help` : undefined}
        disabled={disabled}
        onClick={() => onChange(!checked)}
      >
        <span className="km-switch-thumb" />
      </button>
    </div>
  );
}
