import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import {
  allows,
  api,
  type CommonSetting,
  type Connector,
  type ConnectorSetting,
  type Source,
  type SourceForm,
  type TestResult,
} from "../api";
import { ContainerPicker } from "./ContainerPicker";
import { ConnectorTile, tileProps } from "./ConnectorTile";
import { Icon } from "./icons";
import { commonSwitch, settingKind } from "./settingKinds";
import { SideSheet, Switch } from "./ui";
import { useRole } from "./role";
import { needsDriver } from "./ConnectorGallery";
import { href } from "../router";

const STEPS = ["Connect", "Test", "Choose what to scan", "Save"] as const;
/** The stepper's short labels, so it stays on one line. */
const SHORT = ["Connect", "Test", "What to scan", "Save"] as const;
/** File settings that say how to read files: chosen in step 3, beside the preview that shows their effect. */
const READING = new Set(["delimiter", "header", "encoding"]);
const PATTERNS = new Set(["include", "exclude"]);
const SAVE_STEP = new Set(["description", "ai_egress"]);
const AI_LABELS: Record<string, string> = {
  local_only: "Local models only (default)",
  allowed: "Any configured model",
  none: "No AI models",
  false: "No: read-only (default)",
  true: "Yes: pipelines may write tables or files here",
};
/**
 * Whether pipelines may write to the source, whether SQL a person writes may run on it
 * (PostgreSQL only, ADR 0028), and how many runs may use it at once (ADR 0040): designers and
 * admins see and change them.
 */
const ADMIN_STEP = new Set(["allow_writing", "allow_sql", "max_runs"]);

/**
 * A connection, step by step: 1 Connect (fields with help and examples; host, port and database
 * build a database's URL, with the full URL under Advanced), 2 Test, 3 Choose what to scan (the
 * picker with counts and previews, patterns, and for files how to read them), 4 Save (display
 * name, description, AI rule; save, or save and scan). Every step can be revisited. Used to add a
 * connection beside the gallery and to edit a saved source, where it opens at step 1 with the
 * saved values. Escape closes it.
 */
export function ConnectionPanel({
  connector,
  existing: initial,
  onClose,
  onSaved,
}: {
  connector: Connector;
  existing?: Source;
  onClose: () => void;
  onSaved: (s: Source, scanQueued: boolean) => void;
}) {
  const id = useId();
  const role = useRole();
  // Once saved, the panel edits what it saved: a retry after a failed scan updates, never adds twice.
  const [saved, setSaved] = useState<Source | undefined>(initial);
  const existing = saved;
  const editing = existing != null;
  const [common, setCommon] = useState<CommonSetting[]>([]);
  const [step, setStep] = useState(0);
  const [name, setName] = useState(existing?.name ?? "");
  const [displayName, setDisplayName] = useState(
    existing && existing.displayName !== existing.name ? existing.displayName : "",
  );
  const [settings, setSettings] = useState<Record<string, string>>(existing?.settings ?? {});
  const [address, setAddress] = useState({ host: "", port: "", database: "" });
  const [password, setPassword] = useState("");
  const [clearPassword, setClearPassword] = useState(false);
  const [test, setTest] = useState<TestResult | null>(null);
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const snapshot = JSON.stringify({ name, displayName, settings, password, clearPassword, address });
  const [baseline, setBaseline] = useState(snapshot);
  const dirty = snapshot !== baseline;

  useEffect(() => {
    api.commonSettings().then(setCommon).catch((e: Error) => setError(e.message));
  }, []);

  // Each step change puts focus on the step's heading, which says "Step N of 4: ...".
  useEffect(() => heading.current?.focus(), [step, connector.kind]);

  const set = (key: string, value: string) => {
    setSettings((s) => ({ ...s, [key]: value }));
    setTest(null);
  };

  const form = (): SourceForm => ({
    name: name.trim(),
    kind: connector.kind,
    displayName: displayName.trim() || undefined,
    settings,
    password: password || undefined,
    clearPassword: clearPassword || undefined,
    source: existing?.name,
  });

  /** Builds the URL from host, port and database, when they are filled in. */
  const buildAddress = async () => {
    if (!connector.addressFields || !address.host.trim()) return true;
    try {
      const built = await api.address(connector.kind, address.host.trim(), address.port.trim(), address.database.trim());
      setSettings((s) => ({ ...s, ...built }));
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    }
  };

  const go = async (to: number) => {
    setError(null);
    if (step === 0 && to !== 0 && !(await buildAddress())) return;
    setStep(to);
  };

  const runTest = async () => {
    setTesting(true);
    setTest(null);
    setError(null);
    try {
      setTest(await api.testDraft(form()));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setTesting(false);
    }
  };

  /** The step that holds a setting, so an error about it can be shown where it is fixed. */
  const stepOf = (key: string) => {
    if (key === "displayName" || SAVE_STEP.has(key) || ADMIN_STEP.has(key)) return 3;
    if (READING.has(key) || PATTERNS.has(key) || key === connector.containerSetting) return 2;
    return 0;
  };

  const save = async (scan: boolean) => {
    setSaving(true);
    setError(null);
    try {
      const f = form();
      const s = editing
        ? await api.updateSource(existing.name, f)
        : await api.addSource({
            name: f.name ?? "",
            kind: connector.kind,
            settings,
            password,
            displayName: f.displayName,
          });
      setSaved(s);
      setPassword("");
      setClearPassword(false);
      setBaseline(JSON.stringify({ name, displayName, settings, password: "", clearPassword: false, address }));
      if (scan) {
        try {
          await api.scan(s.name);
        } catch (e) {
          setError(`Saved. The scan could not be queued: ${(e as Error).message}`);
          return;
        }
      }
      onSaved(s, scan);
    } catch (e) {
      const message = (e as Error).message;
      setError(message);
      const key = /^([a-zA-Z_]+):/.exec(message)?.[1];
      if (key) setStep(key === "name" || key === "password" || key === "url" ? 0 : stepOf(key));
    } finally {
      setSaving(false);
    }
  };

  /** Closes the panel, asking first if there are changes not saved. */
  const requestClose = () => {
    if (dirty && !window.confirm("Discard the changes you made to this connection?")) return;
    onClose();
  };

  // Escape closes the panel when focus is inside it, but not from a search box or a list, which
  // use Escape themselves, nor when something else has already handled it.
  const onKeyDown = (e: KeyboardEvent<HTMLElement>) => {
    const t = e.target as HTMLElement;
    if (
      e.key !== "Escape" ||
      e.defaultPrevented ||
      t.tagName === "SELECT" ||
      (t instanceof HTMLInputElement && t.type === "search") ||
      document.querySelector("dialog[open]")
    ) {
      return;
    }
    e.preventDefault();
    requestClose();
  };

  const field = (key: string) => `${id}-${key}`;
  const own = connector.settings.filter((s) => s.key !== connector.containerSetting);
  const connectFields = own.filter((s) => !READING.has(s.key) && !(connector.addressFields && s.key === "url"));
  const readingFields = own.filter((s) => READING.has(s.key));
  const commonOf = (keys: Set<string>) => common.filter((c) => keys.has(c.key));
  const containerSetting = connector.settings.find((s) => s.key === connector.containerSetting);

  /**
   * A connector setting with the control its kind needs: switch, list, number, address, secret or
   * text. The kind is the one the server declares; settingKinds.ts guesses only for a setting without.
   */
  const settingField = (s: ConnectorSetting) => {
    const k = settingKind(s);
    const value = settings[s.key] ?? "";
    if (k.kind === "switch") {
      const on = value === "" ? k.defaultOn : value.toLowerCase() === "true";
      return (
        <Switch
          key={s.key}
          id={field(`s-${s.key}`)}
          label={s.label}
          help={k.help}
          defaultNote={`Default: ${k.defaultOn ? "on" : "off"}`}
          checked={on}
          onChange={(v) => set(s.key, v ? "true" : "false")}
        />
      );
    }
    if (k.kind === "select") {
      return (
        <div className="field" key={s.key}>
          <label htmlFor={field(`s-${s.key}`)}>{s.label}</label>
          <select
            id={field(`s-${s.key}`)}
            value={
              k.choices.find((c) => c.value.toLowerCase() === value.trim().toLowerCase())?.value ?? k.defaultValue
            }
            onChange={(e) => set(s.key, e.target.value)}
            aria-describedby={field(`s-${s.key}-hint`)}
          >
            {k.choices.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
          <p id={field(`s-${s.key}-hint`)} className="hint">
            {k.help}
          </p>
        </div>
      );
    }
    return (
      <div className="field" key={s.key}>
        <label htmlFor={field(`s-${s.key}`)}>
          {s.label}
          {!s.required && " (optional)"}
        </label>
        <input
          id={field(`s-${s.key}`)}
          type={k.kind === "number" ? "number" : k.kind === "password" ? "password" : "text"}
          inputMode={k.kind === "number" ? "numeric" : k.kind === "url" ? "url" : undefined}
          min={k.kind === "number" ? k.min : undefined}
          value={value}
          required={s.required}
          autoComplete={k.kind === "password" ? "new-password" : "off"}
          spellCheck={false}
          placeholder={s.example ?? s.defaultValue ?? undefined}
          aria-describedby={field(`s-${s.key}-hint`)}
          onChange={(e) => set(s.key, e.target.value)}
        />
        <p id={field(`s-${s.key}-hint`)} className="hint">
          {s.hint}
          {s.multiple && ". Separate several with commas"}
          {s.example && (
            <>
              . Example: <span className="mono">{s.example}</span>
            </>
          )}
        </p>
      </div>
    );
  };

  /** Text fields first, then the on/off options together under one heading, as in the mockup. */
  const fieldGroup = (list: ConnectorSetting[], heading: string, between?: ReactNode) => {
    const switches = list.filter((s) => settingKind(s).kind === "switch");
    const others = list.filter((s) => settingKind(s).kind !== "switch");
    return (
      <>
        {others.map(settingField)}
        {between}
        {switches.length > 0 && (
          <div className="km-options" role="group" aria-labelledby={field(`opts-${heading}`)}>
            <h4 id={field(`opts-${heading}`)} className="km-options-title">
              {heading}
            </h4>
            {switches.map(settingField)}
          </div>
        )}
      </>
    );
  };

  const commonField = (c: CommonSetting) => {
    const sw = commonSwitch(c);
    if (sw) {
      const v = (settings[c.key] ?? "").trim().toLowerCase();
      return (
        <Switch
          key={c.key}
          id={field(`c-${c.key}`)}
          label={c.label}
          help={sw.help}
          defaultNote={`Default: ${sw.defaultOn ? "on" : "off"}`}
          checked={v === "" ? sw.defaultOn : v === "true"}
          onChange={(on) => set(c.key, on ? "true" : "false")}
        />
      );
    }
    return commonSelectOrText(c);
  };

  const commonSelectOrText = (c: CommonSetting) =>
    c.choices.length > 0 ? (
      <div className="field" key={c.key}>
        <label htmlFor={field(`c-${c.key}`)}>{c.label}</label>
        <select
          id={field(`c-${c.key}`)}
          value={settings[c.key] || c.defaultValue || ""}
          onChange={(e) => set(c.key, e.target.value)}
          aria-describedby={field(`c-${c.key}-hint`)}
        >
          {c.choices.map((v) => (
            <option key={v} value={v}>
              {AI_LABELS[v] ?? v}
            </option>
          ))}
        </select>
        <p id={field(`c-${c.key}-hint`)} className="hint">
          {c.hint}.
        </p>
      </div>
    ) : (
      <div className="field" key={c.key}>
        <label htmlFor={field(`c-${c.key}`)}>{c.label} (optional)</label>
        {c.key === "description" ? (
          <textarea
            id={field(`c-${c.key}`)}
            value={settings[c.key] ?? ""}
            rows={3}
            maxLength={2000}
            onChange={(e) => set(c.key, e.target.value)}
            aria-describedby={field(`c-${c.key}-hint`)}
          />
        ) : (
          <input
            id={field(`c-${c.key}`)}
            value={settings[c.key] ?? ""}
            autoComplete="off"
            spellCheck={false}
            inputMode={c.kind === "number" ? "numeric" : undefined}
            onChange={(e) => set(c.key, e.target.value)}
            aria-describedby={field(`c-${c.key}-hint`)}
          />
        )}
        <p id={field(`c-${c.key}-hint`)} className="hint">
          {c.hint}
          {c.multiple && ". Separate several with commas."}
        </p>
      </div>
    );

  return (
    <SideSheet labelledBy={`${id}-title`} onKeyDown={onKeyDown} onScrim={requestClose}>
      <div className="km-panel-head">
        <ConnectorTile {...tileProps(connector)} size="md" />
        <div className="km-panel-title">
          <h2 id={`${id}-title`}>{editing ? `Edit ${existing.displayName}` : `New ${connector.displayName} connection`}</h2>
          {editing && <p className="hint">{connector.displayName}</p>}
        </div>
        <button type="button" className="km-icon-button km-close" onClick={requestClose} aria-label="Close the connection panel">
          <Icon name="close" size={20} />
        </button>
      </div>
      <nav aria-label="Steps">
        <ol className="steps">
          {STEPS.map((label, i) => (
            <li key={label} className={i < step ? "is-past" : undefined}>
              <button type="button" aria-current={step === i ? "step" : undefined} onClick={() => go(i)} title={label}>
                <span className="step-number" aria-hidden="true">
                  {i + 1}
                </span>
                <span className="step-label">
                  <span className="visually-hidden">Step {i + 1}: </span>
                  <span aria-hidden="true">{SHORT[i]}</span>
                  <span className="visually-hidden">{label}</span>
                </span>
              </button>
            </li>
          ))}
        </ol>
      </nav>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {needsDriver(connector) && (
        <p className="km-callout km-callout--info">
          <strong>{connector.displayName} needs its driver.</strong> Kilnmark cannot ship it because of its licence.{" "}
          {role === "admin" ? (
            <>
              Install it under <a href={href.drivers()}>Settings, Drivers</a>, then test the connection.
            </>
          ) : (
            <>Ask an admin to install it under Settings, Drivers; until then the connection test says so.</>
          )}
        </p>
      )}
      <h3 ref={heading} tabIndex={-1} className="step-heading">
        Step {step + 1} of {STEPS.length}: {STEPS[step]}
      </h3>

      {step === 0 && (
        <div className="form">
          <div className="field">
            <label htmlFor={field("name")}>Source ID (used in asset references, cannot be changed)</label>
            <input
              id={field("name")}
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              readOnly={editing}
              pattern="[a-z0-9][a-z0-9\-]{0,62}"
              aria-describedby={field("name-hint")}
              autoComplete="off"
              spellCheck={false}
              placeholder="finance-db"
            />
            <p id={field("name-hint")} className="hint">
              {editing
                ? "To use another ID, add a new source."
                : "Lower-case letters, digits and hyphens. Example: finance-db. It cannot change later."}
            </p>
          </div>
          {connector.addressFields && (
            <fieldset className="group">
              <legend>Where the database is</legend>
              <div className="field-row">
                <div className="field">
                  <label htmlFor={field("host")}>Host</label>
                  <input
                    id={field("host")}
                    value={address.host}
                    onChange={(e) => setAddress({ ...address, host: e.target.value })}
                    onBlur={buildAddress}
                    autoComplete="off"
                    spellCheck={false}
                    placeholder="db.example.com"
                    aria-describedby={field("host-hint")}
                  />
                  <p id={field("host-hint")} className="hint">
                    A name or address. Example: db.example.com
                  </p>
                </div>
                <div className="field">
                  <label htmlFor={field("port")}>Port (optional)</label>
                  <input
                    id={field("port")}
                    inputMode="numeric"
                    value={address.port}
                    onChange={(e) => setAddress({ ...address, port: e.target.value })}
                    onBlur={buildAddress}
                    autoComplete="off"
                    aria-describedby={field("port-hint")}
                  />
                  <p id={field("port-hint")} className="hint">
                    Leave empty for the usual one.
                  </p>
                </div>
                <div className="field">
                  <label htmlFor={field("database")}>Database (optional)</label>
                  <input
                    id={field("database")}
                    value={address.database}
                    onChange={(e) => setAddress({ ...address, database: e.target.value })}
                    onBlur={buildAddress}
                    autoComplete="off"
                    spellCheck={false}
                    placeholder="sales"
                  />
                </div>
              </div>
              <details open={editing || undefined}>
                <summary>Advanced: the full URL</summary>
                {connector.settings.filter((s) => s.key === "url").map(settingField)}
              </details>
            </fieldset>
          )}
          {fieldGroup(connectFields, "Connection options", (
          <div className="field">
            <label htmlFor={field("pass")}>Password or secret (optional)</label>
            <input
              id={field("pass")}
              type="password"
              value={password}
              disabled={clearPassword}
              onChange={(e) => {
                setPassword(e.target.value);
                setTest(null);
              }}
              autoComplete="new-password"
              aria-describedby={field("pass-hint")}
            />
            <p id={field("pass-hint")} className="hint">
              {editing && existing.hasPassword
                ? "A password is stored. Leave this empty to keep it; enter it again if you change the address or user name."
                : "Stored encrypted. Use a read-only account."}
            </p>
            {editing && existing.hasPassword && (
              <Switch
                id={field("clear")}
                label="Remove the stored password"
                help="On save, the stored password or secret is deleted."
                checked={clearPassword}
                onChange={(on) => {
                  setClearPassword(on);
                  if (on) setPassword("");
                }}
              />
            )}
          </div>
          ))}
        </div>
      )}

      {step === 1 && (
        <div className="form">
          <p>Kilnmark connects with these settings, from the server, and says what it found. Nothing is saved.</p>
          <div className="buttons">
            <button type="button" className="primary" onClick={() => !testing && runTest()} aria-disabled={testing}>
              {testing ? "Testing…" : "Test connection"}
            </button>
          </div>
          <div role="status" aria-live="polite">
            {test &&
              (test.ok ? (
                <p className="km-status-line good">
                  <Icon name="circle-check" size={18} /> Connected · {test.millis} ms
                </p>
              ) : (
                <p className="km-callout km-callout--bad">
                  <strong>Could not connect.</strong> {test.problem}
                </p>
              ))}
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="form">
          {containerSetting && (
            <div className="field">
              <label htmlFor={field("containers")}>{containerSetting.label} to scan (optional)</label>
              <input
                id={field("containers")}
                value={settings[containerSetting.key] ?? ""}
                onChange={(e) => set(containerSetting.key, e.target.value)}
                autoComplete="off"
                spellCheck={false}
                aria-describedby={field("containers-hint")}
              />
              <p id={field("containers-hint")} className="hint">
                Leave empty to scan them all, or tick them below. Separate several with commas.
              </p>
              <ContainerPicker
                label={connector.containerLabel}
                value={settings[containerSetting.key] ?? ""}
                onChange={(v) => set(containerSetting.key, v)}
                load={() => api.draftContainers(form())}
                loadAssets={(container, offset) => api.draftAssets(form(), container, offset)}
                loadPreview={(path, rows) => api.draftPreview(form(), path, rows)}
              />
            </div>
          )}
          {commonOf(PATTERNS).map(commonField)}
          {readingFields.length > 0 && (
            <fieldset className="group">
              <legend>Reading files</legend>
              <p className="hint">
                Kilnmark detects these. If a preview looks wrong, set them here: they apply to every file of this
                source, in previews and scans.
              </p>
              {fieldGroup(readingFields, "Options")}
            </fieldset>
          )}
        </div>
      )}

      {step === 3 && (
        <div className="form">
          <div className="field">
            <label htmlFor={field("display")}>Display name (optional)</label>
            <input
              id={field("display")}
              value={displayName}
              maxLength={200}
              onChange={(e) => setDisplayName(e.target.value)}
              aria-describedby={field("display-hint")}
              autoComplete="off"
            />
            <p id={field("display-hint")} className="hint">
              What people see. Example: Finance warehouse. Leave empty to show the source ID.
            </p>
          </div>
          {commonOf(SAVE_STEP).map(commonField)}
          {allows(role, "designer") &&
            commonOf(ADMIN_STEP)
              .filter((c) => c.key !== "allow_sql" || connector.kind === "postgresql")
              .map(commonField)}
          <div className="buttons">
            <button type="button" className="primary" onClick={() => !saving && save(true)} aria-disabled={saving}>
              {saving ? "Saving…" : "Save and scan"}
            </button>
            <button type="button" onClick={() => !saving && save(false)} aria-disabled={saving}>
              Save
            </button>
          </div>
        </div>
      )}

      <div className="buttons step-nav">
        {step > 0 && (
          <button type="button" onClick={() => go(step - 1)}>
            Back<span className="visually-hidden"> to {STEPS[step - 1]}</span>
          </button>
        )}
        {step < STEPS.length - 1 && (
          <button type="button" onClick={() => go(step + 1)}>
            Next: {STEPS[step + 1]}
          </button>
        )}
      </div>
    </SideSheet>
  );
}
