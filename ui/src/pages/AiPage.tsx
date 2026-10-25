import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  aiApi,
  formatNumber,
  type AiDiscovery,
  type AiFeature,
  type AiFoundModel,
  type AiFoundServer,
  type AiProfile,
  type AiProfileInput,
  type AiProvider,
  type AiPurpose,
  type AiSettings,
  type AiStatus,
  type AiTestResult,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";

/**
 * AI models (admins): the server-wide rule on hosted models, which model each feature uses, and
 * the model profiles themselves, with a test button that says in plain words whether one works.
 */
export function AiPage() {
  useTitle("AI models");
  const [profiles, setProfiles] = useState<AiProfile[] | null>(null);
  const [features, setFeatures] = useState<AiFeature[]>([]);
  const [providers, setProviders] = useState<AiProvider[]>([]);
  const [settings, setSettings] = useState<AiSettings | null>(null);
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState<AiProfile | "new" | null>(null);
  const [tests, setTests] = useState<Record<string, AiTestResult | "testing">>({});
  const [discovery, setDiscovery] = useState<AiDiscovery | null>(null);
  const [finding, setFinding] = useState(false);
  const [findError, setFindError] = useState<string | null>(null);
  // A model chosen from what discovery found: the add-model form opens with it filled in.
  const [prefill, setPrefill] = useState<{ n: number; values: Partial<FormState> } | null>(null);

  const load = useCallback(() => {
    Promise.all([aiApi.profiles(), aiApi.features(), aiApi.settings(), aiApi.status()])
      .then(([p, f, s, st]) => {
        setProfiles(p);
        setFeatures(f);
        setSettings(s);
        setStatus(st);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(() => {
    load();
    aiApi.providers().then(setProviders).catch(() => setProviders([]));
  }, [load]);

  const run = async (work: () => Promise<unknown>, done: string) => {
    try {
      await work();
      setNotice(done);
      setError(null);
      load();
    } catch (e) {
      setError((e as Error).message);
      setNotice("");
    }
  };

  const test = async (p: AiProfile) => {
    setTests((t) => ({ ...t, [p.name]: "testing" }));
    try {
      const r = await aiApi.testProfile(p.name);
      setTests((t) => ({ ...t, [p.name]: r }));
    } catch (e) {
      setTests((t) => ({ ...t, [p.name]: { ok: false, message: (e as Error).message, latencyMs: 0, dimensions: null } }));
    }
  };

  const find = async () => {
    setFinding(true);
    setFindError(null);
    try {
      setDiscovery(await aiApi.discover());
    } catch (e) {
      setFindError((e as Error).message);
    } finally {
      setFinding(false);
    }
  };

  const use = (server: AiFoundServer, model: AiFoundModel) => {
    setPrefill((p) => ({ n: (p?.n ?? 0) + 1, values: fromFound(server, model) }));
    setEditing("new");
  };

  const remove = (p: AiProfile) => {
    if (!window.confirm(`Delete model profile ${p.name}? Its stored embeddings are deleted with it.`)) return;
    run(() => aiApi.deleteProfile(p.name), `Model profile ${p.name} deleted.`);
  };

  return (
    <>
      <nav aria-label="Breadcrumb" className="crumbs">
        <ol>
          <li>
            <a href={href.settings()}>Settings</a>
          </li>
          <li aria-current="page">AI models</li>
        </ol>
      </nav>
      <h1 tabIndex={-1}>AI models</h1>
      <p className="lead">
        Choose the models Kilnmark uses: local ones on your own servers, or hosted ones with an API key. AI only proposes;
        people approve. A source&rsquo;s AI rule decides which models may see it. <a href={href.aiUsage()}>See usage</a>.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      {settings && <HostedRule settings={settings} onSave={(v) => run(() => aiApi.saveSettings(v), "Rule saved.")} />}

      <section aria-labelledby="features-h" className="panel">
        <h2 id="features-h">Features</h2>
        <p className="hint">Each feature uses one model. A feature with no model is off and works without AI.</p>
        <div className="ai-features">
          {features.map((f) => (
            <div className="field" key={f.id}>
              <label htmlFor={`feature-${f.id}`}>{f.label}</label>
              <select
                id={`feature-${f.id}`}
                value={f.profile ?? ""}
                aria-describedby={`feature-${f.id}-hint`}
                onChange={(e) =>
                  run(
                    () => aiApi.route(f.id, e.target.value || null),
                    e.target.value ? `${f.label} now uses ${e.target.value}.` : `${f.label} is off.`,
                  )
                }
              >
                <option value="">Off</option>
                {(profiles ?? [])
                  .filter((p) => p.purpose === f.purpose)
                  .map((p) => (
                    <option key={p.name} value={p.name}>
                      {p.name} ({p.model}
                      {p.effectiveLocal ? ", local" : ", hosted"})
                    </option>
                  ))}
              </select>
              <p id={`feature-${f.id}-hint`} className="hint">
                {f.help} Needs {f.purpose === "chat" ? "a chat" : "an embedding"} model.
                {f.id === "embed" &&
                  " With a hosted model, the text of every search is sent to that provider to be embedded."}
                {f.id === "search_nl" && " With a hosted model, every question is sent to that provider."}
              </p>
            </div>
          ))}
        </div>
        {status?.embeddings && (
          <div className="buttons ai-embed-status">
            <p className="meta">
              Search by meaning: {formatNumber(status.embeddings.embedded)} of {formatNumber(status.embeddings.assets)}{" "}
              assets embedded with {status.embeddings.profile}
              {status.embeddings.running ? " (embedding now)" : ""}.
            </p>
            <button
              type="button"
              onClick={async () => {
                try {
                  const r = await aiApi.refreshEmbeddings();
                  setNotice(r.message);
                  setError(null);
                  load();
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              Embed again
            </button>
          </div>
        )}
      </section>

      <section aria-labelledby="profiles-h" className="panel">
        <h2 id="profiles-h">Model profiles</h2>
        {profiles === null && !error && <p>Loading…</p>}
        {profiles?.length === 0 && (
          <p>
            No models yet. Press <strong>Find local models</strong> below to look for Ollama, LM Studio and the bundled
            models, or add one yourself: for example the bundled llama-server (Compose profile <code>ai</code>) at{" "}
            <code>http://ai:8080/v1</code>, ticked as local.
          </p>
        )}
        {profiles && profiles.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Model profiles (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Model</th>
                  <th scope="col">Runs</th>
                  <th scope="col">API key</th>
                  <th scope="col">Used by</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions and test result</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {profiles.map((p) => {
                  const t = tests[p.name];
                  return (
                    <tr key={p.name}>
                      <th scope="row">
                        {p.name}
                        <div className="small muted">{p.purpose === "chat" ? "Chat" : `Embedding, ${p.dimensions} dimensions`}</div>
                      </th>
                      <td className="small">
                        <span className="mono">{p.model}</span>
                        <div className="muted">{p.providerLabel}</div>
                      </td>
                      <td>
                        <span className={`badge ${p.effectiveLocal ? "badge-succeeded" : p.local ? "badge-failed" : "badge-running"}`}>
                          {p.effectiveLocal ? "Local" : p.local ? "Not accepted as local" : "Hosted"}
                        </span>
                        {p.local && !p.effectiveLocal && (
                          <div className="small muted">Its address is not on one of this server&rsquo;s local networks.</div>
                        )}
                        <div className="small muted mono">{p.effectiveBaseUrl}</div>
                      </td>
                      <td className="small">{p.hasApiKey ? (p.apiKeyHint ? `Ends ${p.apiKeyHint}` : "Stored") : "None"}</td>
                      <td className="small">{p.usedBy.length ? p.usedBy.join(", ") : "Nothing"}</td>
                      <td>
                        <div className="buttons">
                          <button type="button" onClick={() => test(p)} disabled={t === "testing"} aria-label={`Test ${p.name}`}>
                            {t === "testing" ? "Testing…" : "Test"}
                          </button>
                          <button
                            type="button"
                            onClick={() => {
                              setPrefill(null);
                              setEditing(p);
                            }}
                            aria-label={`Edit ${p.name}`}
                          >
                            Edit
                          </button>
                          <button type="button" onClick={() => remove(p)} aria-label={`Delete ${p.name}`}>
                            Delete
                          </button>
                        </div>
                        <p role="status" className="small ai-test">
                          {t && t !== "testing" && (
                            <span className={t.ok ? "ai-ok" : "ai-bad"}>
                              <strong>{t.ok ? "Works: " : "Does not work: "}</strong>
                              {t.message}
                            </span>
                          )}
                        </p>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {editing === null && (
          <button
            type="button"
            className="primary"
            onClick={() => {
              setPrefill(null);
              setEditing("new");
            }}
          >
            Add a model
          </button>
        )}
      </section>

      <FindModels discovery={discovery} finding={finding} error={findError} onFind={find} onUse={use} />

      {editing !== null && (
        <ProfileForm
          key={editing === "new" ? `new-${prefill?.n ?? 0}` : editing.name}
          profile={editing === "new" ? null : editing}
          prefill={editing === "new" ? prefill?.values : undefined}
          known={(discovery?.servers ?? []).filter((s) => s.outcome === "found")}
          providers={providers}
          locked={settings?.lockedByServer ?? false}
          onCancel={() => {
            setEditing(null);
            setPrefill(null);
          }}
          onSaved={(p, isNew) => {
            setEditing(null);
            setPrefill(null);
            setNotice(`Model profile ${p.name} ${isNew ? "added" : "saved"}. Press Test to check it works.`);
            load();
          }}
        />
      )}
    </>
  );
}

/** Sizes as model servers show them, in decimal units: 5.1 GB. */
function formatSize(bytes: number | null): string {
  if (bytes == null) return "";
  return bytes >= 1e9 ? `${(bytes / 1e9).toFixed(1)} GB` : `${Math.max(1, Math.round(bytes / 1e6))} MB`;
}

/** A base URL compared loosely: lower-case scheme and host, no trailing slash, ending in /v1. */
function sameServer(a: string, b: string): boolean {
  const norm = (u: string) => {
    try {
      const url = new URL(u.trim());
      const path = url.pathname.replace(/\/+$/, "").replace(/\/v1$/i, "");
      return `${url.protocol}//${url.host.toLowerCase()}${path}/v1`;
    } catch {
      return "";
    }
  };
  const x = norm(a);
  return x !== "" && x === norm(b);
}

/** A profile name from a model name: lower-case letters, digits and hyphens. */
function nameFor(model: string): string {
  const slug = model
    .toLowerCase()
    .replace(/:latest$/, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 63)
    .replace(/-+$/, "");
  return slug || "local-model";
}

/** What the add-model form starts with for a model discovery found. */
function fromFound(server: AiFoundServer, model: AiFoundModel): Partial<FormState> {
  return {
    name: nameFor(model.name),
    provider: "openai-compatible",
    baseUrl: server.baseUrl,
    model: model.name,
    purpose: model.embedding ? "embedding" : "chat",
    local: server.suggestLocal,
    apiKey: "",
    // What the server says about the model; Test still checks the embedding size.
    contextSize: model.contextSize != null && model.contextSize >= 256 ? String(model.contextSize) : "",
    dimensions: model.embedding && model.dimensions != null ? String(model.dimensions) : "",
  };
}

/** A model's size, parameters and quantisation, as far as its server says. */
function modelFacts(m: AiFoundModel): string {
  return [formatSize(m.sizeBytes), m.parameterSize && `${m.parameterSize} parameters`, m.quantisation, m.family]
    .filter(Boolean)
    .join(" · ");
}

function FindModels({
  discovery,
  finding,
  error,
  onFind,
  onUse,
}: {
  discovery: AiDiscovery | null;
  finding: boolean;
  error: string | null;
  onFind: () => void;
  onUse: (s: AiFoundServer, m: AiFoundModel) => void;
}) {
  const errorRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (error) errorRef.current?.focus();
  }, [error]);
  const found = discovery?.servers.filter((s) => s.outcome === "found") ?? [];
  const missing = discovery?.servers.filter((s) => s.outcome !== "found") ?? [];
  const models = found.reduce((n, s) => n + s.models.length, 0);
  const summary = finding
    ? "Looking for model servers…"
    : !discovery
      ? ""
      : found.length === 0
        ? "No model server answered. The reasons are listed below."
        : `Found ${found.length} model ${found.length === 1 ? "server" : "servers"} with ${models} ${models === 1 ? "model" : "models"}.`;
  return (
    <section aria-labelledby="find-h" className="panel">
      <h2 id="find-h">Find local models</h2>
      <p className="hint">
        Looks for Ollama and LM Studio on this computer (or the computer running Kilnmark&rsquo;s container) and for the
        bundled models, and lists what they serve. GPU use is what the model server reports: Kilnmark does not look at
        the GPU itself.
      </p>
      <div className="buttons">
        {/* aria-disabled, not disabled: the button keeps focus while it works. */}
        <button type="button" onClick={() => !finding && onFind()} aria-disabled={finding}>
          {finding ? "Looking…" : "Find local models"}
        </button>
      </div>
      <p role="status" className="small">
        {summary}
      </p>
      {error && (
        <p className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      {discovery && !discovery.inContainer && (
        <p className="hint">
          Kilnmark is not running in a container, so only <code>localhost</code> was checked.
        </p>
      )}
      {found.map((s) => (
        <FoundServerView key={s.address} server={s} onUse={onUse} />
      ))}
      {missing.length > 0 && (
        <details className="ai-missing" open={found.length === 0}>
          <summary>
            Not found or not checked ({missing.length})
          </summary>
          <ul>
            {missing.map((s) => (
              <li key={s.address}>
                <strong>{s.label}</strong> <span className="mono small">{s.address}</span>
                <div>{s.problem}</div>
                {s.guidance && <div className="hint">{s.guidance}</div>}
              </li>
            ))}
          </ul>
          {discovery?.inContainer && (
            <p className="hint">
              Kilnmark runs in a container, so <code>localhost</code> is the container itself. A model server on the
              same computer is at <code>host.containers.internal</code> (Podman) or <code>host.docker.internal</code>{" "}
              (Docker).
            </p>
          )}
        </details>
      )}
    </section>
  );
}

function FoundServerView({ server: s, onUse }: { server: AiFoundServer; onUse: (s: AiFoundServer, m: AiFoundModel) => void }) {
  const headingId = `found-${s.address.replace(/[^a-z0-9]+/gi, "-")}`;
  return (
    <div className="ai-found">
      <h3 id={headingId}>
        {s.server} <span className="mono small">{s.baseUrl}</span>
      </h3>
      <p className="small muted">
        {s.label}
        {s.detail ? `. ${s.detail}` : ""}
        {!s.gpuReported && " This server does not report GPU use."}
      </p>
      {s.guidance && <p className="hint">{s.guidance}</p>}
      {s.models.length > 0 && (
        <div className="table-scroll" role="region" aria-labelledby={headingId} tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Model</th>
                <th scope="col">Size</th>
                {s.gpuReported && <th scope="col">GPU</th>}
                <th scope="col">
                  <span className="visually-hidden">Action</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {s.models.map((m) => (
                <tr key={m.name}>
                  <th scope="row">
                    <span className="mono">{m.name}</span>
                    <div className="small muted">
                      {m.embedding ? "Embedding model" : "Chat model"}
                      {m.loaded === true && !s.gpuReported ? " · loaded" : ""}
                    </div>
                  </th>
                  <td className="small">{modelFacts(m) || "Not reported"}</td>
                  {s.gpuReported && (
                    <td className="small">
                      {m.gpuText}
                      {m.gpu === "split" && m.vramBytes != null ? ` (${formatSize(m.vramBytes)} in GPU memory)` : ""}
                    </td>
                  )}
                  <td>
                    <button type="button" onClick={() => onUse(s, m)} aria-label={`Use ${m.name} from ${s.server}`}>
                      Use this model
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function HostedRule({ settings, onSave }: { settings: AiSettings; onSave: (v: "allowed" | "forbidden") => void }) {
  const [value, setValue] = useState(settings.hostedModels);
  useEffect(() => setValue(settings.hostedModels), [settings.hostedModels]);
  return (
    <section aria-labelledby="rule-h" className="panel">
      <h2 id="rule-h">What may leave the network</h2>
      {settings.lockedByServer ? (
        <p>
          <strong>Only local models.</strong> This server&rsquo;s configuration forbids hosted models
          (<code>KILNMARK_AI_HOSTED_MODELS=forbidden</code>), so this cannot be changed here.
        </p>
      ) : (
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault();
            onSave(value);
          }}
        >
          <fieldset>
            <legend>Hosted models (any model not marked local)</legend>
            <label className="choice">
              <input type="radio" name="hosted" value="allowed" checked={value === "allowed"} onChange={() => setValue("allowed")} />
              Allowed, for sources whose AI rule allows them
            </label>
            <label className="choice">
              <input type="radio" name="hosted" value="forbidden" checked={value === "forbidden"} onChange={() => setValue("forbidden")} />
              Forbidden: only local models, for every source
            </label>
          </fieldset>
          <p className="hint">
            Each source also has its own AI rule: local models only (the default), any model, or none.
          </p>
          <div>
            <button type="submit" className="primary" disabled={value === settings.hostedModels}>
              Save rule
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

type FormState = {
  name: string;
  provider: string;
  baseUrl: string;
  model: string;
  purpose: AiPurpose;
  dimensions: string;
  contextSize: string;
  local: boolean;
  apiKey: string;
  removeKey: boolean;
  timeoutSeconds: string;
  maxConcurrent: string;
};

/** The host part of an address, or "" when it is not one yet. */
function hostOf(url: string | null | undefined): string {
  try {
    return url ? new URL(url).hostname.toLowerCase() : "";
  } catch {
    return "";
  }
}

/** Scheme, host and port, with the scheme's default port: where a stored key may be sent. */
function originOf(url: string | null | undefined): string {
  try {
    if (!url) return "";
    const u = new URL(url);
    return `${u.protocol}//${u.hostname.toLowerCase()}:${u.port || (u.protocol === "https:" ? "443" : "80")}`;
  } catch {
    return "";
  }
}

function ProfileForm({
  profile,
  prefill,
  known,
  providers,
  locked,
  onCancel,
  onSaved,
}: {
  profile: AiProfile | null;
  prefill?: Partial<FormState>;
  known: AiFoundServer[];
  providers: AiProvider[];
  locked: boolean;
  onCancel: () => void;
  onSaved: (p: AiProfile, isNew: boolean) => void;
}) {
  const [f, setF] = useState<FormState>(() => ({
    name: profile?.name ?? "",
    provider: profile?.provider ?? "openai-compatible",
    baseUrl: profile?.baseUrl ?? "",
    model: profile?.model ?? "",
    purpose: profile?.purpose ?? "chat",
    dimensions: profile?.dimensions?.toString() ?? "",
    contextSize: profile?.contextSize?.toString() ?? "",
    local: profile?.local ?? false,
    apiKey: "",
    removeKey: false,
    timeoutSeconds: String(profile?.timeoutSeconds ?? 60),
    maxConcurrent: String(profile?.maxConcurrent ?? 2),
    ...prefill,
  }));
  const [checked, setChecked] = useState<AiFoundServer | "checking" | null>(null);
  const [typeOwn, setTypeOwn] = useState(false);
  const checkRef = useRef<HTMLParagraphElement>(null);
  const modelRef = useRef<HTMLInputElement>(null);
  const urlRef = useRef<HTMLInputElement>(null);
  // After "Another model", the text box replaces the list: put the focus in it.
  useEffect(() => {
    if (typeOwn) modelRef.current?.focus();
  }, [typeOwn]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => heading.current?.focus(), []);

  const provider = providers.find((p) => p.id === f.provider);
  const compatible = f.provider === "openai-compatible";
  const effective = (baseUrl: string) => baseUrl.trim() || provider?.defaultBaseUrl || "";
  // A stored key is only sent where it was entered for (scheme, host and port), and "local" is about one server.
  const hostChanged = profile !== null && originOf(effective(f.baseUrl)) !== originOf(profile.effectiveBaseUrl);
  const changeUrl = (url: string) =>
    setF((s) => {
      const moved = hostOf(effective(url)) !== hostOf(effective(s.baseUrl));
      return { ...s, baseUrl: url, local: moved ? false : s.local };
    });
  const set = <K extends keyof FormState>(k: K, v: FormState[K]) => setF((s) => ({ ...s, [k]: v }));
  const num = (s: string) => (s.trim() === "" ? null : Number(s));

  // A server at the address in the form, from "Find local models" or "Check this address": offer its models.
  const servers = checked && checked !== "checking" && checked.outcome === "found" ? [checked, ...known] : known;
  const server = compatible ? servers.find((s) => sameServer(s.baseUrl, f.baseUrl)) : undefined;
  const offered = server?.models ?? [];
  // Another server at the address: offer its list again rather than keep the typed box.
  const serverKey = server?.baseUrl ?? "";
  const lastServer = useRef(serverKey);
  useEffect(() => {
    if (lastServer.current !== serverKey) {
      lastServer.current = serverKey;
      setTypeOwn(false);
    }
  }, [serverKey]);
  const choose = offered.length > 0 && !typeOwn && (f.model === "" || offered.some((m) => m.name === f.model));
  const pickModel = (name: string) => {
    if (name === "__other__") {
      setF((s) => ({ ...s, model: "" }));
      setTypeOwn(true);
      return;
    }
    const m = offered.find((x) => x.name === name);
    setF((s) => ({
      ...s,
      model: name,
      // A new profile follows the model: embedding models are for search by meaning.
      purpose: profile === null && m ? (m.embedding ? "embedding" : "chat") : s.purpose,
      contextSize: profile === null && m?.contextSize != null && m.contextSize >= 256 ? String(m.contextSize) : s.contextSize,
      dimensions: profile === null && m?.embedding && m.dimensions != null ? String(m.dimensions) : s.dimensions,
    }));
  };

  const checkable = checked !== "checking" && f.baseUrl.trim() !== "";
  const check = async () => {
    if (!checkable) return;
    setChecked("checking");
    try {
      const d = await aiApi.discover(f.baseUrl.trim());
      setChecked(d.servers[0] ?? null);
    } catch (err) {
      setChecked({
        label: "The address you entered",
        server: null,
        address: f.baseUrl,
        baseUrl: f.baseUrl,
        outcome: "not-found",
        problem: (err as Error).message,
        guidance: null,
        suggestLocal: false,
        detail: null,
        gpuReported: false,
        models: [],
      });
    }
  };
  // A failed check moves focus to its reason, like a failed save.
  useEffect(() => {
    if (checked && checked !== "checking" && checked.outcome !== "found") checkRef.current?.focus();
  }, [checked]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    const body: AiProfileInput = {
      name: f.name.trim(),
      provider: f.provider,
      baseUrl: f.baseUrl.trim() || null,
      model: f.model.trim(),
      purpose: f.purpose,
      dimensions: f.purpose === "embedding" ? num(f.dimensions) : null,
      contextSize: num(f.contextSize),
      local: compatible && f.local,
      timeoutSeconds: Number(f.timeoutSeconds),
      maxConcurrent: Number(f.maxConcurrent),
    };
    if (f.removeKey) body.apiKey = "";
    else if (f.apiKey) body.apiKey = f.apiKey;
    try {
      const saved = profile ? await aiApi.updateProfile(profile.name, body) : await aiApi.createProfile(body);
      onSaved(saved, profile === null);
    } catch (err) {
      setError((err as Error).message);
      window.setTimeout(() => errorRef.current?.focus(), 0);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section aria-labelledby="profile-form-h" className="panel">
      <h2 id="profile-form-h" tabIndex={-1} ref={heading}>
        {profile ? `Edit ${profile.name}` : "Add a model"}
      </h2>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      <form className="form" onSubmit={submit}>
        <div className="field-row">
          <div className="field">
            <label htmlFor="ai-name">Name</label>
            <input id="ai-name" value={f.name} onChange={(e) => set("name", e.target.value)} required maxLength={63}
              autoComplete="off" aria-describedby="ai-name-hint" />
            <p id="ai-name-hint" className="hint">Lower-case letters, digits and hyphens, for example local-chat.</p>
          </div>
          <div className="field">
            <label htmlFor="ai-purpose">Purpose</label>
            <select id="ai-purpose" value={f.purpose} onChange={(e) => set("purpose", e.target.value as AiPurpose)}>
              <option value="chat">Chat: answers questions and drafts text</option>
              <option value="embedding">Embedding: turns text into numbers for search by meaning</option>
            </select>
          </div>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="ai-provider">Provider</label>
            <select id="ai-provider" value={f.provider} onChange={(e) => set("provider", e.target.value)}>
              {providers.map((p) => (
                <option key={p.id} value={p.id} disabled={f.purpose === "embedding" && !p.embeds}>
                  {p.label}
                  {f.purpose === "embedding" && !p.embeds ? " (no embedding models)" : ""}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="ai-model">Model name</label>
            {choose ? (
              <select id="ai-model" value={f.model} onChange={(e) => pickModel(e.target.value)} required
                aria-describedby="ai-model-hint">
                <option value="">Choose a model</option>
                {offered.map((m) => (
                  <option key={m.name} value={m.name}>
                    {m.name}
                    {m.sizeBytes != null ? ` (${formatSize(m.sizeBytes)})` : ""}
                    {m.embedding ? ", embedding" : ""}
                  </option>
                ))}
                <option value="__other__">Another model (type its name)</option>
              </select>
            ) : (
              <input id="ai-model" ref={modelRef} value={f.model} onChange={(e) => set("model", e.target.value)} required
                autoComplete="off" aria-describedby="ai-model-hint" />
            )}
            <p id="ai-model-hint" className="hint">
              {choose
                ? `The models ${server?.server ?? "the server"} at this address offers.`
                : "As the server knows it, for example qwen2.5-1.5b-instruct."}
            </p>
          </div>
        </div>
        <div className="field">
          <label htmlFor="ai-url">Base URL{provider?.defaultBaseUrl ? " (optional)" : ""}</label>
          <input id="ai-url" ref={urlRef} type="url" value={f.baseUrl}
            onChange={(e) => {
              changeUrl(e.target.value);
              setChecked(null);
            }}
            required={!provider?.defaultBaseUrl} placeholder={provider?.defaultBaseUrl ?? "http://ai:8080/v1"}
            autoComplete="off" aria-describedby="ai-url-hint" />
          <p id="ai-url-hint" className="hint">
            {provider?.defaultBaseUrl
              ? `Leave empty to use ${provider.defaultBaseUrl}.`
              : f.provider === "azure-openai"
                ? "Your resource's v1 address: https://RESOURCE.openai.azure.com/openai/v1"
                : "Where the API starts, usually ending in /v1. Ollama on the computer running Kilnmark's container: http://host.containers.internal:11434/v1 (Podman) or http://host.docker.internal:11434/v1 (Docker)."}
          </p>
          {compatible && (
            <div className="buttons">
              {/* aria-disabled, not disabled: the button keeps focus while it checks. */}
              <button type="button" onClick={check} aria-disabled={!checkable}>
                {checked === "checking" ? "Checking…" : "Check this address"}
              </button>
            </div>
          )}
          <div className="small ai-check">
            {/* Found: announced as a status. Not found: focus moves to the reason, which reads it out once. */}
            <p role="status">
              {checked === "checking" && "Checking the address…"}
              {checked && checked !== "checking" && checked.outcome === "found" && (
                <span className="ai-ok">
                  <strong>Found {checked.server}</strong> with {checked.models.length}{" "}
                  {checked.models.length === 1 ? "model" : "models"}
                  {checked.detail ? `. ${checked.detail}` : "."}
                  {checked.guidance ? ` ${checked.guidance}` : ""}
                  {f.baseUrl.trim().replace(/\/+$/, "") !== checked.baseUrl && (
                    <>
                      {" "}
                      <button
                        type="button"
                        className="link-button"
                        onClick={() => {
                          changeUrl(checked.baseUrl);
                          // The button goes once the address matches: move focus to the address itself.
                          window.setTimeout(() => urlRef.current?.focus(), 0);
                        }}
                      >
                        Use {checked.baseUrl}
                      </button>
                    </>
                  )}
                </span>
              )}
            </p>
            {checked && checked !== "checking" && checked.outcome !== "found" && (
              <p className="ai-bad" tabIndex={-1} ref={checkRef}>
                <strong>Not found: </strong>
                {checked.problem}
                {checked.guidance ? ` ${checked.guidance}` : ""}
              </p>
            )}
          </div>
        </div>
        {compatible && (
          <div className="field">
            <label className="choice">
              <input type="checkbox" checked={f.local} onChange={(e) => set("local", e.target.checked)}
                aria-describedby="ai-local-hint" />
              This server runs on our own network (local)
            </label>
            <p id="ai-local-hint" className="hint">
              Sources with the default AI rule let only local models see their metadata. Tick this only if no data
              sent to this server leaves your network. Changing the address clears it.
              {locked && " This server accepts a model as local only if its address is on one of its own networks."}
            </p>
          </div>
        )}
        <div className="field">
          <label htmlFor="ai-key">API key{provider?.needsKey ? "" : " (if the server needs one)"}</label>
          <input id="ai-key" type="password" value={f.apiKey} onChange={(e) => set("apiKey", e.target.value)}
            autoComplete="new-password"
            required={!f.removeKey && ((!!provider?.needsKey && !profile?.hasApiKey) || (!!profile?.hasApiKey && hostChanged))}
            disabled={f.removeKey} aria-describedby="ai-key-hint" />
          <p id="ai-key-hint" className="hint">
            {profile?.hasApiKey && hostChanged
              ? "The address points at another server now, so enter the key again (a stored key is only sent where it was entered for)."
              : profile?.hasApiKey
                ? "A key is stored. Leave this empty to keep it. Kilnmark never shows a stored key."
                : "Stored encrypted. Kilnmark never shows it again."}
          </p>
          {profile?.hasApiKey && !provider?.needsKey && (
            <label className="choice">
              <input type="checkbox" checked={f.removeKey} onChange={(e) => set("removeKey", e.target.checked)} />
              Remove the stored key
            </label>
          )}
        </div>
        <div className="field-row">
          {f.purpose === "embedding" && (
            <div className="field">
              <label htmlFor="ai-dims">Dimensions</label>
              <input id="ai-dims" type="number" min={1} max={16000} value={f.dimensions}
                onChange={(e) => set("dimensions", e.target.value)} required aria-describedby="ai-dims-hint" />
              <p id="ai-dims-hint" className="hint">Numbers per text, for example 384. Test tells you if it is wrong.</p>
            </div>
          )}
          <div className="field">
            <label htmlFor="ai-ctx">Context size in tokens (optional)</label>
            <input id="ai-ctx" type="number" min={256} value={f.contextSize} onChange={(e) => set("contextSize", e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="ai-timeout">Timeout in seconds</label>
            <input id="ai-timeout" type="number" min={1} max={600} value={f.timeoutSeconds}
              onChange={(e) => set("timeoutSeconds", e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="ai-conc">Calls at the same time</label>
            <input id="ai-conc" type="number" min={1} max={64} value={f.maxConcurrent}
              onChange={(e) => set("maxConcurrent", e.target.value)} required aria-describedby="ai-conc-hint" />
            <p id="ai-conc-hint" className="hint">Per Kilnmark process: with separate api and worker processes, the server can get more.</p>
          </div>
        </div>
        <div className="buttons">
          <button type="submit" className="primary" disabled={saving}>
            {saving ? "Saving…" : profile ? "Save changes" : "Add model"}
          </button>
          <button type="button" onClick={onCancel}>
            Cancel
          </button>
        </div>
      </form>
    </section>
  );
}
