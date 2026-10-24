import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { api, type CommonSetting, type Connector, type Source, type SourceForm as Form, type TestResult } from "../api";
import { ContainerPicker } from "./ContainerPicker";

const AI_LABELS: Record<string, string> = {
  local_only: "Local models only (default)",
  allowed: "Any configured model",
  none: "No AI models",
};

/**
 * Adding or editing a source: kind, name, display name, the connector's settings (its container
 * setting with a picker), the password, and the settings every source has. Test the connection
 * before saving. When editing, the name and kind are fixed, and a blank password keeps the stored one.
 */
export function SourceForm({
  existing,
  onSaved,
  onCancel,
}: {
  existing?: Source;
  onSaved: (s: Source) => void;
  onCancel?: () => void;
}) {
  const id = useId();
  const editing = existing != null;
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [common, setCommon] = useState<CommonSetting[]>([]);
  const [kind, setKind] = useState(existing?.kind ?? "postgresql");
  const [name, setName] = useState(existing?.name ?? "");
  const [displayName, setDisplayName] = useState(
    existing && existing.displayName !== existing.name ? existing.displayName : "",
  );
  const [settings, setSettings] = useState<Record<string, string>>(existing?.settings ?? {});
  const [password, setPassword] = useState("");
  const [clearPassword, setClearPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [test, setTest] = useState<TestResult | null>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    api.connectors().then(setConnectors).catch((e: Error) => setError(e.message));
    api.commonSettings().then(setCommon).catch((e: Error) => setError(e.message));
  }, []);

  const connector = connectors.find((c) => c.kind === kind);
  const set = (key: string, value: string) => {
    setSettings((s) => ({ ...s, [key]: value }));
    setTest(null);
  };

  const form = (): Form => ({
    name: name.trim(),
    kind,
    displayName: displayName.trim() || undefined,
    settings,
    password: password || undefined,
    clearPassword: clearPassword || undefined,
    source: existing?.name,
  });

  const showError = (message: string) => {
    setError(message);
    window.setTimeout(() => errorRef.current?.focus(), 0);
  };

  const runTest = async () => {
    setTesting(true);
    setTest(null);
    setError(null);
    try {
      setTest(await api.testDraft(form()));
    } catch (e) {
      showError((e as Error).message);
    } finally {
      setTesting(false);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const f = form();
      const s = editing
        ? await api.updateSource(existing.name, f)
        : await api.addSource({ name: f.name ?? "", kind, settings, password, displayName: f.displayName });
      if (!editing) {
        setName("");
        setDisplayName("");
        setSettings({});
      }
      setPassword("");
      setClearPassword(false);
      setTest(null);
      onSaved(s);
    } catch (err) {
      showError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const field = (key: string) => `${id}-${key}`;

  return (
    <>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      <form onSubmit={submit} className="form">
        <div className="field-row">
          <div className="field">
            <label htmlFor={field("kind")}>Kind</label>
            {editing ? (
              <input id={field("kind")} value={connector?.displayName ?? kind} readOnly aria-describedby={field("kind-hint")} />
            ) : (
              <select
                id={field("kind")}
                value={kind}
                onChange={(e) => {
                  setKind(e.target.value);
                  setSettings({});
                  setTest(null);
                }}
              >
                {connectors.map((c) => (
                  <option key={c.kind} value={c.kind}>
                    {c.displayName}
                  </option>
                ))}
              </select>
            )}
            {editing && (
              <p id={field("kind-hint")} className="hint">
                A source's kind cannot change.
              </p>
            )}
          </div>
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
            />
            <p id={field("name-hint")} className="hint">
              {editing
                ? "To use another ID, add a new source."
                : "Lower-case letters, digits and hyphens, for example finance-db. Choose with care: it cannot change later."}
            </p>
          </div>
        </div>
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
            What people see, for example Finance warehouse. Leave empty to show the source ID.
          </p>
        </div>

        {connector?.settings.map((s) => (
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
              aria-describedby={s.hint ? field(`s-${s.key}-hint`) : undefined}
              onChange={(e) => set(s.key, e.target.value)}
            />
            {s.hint && (
              <p id={field(`s-${s.key}-hint`)} className="hint">
                {s.hint}
                {s.multiple && ". Separate several with commas."}
              </p>
            )}
            {s.key === connector.containerSetting && (
              <ContainerPicker
                label={connector.containerLabel}
                value={settings[s.key] ?? ""}
                onChange={(v) => set(s.key, v)}
                load={() => api.draftContainers(form())}
              />
            )}
          </div>
        ))}

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
              ? "A password is stored. Leave this empty to keep it; enter it again if you change the URL, endpoint or user name."
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

        <fieldset className="group">
          <legend>Settings every source has</legend>
          {common.map((c) =>
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
                <label htmlFor={field(`c-${c.key}`)}>
                  {c.label} (optional)
                </label>
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
            ),
          )}
        </fieldset>

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
        <div className="buttons">
          <button type="button" onClick={runTest} disabled={testing || !connector}>
            {testing ? "Testing…" : "Test connection"}
          </button>
          <button type="submit" className="primary" disabled={saving || !connector}>
            {saving ? "Saving…" : editing ? "Save changes" : "Add source"}
          </button>
          {onCancel && (
            <button type="button" onClick={onCancel}>
              Cancel
            </button>
          )}
        </div>
      </form>
    </>
  );
}
