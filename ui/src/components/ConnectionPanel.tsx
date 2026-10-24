import { useEffect, useId, useRef, useState } from "react";
import {
  api,
  type CommonSetting,
  type Connector,
  type ConnectorSetting,
  type Source,
  type SourceForm,
  type TestResult,
} from "../api";
import { ContainerPicker } from "./ContainerPicker";
import { Icon } from "./Icon";

const STEPS = ["Connect", "Test", "Choose what to scan", "Save"] as const;
/** File settings that say how to read files: chosen in step 3, beside the preview that shows their effect. */
const READING = new Set(["delimiter", "header", "encoding"]);
const PATTERNS = new Set(["include", "exclude"]);
const SAVE_STEP = new Set(["description", "ai_egress"]);
const AI_LABELS: Record<string, string> = {
  local_only: "Local models only (default)",
  allowed: "Any configured model",
  none: "No AI models",
};

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
  existing,
  onClose,
  onSaved,
}: {
  connector: Connector;
  existing?: Source;
  onClose: () => void;
  onSaved: (s: Source, scanQueued: boolean) => void;
}) {
  const id = useId();
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
    if (key === "displayName" || SAVE_STEP.has(key)) return 3;
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
      let queued = false;
      if (scan) {
        await api.scan(s.name);
        queued = true;
      }
      onSaved(s, queued);
    } catch (e) {
      const message = (e as Error).message;
      setError(message);
      const key = /^([a-zA-Z_]+):/.exec(message)?.[1];
      if (key) setStep(key === "name" || key === "password" || key === "url" ? 0 : stepOf(key));
    } finally {
      setSaving(false);
    }
  };

  // Escape closes the panel wherever focus is (also after a button that had focus was disabled),
  // except while a dialog is open, which Escape closes first.
  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape" && !document.querySelector("dialog[open]")) {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const field = (key: string) => `${id}-${key}`;
  const own = connector.settings.filter((s) => s.key !== connector.containerSetting);
  const connectFields = own.filter((s) => !READING.has(s.key) && !(connector.addressFields && s.key === "url"));
  const readingFields = own.filter((s) => READING.has(s.key));
  const commonOf = (keys: Set<string>) => common.filter((c) => keys.has(c.key));
  const containerSetting = connector.settings.find((s) => s.key === connector.containerSetting);

  const settingField = (s: ConnectorSetting) => (
    <div className="field" key={s.key}>
      <label htmlFor={field(`s-${s.key}`)}>
        {s.label}
        {!s.required && " (optional)"}
      </label>
      <input
        id={field(`s-${s.key}`)}
        value={settings[s.key] ?? ""}
        required={s.required}
        autoComplete="off"
        spellCheck={false}
        placeholder={s.example ?? undefined}
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

  const commonField = (c: CommonSetting) =>
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
    <section className="connection-panel panel" aria-labelledby={`${id}-title`}>
      <div className="panel-head">
        <h2 id={`${id}-title`}>
          <Icon name={connector.icon} size={22} /> {editing ? `Edit ${existing.displayName}` : `New ${connector.displayName} connection`}
        </h2>
        <button type="button" onClick={onClose}>
          Close<span className="visually-hidden"> the connection panel</span>
        </button>
      </div>
      <nav aria-label="Steps">
        <ol className="steps">
          {STEPS.map((label, i) => (
            <li key={label}>
              <button type="button" aria-current={step === i ? "step" : undefined} onClick={() => go(i)}>
                <span className="step-number">{i + 1}</span> {label}
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
          {connectFields.map(settingField)}
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
              <div className="check">
                <input
                  id={field("clear")}
                  type="checkbox"
                  checked={clearPassword}
                  onChange={(e) => {
                    setClearPassword(e.target.checked);
                    if (e.target.checked) setPassword("");
                  }}
                />
                <label htmlFor={field("clear")}>Remove the stored password</label>
              </div>
            )}
          </div>
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
                <p className="notice">Connected in {(test.millis / 1000).toFixed(1)} s.</p>
              ) : (
                <p className="warning">
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
              {readingFields.map(settingField)}
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
    </section>
  );
}
