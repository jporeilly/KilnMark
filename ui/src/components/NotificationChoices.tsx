import type { KindChoice, NotificationKindId, NotificationSeverity, SubscriptionInput } from "../api";

/** What the email part of a subscription holds, without the address and the on/off switch. */
export type EmailChoices = Pick<SubscriptionInput, "kinds" | "minSeverity" | "mode" | "digestHour" | "sources">;

const SEVERITIES: { value: NotificationSeverity; label: string }[] = [
  { value: "high", label: "High only" },
  { value: "medium", label: "Medium and high" },
  { value: "low", label: "Low, medium and high" },
  { value: "info", label: "Everything, including recoveries shown as information" },
];

const hour = (h: number) => `${String(h).padStart(2, "0")}:00`;

/** Kinds of event as tick boxes, in one group with a legend. */
export function KindBoxes({
  idPrefix,
  legend,
  hint,
  choices,
  value,
  onChange,
  disabled,
}: {
  idPrefix: string;
  legend: string;
  hint?: string;
  choices: KindChoice[];
  value: NotificationKindId[];
  onChange: (kinds: NotificationKindId[]) => void;
  disabled?: boolean;
}) {
  const toggle = (id: NotificationKindId, on: boolean) =>
    onChange(on ? [...value.filter((k) => k !== id), id] : value.filter((k) => k !== id));
  return (
    <fieldset className="group" disabled={disabled} aria-describedby={hint ? `${idPrefix}-hint` : undefined}>
      <legend>{legend}</legend>
      {hint && (
        <p id={`${idPrefix}-hint`} className="hint">
          {hint}
        </p>
      )}
      {choices.map((k) => (
        <label key={k.id} className="choice">
          <input
            type="checkbox"
            checked={value.includes(k.id)}
            onChange={(e) => toggle(k.id, e.target.checked)}
            aria-describedby={`${idPrefix}-${k.id}-hint`}
          />
          <span>
            {k.label}
            <span id={`${idPrefix}-${k.id}-hint`} className="hint choice-hint">
              {k.description}
            </span>
          </span>
        </label>
      ))}
    </fieldset>
  );
}

/**
 * The email choices shared by a person's settings and a team address: which kinds, the least
 * severity, at once or a daily digest at an hour (server time zone), and every source or some.
 */
export function EmailChoiceFields({
  idPrefix,
  value,
  onChange,
  kindChoices,
  sources,
  timeZone,
  disabled,
}: {
  idPrefix: string;
  value: EmailChoices;
  onChange: (v: EmailChoices) => void;
  kindChoices: KindChoice[];
  sources: { name: string; displayName: string }[];
  timeZone: string;
  disabled?: boolean;
}) {
  const set = (patch: Partial<EmailChoices>) => onChange({ ...value, ...patch });
  const chosen = value.sources ?? [];
  return (
    <>
      <KindBoxes
        idPrefix={`${idPrefix}-kinds`}
        legend="Kinds of event by email"
        choices={kindChoices}
        value={value.kinds}
        onChange={(kinds) => set({ kinds })}
        disabled={disabled}
      />
      <div className="field">
        <label htmlFor={`${idPrefix}-severity`}>Least severity by email</label>
        <select
          id={`${idPrefix}-severity`}
          value={value.minSeverity}
          disabled={disabled}
          onChange={(e) => set({ minSeverity: e.target.value as NotificationSeverity })}
        >
          {SEVERITIES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>
      <fieldset className="group" disabled={disabled}>
        <legend>When emails go</legend>
        <label className="choice">
          <input
            type="radio"
            name={`${idPrefix}-mode`}
            checked={value.mode === "immediate"}
            onChange={() => set({ mode: "immediate" })}
          />
          <span>Each one as it happens</span>
        </label>
        <label className="choice">
          <input
            type="radio"
            name={`${idPrefix}-mode`}
            checked={value.mode === "digest"}
            onChange={() => set({ mode: "digest" })}
          />
          <span>One email a day, a digest of everything since the last one</span>
        </label>
        <div className="field">
          <label htmlFor={`${idPrefix}-hour`}>Digest time</label>
          <select
            id={`${idPrefix}-hour`}
            value={value.digestHour}
            disabled={disabled || value.mode !== "digest"}
            onChange={(e) => set({ digestHour: Number(e.target.value) })}
            aria-describedby={`${idPrefix}-hour-hint`}
          >
            {Array.from({ length: 24 }, (_, h) => (
              <option key={h} value={h}>
                {hour(h)}
              </option>
            ))}
          </select>
          <p id={`${idPrefix}-hour-hint`} className="hint">
            In the server's time zone, {timeZone}, not necessarily yours. Emails over the hourly limit also wait for
            this time.
          </p>
        </div>
      </fieldset>
      <fieldset className="group" disabled={disabled}>
        <legend>Sources</legend>
        <label className="choice">
          <input
            type="radio"
            name={`${idPrefix}-scope`}
            checked={value.sources === null}
            onChange={() => set({ sources: null })}
          />
          <span>Every source, including ones added later</span>
        </label>
        <label className="choice">
          <input
            type="radio"
            name={`${idPrefix}-scope`}
            checked={value.sources !== null}
            onChange={() => set({ sources: chosen.length ? chosen : sources.slice(0, 1).map((s) => s.name) })}
            disabled={sources.length === 0}
          />
          <span>Only the sources ticked below</span>
        </label>
        {value.sources !== null && (
          <div role="group" aria-label="Sources to hear about">
            {sources.map((s) => (
              <label key={s.name} className="choice">
                <input
                  type="checkbox"
                  checked={chosen.includes(s.name)}
                  onChange={(e) =>
                    set({ sources: e.target.checked ? [...chosen, s.name] : chosen.filter((n) => n !== s.name) })
                  }
                />
                <span>
                  {s.displayName}
                  {s.displayName !== s.name && <span className="muted small"> ({s.name})</span>}
                </span>
              </label>
            ))}
          </div>
        )}
        <p className="hint">The sources apply to notifications in Kilnmark too. Pipeline events are not tied to a source.</p>
      </fieldset>
    </>
  );
}

/** The least severity as a phrase for summaries: "high only", "medium and high", ... */
export const severityPhrase = (s: NotificationSeverity) =>
  ({ high: "high only", medium: "medium and high", low: "low, medium and high", info: "everything" })[s];

/** The label for a setting fixed by the server's configuration (kilnmark.mail.locked). */
export function Locked({ id }: { id: string }) {
  return (
    <p id={id} className="hint">
      Set by the server's configuration.
    </p>
  );
}
