import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { api, formatTime, type AccessRole, type ApiToken, type CreatedToken, type Me } from "../api";
import { useTitle } from "../components/useTitle";

const ROLES: { value: AccessRole; label: string }[] = [
  { value: "viewer", label: "Viewer: search and read the catalogue" },
  { value: "steward", label: "Steward: also start scans" },
  { value: "admin", label: "Admin: also add sources and manage tokens" },
];

const EXPIRY: { value: string; label: string }[] = [
  { value: "30", label: "In 30 days" },
  { value: "90", label: "In 90 days" },
  { value: "365", label: "In a year" },
  { value: "never", label: "Never" },
];

const STATUS: Record<ApiToken["status"], string> = {
  active: "Active",
  expired: "Expired",
  "set-aside": "Set aside (replaced environment token)",
  revoked: "Revoked",
};

/** Admins create, list and revoke API tokens. A new token is shown once, with a copy button. */
export function TokensPage({ me }: { me: Me }) {
  useTitle("API tokens");
  const [tokens, setTokens] = useState<ApiToken[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [created, setCreated] = useState<CreatedToken | null>(null);

  const load = useCallback(() => {
    api
      .tokens()
      .then((t) => {
        setTokens(t);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(load, [load]);

  const revoke = async (t: ApiToken) => {
    const own = me.tokenId === t.id;
    const question = own
      ? `Revoke ${t.name}? It is the token you are signed in with, so you will be signed out.`
      : `Revoke ${t.name}? Anything using it stops working at once.`;
    if (!window.confirm(question)) return;
    try {
      await api.revokeToken(t.id);
      setNotice(`Token ${t.name} revoked.`);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      <h1 tabIndex={-1}>API tokens</h1>
      <p className="lead">
        Scripts, pipelines and people sign in with an API token. Each token has a role, and can expire or be revoked at
        any time.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {created && (
        <NewTokenPanel
          created={created}
          onDone={() => {
            setCreated(null);
            document.getElementById("tok-name")?.focus();
          }}
        />
      )}

      <CreateToken
        onCreated={(t) => {
          setCreated(t);
          setNotice("");
          load();
        }}
      />

      <section aria-labelledby="tokens-h" className="panel">
        <h2 id="tokens-h">Tokens</h2>
        {tokens === null && !error && <p>Loading…</p>}
        {tokens?.length === 0 && <p>No tokens yet.</p>}
        {tokens && tokens.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Tokens table (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Role</th>
                  <th scope="col">Status</th>
                  <th scope="col">Created</th>
                  <th scope="col">Expires</th>
                  <th scope="col">Last used</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {tokens.map((t) => (
                  <tr key={t.id}>
                    <th scope="row">
                      {t.name}
                      {me.tokenId === t.id && <div className="small muted">You are signed in with this one</div>}
                    </th>
                    <td>{t.role}</td>
                    <td>
                      <span className={`badge badge-token-${t.status}`}>{STATUS[t.status]}</span>
                      {t.revokedAt && <div className="small muted">{formatTime(t.revokedAt)}</div>}
                    </td>
                    <td className="small">
                      {formatTime(t.createdAt)}
                      <div className="muted">by {t.createdBy}</div>
                    </td>
                    <td className="small">{t.expiresAt ? formatTime(t.expiresAt) : "Never"}</td>
                    <td className="small">{t.lastUsedAt ? formatTime(t.lastUsedAt) : "Not yet"}</td>
                    <td>
                      {t.status !== "revoked" && (
                        <button type="button" onClick={() => revoke(t)} aria-label={`Revoke ${t.name}`}>
                          Revoke
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

function CreateToken({ onCreated }: { onCreated: (t: CreatedToken) => void }) {
  const [name, setName] = useState("");
  const [role, setRole] = useState<AccessRole>("viewer");
  const [expiry, setExpiry] = useState("90");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const errorRef = useRef<HTMLParagraphElement>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const expiresAt = expiry === "never" ? null : new Date(Date.now() + Number(expiry) * 86_400_000).toISOString();
      const t = await api.createToken({ name: name.trim(), role, expiresAt });
      setName("");
      onCreated(t);
    } catch (err) {
      setError((err as Error).message);
      window.setTimeout(() => errorRef.current?.focus(), 0);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section aria-labelledby="create-h" className="panel">
      <h2 id="create-h">Create a token</h2>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      <form onSubmit={submit} className="form">
        <div className="field">
          <label htmlFor="tok-name">Name</label>
          <input id="tok-name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={100}
            autoComplete="off" aria-describedby="tok-name-hint" />
          <p id="tok-name-hint" className="hint">Who or what will use it, for example nightly-ci or Priya's laptop.</p>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="tok-role">Role</label>
            <select id="tok-role" value={role} onChange={(e) => setRole(e.target.value as AccessRole)}>
              {ROLES.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="tok-expiry">Expires</label>
            <select id="tok-expiry" value={expiry} onChange={(e) => setExpiry(e.target.value)}
              aria-describedby="tok-expiry-hint">
              {EXPIRY.map((x) => (
                <option key={x.value} value={x.value}>
                  {x.label}
                </option>
              ))}
            </select>
            <p id="tok-expiry-hint" className="hint">Give the least role and the shortest life that will do.</p>
          </div>
        </div>
        <button type="submit" className="primary" disabled={saving}>
          {saving ? "Creating…" : "Create token"}
        </button>
      </form>
    </section>
  );
}

function NewTokenPanel({ created, onDone }: { created: CreatedToken; onDone: () => void }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const field = useRef<HTMLInputElement>(null);
  const [copyStatus, setCopyStatus] = useState("");

  useEffect(() => {
    heading.current?.focus();
  }, [created]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(created.token);
      setCopyStatus("Token copied to the clipboard.");
    } catch {
      field.current?.focus();
      field.current?.select();
      setCopyStatus("Could not copy automatically. The token is selected: press Ctrl+C to copy it.");
    }
  };

  return (
    <section aria-labelledby="new-h" className="panel new-token">
      <h2 id="new-h" tabIndex={-1} ref={heading}>
        New token: {created.name}
      </h2>
      <p className="warning">
        <strong>Copy this token now.</strong> This is the only time Kilnmark shows it: only a fingerprint of it is kept,
        so it cannot be shown again. Store it like a password. If you lose it, revoke it and create another.
      </p>
      <div className="field">
        <label htmlFor="new-token">
          Token for {created.name} ({created.role}
          {created.expiresAt ? `, expires ${formatTime(created.expiresAt)}` : ", never expires"})
        </label>
        <div className="copy-row">
          <input id="new-token" ref={field} className="mono" readOnly value={created.token} spellCheck={false}
            onFocus={(e) => e.currentTarget.select()} />
          <button type="button" className="primary" onClick={copy}>
            Copy token
          </button>
        </div>
      </div>
      <p role="status" className="notice">
        {copyStatus}
      </p>
      <button type="button" onClick={onDone}>
        I have copied it
      </button>
    </section>
  );
}
