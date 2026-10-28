/**
 * Typed calls to the Kilnmark API. Every error carries the server's own message. Calls send the
 * API token kept in sessionStorage; when the server stops accepting it, the token is dropped and
 * the app returns to the sign-in page.
 */

export type AccessRole = "viewer" | "steward" | "admin";

/** Who is signed in. signInRequired is false when the server runs with sign-in turned off. */
export type Me = {
  name: string;
  role: AccessRole;
  tokenId: number | null;
  expiresAt: string | null;
  signInRequired: boolean;
};

export type ApiToken = {
  id: number;
  name: string;
  role: AccessRole;
  status: "active" | "expired" | "set-aside" | "revoked";
  createdBy: string;
  createdAt: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
  revokedAt: string | null;
};

/** A token just created: `token` is shown this once. */
export type CreatedToken = {
  id: number;
  name: string;
  role: AccessRole;
  createdAt: string;
  expiresAt: string | null;
  token: string;
};

const RANK: Record<AccessRole, number> = { viewer: 0, steward: 1, admin: 2 };

/** True if `role` includes `needed`: an admin can do what a steward can, and a steward what a viewer can. */
export const allows = (role: AccessRole | undefined, needed: AccessRole) => role != null && RANK[role] >= RANK[needed];

const TOKEN_KEY = "kilnmark.token";
let memory: string | null = null;

/** The API token for this browser tab only. Storage can be blocked, so every access is guarded. */
export const session = {
  token(): string | null {
    try {
      return window.sessionStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  save(token: string) {
    try {
      window.sessionStorage.setItem(TOKEN_KEY, token);
    } catch {
      // Storage blocked: the token lives in memory below for this page load.
    }
    memory = token;
  },
  clear() {
    try {
      window.sessionStorage.removeItem(TOKEN_KEY);
    } catch {
      // nothing stored
    }
    memory = null;
  },
};
const currentToken = () => session.token() ?? memory;

/** Fired on window when the server stops accepting the token; detail is the message to show. */
export const SIGNED_OUT = "kilnmark:signed-out";

const NOT_ACCEPTED =
  "You have been signed out: your API token was not accepted. It may have expired or been revoked. Sign in again.";

function headers(extra: Record<string, string> = {}, token = currentToken()): Record<string, string> {
  return { Accept: "application/json", ...extra, ...(token ? { Authorization: `Bearer ${token}` } : {}) };
}

/** A 401 with a token means the token stopped working: drop it and return to sign-in. */
function signedOut(sentToken: boolean) {
  if (!sentToken) return;
  session.clear();
  window.dispatchEvent(new CustomEvent(SIGNED_OUT, { detail: NOT_ACCEPTED }));
}

export type Run = {
  id: number;
  source: string;
  kind: string;
  status: "queued" | "running" | "succeeded" | "partial" | "failed";
  requestedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  assetsSeen: number;
  assetsFailed: number;
  rowsRead: number;
  detail: string | null;
};

/** name is the source's fixed key, part of every asset reference; people see displayName. */
export type Source = {
  name: string;
  kind: string;
  settings: Record<string, string>;
  hasPassword: boolean;
  createdAt: string;
  lastRun: Run | null;
  displayName: string;
  assets: number;
  category: string;
  /** For file sources: how many current files have each extension. */
  formats: Record<string, number>;
};

/** The kind of value a connector or common setting takes; values are always sent as text. */
export type SettingValueKind = "text" | "number" | "boolean" | "choice" | "url";
export type ConnectorSetting = {
  key: string;
  label: string;
  required: boolean;
  hint: string;
  multiple: boolean;
  example: string | null;
  /** Missing from older servers: the form then guesses from the key and hint (settingKinds.ts). */
  kind?: SettingValueKind;
  /** The allowed values of a choice; empty otherwise. */
  choices?: string[];
  /** The value used when the setting is left empty, as text, or null. */
  defaultValue?: string | null;
};
export type Capability = "discover" | "read" | "write" | "stream";
/** containerSetting holds the containers (schemas, folders) chosen for scanning. */
export type Connector = {
  kind: string;
  displayName: string;
  settings: ConnectorSetting[];
  containerSetting: string;
  containerLabel: string;
  containerCounted: string;
  category: string;
  family: string;
  capabilities: Capability[];
  formats: string[];
  icon: string;
  /** Letters for a product's tile (never a vendor logo); null for generic kinds, whose tile shows the icon. */
  monogram: string | null;
  docsAnchor: string;
  /** Whether host, port and database fields can build its address. */
  addressFields: boolean;
  driverBundled: boolean;
  /** bundled, ready (installed by an admin), or missing, mismatch or unreadable: then it needs its driver. */
  driver?: "bundled" | "ready" | "missing" | "mismatch" | "unreadable";
};
/** id is the kind it is expected to have, so a branding manifest can name it. */
export type PlannedConnector = {
  id: string;
  name: string;
  category: string;
  family: string;
  capabilities: Capability[];
  wave: string;
  monogram: string | null;
};
export type ConnectorCategory = { id: string; label: string; connectors: Connector[]; planned: PlannedConnector[] };
export type ConnectorCatalogue = { showPlanned: boolean; categories: ConnectorCategory[] };
/** The operator's branding folder (docs/guide/branding.md); images are links shown with <img>. */
export type ConnectorLook = { image: string | null; color: string | null; label: string | null };
export type Branding = { productName: string | null; logo: string | null; connectors: Record<string, ConnectorLook> };
export type AssetItem = { path: string; name: string; kind: string; readable: boolean };
export type AssetList = { container: string; total: number; offset: number; items: AssetItem[] };
export type PreviewColumn = { name: string; kind: string | null; sourceType: string | null };
export type Preview = {
  asset: string;
  columns: PreviewColumn[];
  rows: (string | null)[][];
  rowCount: number;
  truncated: boolean;
  details: Record<string, string>;
};

export type SearchHit = {
  urn: string;
  source: string;
  kind: string;
  container: string;
  name: string;
  description: string | null;
  rowCount: number | null;
  matchedFields: string[];
  score: number;
};

export type TopValue = { value: string; count: number };

export type Profile = {
  shape: string;
  rows: number;
  nulls: number;
  distinct: number;
  distinct_exact?: boolean;
  min?: string;
  median?: string;
  max?: string;
  mean?: string;
  min_length?: number;
  max_length?: number;
  true?: number;
  top?: TopValue[];
  p25?: string;
  p75?: string;
  /** Equal-width bins from the quantile sketch (numbers, dates and times). */
  histogram?: HistogramBin[];
};

export type HistogramBin = { from: string; to: string; count: number };

export type Field = {
  name: string;
  position: number;
  sourceType: string;
  kind: string;
  nullable: boolean;
  description: string | null;
  profile: Profile | null;
  profiledAt: string | null;
};

export type Asset = {
  urn: string;
  source: string;
  kind: string;
  container: string;
  name: string;
  description: string | null;
  primaryKey: string[];
  rowCount: number | null;
  rowCountComplete: boolean | null;
  firstSeenAt: string;
  lastScannedAt: string | null;
  removedAt: string | null;
  fields: Field[];
};

export type NewSource = {
  name: string;
  kind: string;
  settings: Record<string, string>;
  password: string;
  displayName?: string;
};

/** A setting every source has; choices is empty for free text. */
export type CommonSetting = {
  key: string;
  label: string;
  hint: string;
  multiple: boolean;
  choices: string[];
  defaultValue: string | null;
  kind?: SettingValueKind;
};

/** A source as the add and edit forms send it. source names the saved source a check is for. */
export type SourceForm = {
  name?: string;
  kind?: string;
  displayName?: string;
  settings: Record<string, string>;
  password?: string;
  clearPassword?: boolean;
  source?: string;
};

export type TestResult = { ok: boolean; problem: string | null; millis: number };
export type ContainerItem = { name: string; count: number; selected: boolean };
export type ContainerList = {
  setting: string;
  label: string;
  counted: string;
  containers: ContainerItem[];
  selected: string[];
  missing: string[];
  total: number;
  nothingToScan: boolean;
  warnings: string[];
};
export type HistoryRun = { run: Run; attempts: number | null; jobError: string | null };
export type Deleted = { name: string; assets: number; runs: number };

export type ImportRow = {
  line: number;
  name: string | null;
  status: "created" | "updated" | "unchanged" | "failed";
  error: string | null;
  notes: string[];
};
export type ImportReport = {
  dryRun: boolean;
  applied: boolean;
  created: number;
  updated: number;
  unchanged: number;
  failed: number;
  errors: number;
  rows: ImportRow[];
  batchId: number | null;
};
export type ImportOptions = { dryRun: boolean; skipErrors: boolean; remapHosts: boolean; test: boolean; scan: boolean };
export type ImportBatchRow = {
  line: number;
  name: string;
  test: "waiting" | "testing" | "ok" | "failed" | "skipped";
  problem: string | null;
  scan: "waiting" | "queued" | "skipped" | "failed";
  runId: number | null;
};
export type ImportBatch = {
  id: number;
  status: "running" | "finished" | "stopped";
  dryRun: boolean;
  test: boolean;
  scan: boolean;
  createdAt: string;
  finishedAt: string | null;
  rows: ImportBatchRow[];
};

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export async function call<T>(path: string, init?: RequestInit, contentType = "application/json"): Promise<T> {
  const token = currentToken();
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: headers(
        { ...(init?.body ? { "Content-Type": contentType } : {}), ...((init?.headers as Record<string, string>) ?? {}) },
        token,
      ),
    });
  } catch {
    throw new ApiError("Cannot reach the Kilnmark server. Is it running?", 0);
  }
  const text = await response.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = null;
  }
  if ((response.status === 422 && body?.rows) || response.status === 204) {
    return body as T; // an import report listing what to fix
  }
  if (response.status === 401) {
    signedOut(token != null);
  }
  if (!response.ok) {
    throw new ApiError(body?.error ?? `The server answered ${response.status}`, response.status);
  }
  return body as T;
}

/** Checks a token without storing it; resolves to who it belongs to. */
async function checkToken(token: string): Promise<Me> {
  let response: Response;
  try {
    response = await fetch("/api/v1/me", { headers: headers({}, token) });
  } catch {
    throw new ApiError("Cannot reach the Kilnmark server. Is it running?", 0);
  }
  const body = await response.json().catch(() => null);
  if (response.status === 401) {
    throw new ApiError(
      "That token was not accepted. Check that you copied all of it (it starts with km_), and that it has not " +
        "expired or been revoked.",
      401,
    );
  }
  if (!response.ok) {
    throw new ApiError(body?.error ?? `The server answered ${response.status}`, response.status);
  }
  return body as Me;
}

/** A driver Kilnmark cannot ship, as Settings › Drivers shows it (admins only). */
export type DriverItem = {
  id: string;
  coordinates: string;
  version: string;
  fileName: string;
  licence: string;
  licenceUrl: string;
  usedBy: string[];
  state: "ready" | "missing" | "mismatch" | "unreadable";
  message: string | null;
  folder: string;
  sha256: string;
  downloadUrl: string;
  /** Whether this server downloads drivers itself; otherwise an admin puts the file in the folder. */
  download: boolean;
  acceptedBy: string | null;
  acceptedAt: string | null;
};

export const api = {
  me: () => call<Me>("/api/v1/me"),
  signIn: async (token: string) => {
    const me = await checkToken(token);
    session.save(token);
    return me;
  },
  tokens: () => call<ApiToken[]>("/api/v1/tokens"),
  createToken: (t: { name: string; role: AccessRole; expiresAt: string | null }) =>
    call<CreatedToken>("/api/v1/tokens", { method: "POST", body: JSON.stringify(t) }),
  revokeToken: (id: number) => call<null>(`/api/v1/tokens/${id}`, { method: "DELETE" }),
  info: () => call<{ product: string; version: string; roles: string[] }>("/api/v1/info"),
  sources: () => call<Source[]>("/api/v1/sources"),
  connectors: () => call<Connector[]>("/api/v1/connectors"),
  drivers: () => call<DriverItem[]>("/api/v1/drivers"),
  acceptDriver: (id: string) => call<DriverItem>(`/api/v1/drivers/${encodeURIComponent(id)}/accept`, { method: "POST" }),
  /**
   * Public: the sign-in page shows the organisation's name and logo. Sent without the token, so a
   * stale one in this tab can never sign anyone out from here.
   */
  branding: async (): Promise<Branding> => {
    const r = await fetch("/api/v1/branding", { headers: { Accept: "application/json" } });
    if (!r.ok) throw new ApiError(`The server answered ${r.status}`, r.status);
    return (await r.json()) as Branding;
  },
  addSource: (s: NewSource) => call<Source>("/api/v1/sources", { method: "POST", body: JSON.stringify(s) }),
  importSources: (text: string, format: "csv" | "json", o: ImportOptions) =>
    call<ImportReport>(
      `/api/v1/sources/import?dryRun=${o.dryRun}&onError=${o.skipErrors ? "skip" : "stop"}` +
        `&remapHosts=${o.remapHosts}&test=${o.test}&scan=${o.scan}`,
      { method: "POST", body: text },
      format === "csv" ? "text/csv" : "application/json",
    ),
  importBatch: (id: number) => call<ImportBatch>(`/api/v1/imports/${id}`),
  source: (name: string) => call<Source>(`/api/v1/sources/${encodeURIComponent(name)}`),
  updateSource: (name: string, form: SourceForm) =>
    call<Source>(`/api/v1/sources/${encodeURIComponent(name)}`, { method: "PUT", body: JSON.stringify(form) }),
  deleteSource: (name: string) => call<Deleted>(`/api/v1/sources/${encodeURIComponent(name)}`, { method: "DELETE" }),
  testSource: (name: string) => call<TestResult>(`/api/v1/sources/${encodeURIComponent(name)}/test`, { method: "POST" }),
  testDraft: (form: SourceForm) => call<TestResult>("/api/v1/drafts/test", { method: "POST", body: JSON.stringify(form) }),
  containers: (name: string) => call<ContainerList>(`/api/v1/sources/${encodeURIComponent(name)}/containers`),
  draftContainers: (form: SourceForm) =>
    call<ContainerList>("/api/v1/drafts/containers", { method: "POST", body: JSON.stringify(form) }),
  history: (name: string) => call<HistoryRun[]>(`/api/v1/sources/${encodeURIComponent(name)}/history`),
  commonSettings: () => call<CommonSetting[]>("/api/v1/common-settings"),
  catalogue: () => call<ConnectorCatalogue>("/api/v1/connectors/catalogue"),
  address: (kind: string, host: string, port: string, database: string) =>
    call<Record<string, string>>(
      `/api/v1/connectors/${encodeURIComponent(kind)}/address?host=${encodeURIComponent(host)}` +
        `&port=${encodeURIComponent(port)}&database=${encodeURIComponent(database)}`,
    ),
  assets: (name: string, container: string, offset: number) =>
    call<AssetList>(
      `/api/v1/sources/${encodeURIComponent(name)}/assets?container=${encodeURIComponent(container)}&offset=${offset}`,
    ),
  draftAssets: (form: SourceForm, container: string, offset: number) =>
    call<AssetList>(`/api/v1/drafts/assets?container=${encodeURIComponent(container)}&offset=${offset}`, {
      method: "POST",
      body: JSON.stringify(form),
    }),
  preview: (name: string, asset: string, rows: number) =>
    call<Preview>(`/api/v1/sources/${encodeURIComponent(name)}/preview?asset=${encodeURIComponent(asset)}&rows=${rows}`),
  draftPreview: (form: SourceForm, asset: string, rows: number) =>
    call<Preview>(`/api/v1/drafts/preview?asset=${encodeURIComponent(asset)}&rows=${rows}`, {
      method: "POST",
      body: JSON.stringify(form),
    }),
  scan: (name: string) => call<Run>(`/api/v1/sources/${encodeURIComponent(name)}/scans`, { method: "POST" }),
  run: (id: number) => call<Run>(`/api/v1/runs/${id}`),
  search: (q: string) => call<{ query: string; hits: SearchHit[] }>(`/api/v1/search?q=${encodeURIComponent(q)}`),
  /** Assets by words in their names (no model call), optionally in one source: for pickers. */
  findAssets: (q: string, source?: string, limit = 50) =>
    call<{ query: string; hits: SearchHit[] }>(
      `/api/v1/search?q=${encodeURIComponent(q)}&mode=keyword&limit=${limit}${
        source ? `&source=${encodeURIComponent(source)}` : ""
      }`,
    ),
  asset: (urn: string) => call<Asset>(`/api/v1/assets/${encodeURIComponent(urn)}`),
};

const numbers = new Intl.NumberFormat("en-GB");
export const formatNumber = (n: number | null | undefined) => (n == null ? "–" : numbers.format(n));

const times = new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short" });
export const formatTime = (iso: string | null | undefined) => (iso ? times.format(new Date(iso)) : "–");

/** How long a run took, in words; "–" until it has started and finished. */
export function formatDuration(run: Run): string {
  if (!run.startedAt || !run.finishedAt) return "–";
  const seconds = Math.max(0, Math.round((Date.parse(run.finishedAt) - Date.parse(run.startedAt)) / 1000));
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.floor(seconds / 60);
  return minutes < 60 ? `${minutes} min ${seconds % 60} s` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

// ---- AI models, search by meaning and asking in plain English (ADR 0016) ----

export type AiProvider = { id: string; label: string; defaultBaseUrl: string | null; needsKey: boolean; embeds: boolean };

export type AiPurpose = "chat" | "embedding";

/** A model profile. The API key itself is never returned: only whether there is one and its last four characters. */
export type AiProfile = {
  name: string;
  provider: string;
  providerLabel: string;
  baseUrl: string | null;
  effectiveBaseUrl: string | null;
  model: string;
  purpose: AiPurpose;
  dimensions: number | null;
  contextSize: number | null;
  local: boolean;
  /** Whether the server treats it as local now: under the operator lock, only on a local network. */
  effectiveLocal: boolean;
  hasApiKey: boolean;
  apiKeyHint: string | null;
  timeoutSeconds: number;
  maxConcurrent: number;
  usedBy: string[];
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

/** What the admin sends. apiKey: undefined keeps the stored key, "" removes it. */
export type AiProfileInput = {
  name: string;
  provider: string;
  baseUrl: string | null;
  model: string;
  purpose: AiPurpose;
  dimensions: number | null;
  contextSize: number | null;
  local: boolean;
  apiKey?: string;
  timeoutSeconds: number;
  maxConcurrent: number;
};

export type AiFeature = { id: string; label: string; help: string; purpose: AiPurpose; profile: string | null };
export type AiSettings = { hostedModels: "allowed" | "forbidden"; lockedByServer: boolean; hostedAllowed: boolean };
export type AiTestResult = { ok: boolean; message: string; latencyMs: number; dimensions: number | null };
/** How a model uses the GPU, as its server reports it (Ollama does; others say nothing). */
export type AiGpuState = "gpu" | "split" | "cpu" | "not-loaded" | "unknown";
export type AiFoundModel = {
  name: string;
  sizeBytes: number | null;
  family: string | null;
  parameterSize: string | null;
  quantisation: string | null;
  embedding: boolean;
  loaded: boolean | null;
  gpu: AiGpuState;
  gpuPercent: number | null;
  vramBytes: number | null;
  gpuText: string;
  contextSize: number | null;
  /** Embedding models: numbers per text, when the server says. */
  dimensions: number | null;
};
/** One address checked by discovery: found, not found (with the reason), refused, or not on the local list. */
export type AiFoundServer = {
  label: string;
  server: string | null;
  address: string;
  baseUrl: string;
  outcome: "found" | "not-found" | "refused" | "not-local";
  problem: string | null;
  guidance: string | null;
  suggestLocal: boolean;
  detail: string | null;
  gpuReported: boolean;
  models: AiFoundModel[];
};
export type AiDiscovery = { servers: AiFoundServer[]; inContainer: boolean; locked: boolean };
export type AiStatus = {
  searchByMeaning: boolean;
  ask: boolean;
  embeddings: { profile: string; embedded: number; assets: number; running: boolean } | null;
};

export type AiUsageTotal = {
  day: string;
  profile: string | null;
  calls: number;
  ok: number;
  refused: number;
  failed: number;
  tokensIn: number;
  tokensOut: number;
  averageLatencyMs: number;
};
export type AiUsageRecent = {
  id: number;
  at: string;
  feature: string;
  profile: string | null;
  model: string | null;
  source: string | null;
  tokensIn: number | null;
  tokensOut: number | null;
  latencyMs: number;
  outcome: "ok" | "refused" | "failed" | "timeout" | "busy" | "cancelled";
  detail: string | null;
  caller: string;
};
export type AiUsage = { days: number; totals: AiUsageTotal[]; recent: AiUsageRecent[] };

/** A search result; matchedBy says whether it matched by words, by meaning, or by the question's filters. */
export type HybridHit = SearchHit & { matchedBy: ("words" | "meaning" | "filters")[] };
export type HybridResult = { query: string; mode: "hybrid" | "keyword"; note: string | null; hits: HybridHit[] };

export type QueryPlan = { text: string; kinds: string[]; sources: string[]; fields: string[]; ignored: string[] };
export type AskResult = {
  question: string;
  plan: QueryPlan | null;
  interpretedBy: string | null;
  note: string | null;
  mode: "hybrid" | "keyword";
  searchNote: string | null;
  hits: HybridHit[];
};

const enc = encodeURIComponent;

export const aiApi = {
  status: () => call<AiStatus>("/api/v1/ai/status"),
  providers: () => call<AiProvider[]>("/api/v1/ai/providers"),
  profiles: () => call<AiProfile[]>("/api/v1/ai/profiles"),
  createProfile: (p: AiProfileInput) =>
    call<AiProfile>("/api/v1/ai/profiles", { method: "POST", body: JSON.stringify(p) }),
  updateProfile: (name: string, p: AiProfileInput) =>
    call<AiProfile>(`/api/v1/ai/profiles/${enc(name)}`, { method: "PUT", body: JSON.stringify(p) }),
  deleteProfile: (name: string) => call<null>(`/api/v1/ai/profiles/${enc(name)}`, { method: "DELETE" }),
  testProfile: (name: string) => call<AiTestResult>(`/api/v1/ai/profiles/${enc(name)}/test`, { method: "POST" }),
  /** Looks for model servers: the usual places, or only `baseUrl` (with or without /v1). */
  discover: (baseUrl?: string) =>
    call<AiDiscovery>("/api/v1/ai/discover", { method: "POST", body: JSON.stringify(baseUrl ? { baseUrl } : {}) }),
  features: () => call<AiFeature[]>("/api/v1/ai/features"),
  route: (feature: string, profile: string | null) =>
    call<AiFeature>(`/api/v1/ai/features/${enc(feature)}`, { method: "PUT", body: JSON.stringify({ profile }) }),
  settings: () => call<AiSettings>("/api/v1/ai/settings"),
  saveSettings: (hostedModels: "allowed" | "forbidden") =>
    call<AiSettings>("/api/v1/ai/settings", { method: "PUT", body: JSON.stringify({ hostedModels }) }),
  usage: (days: number) => call<AiUsage>(`/api/v1/ai/usage?days=${days}`),
  refreshEmbeddings: () =>
    call<{ started: boolean; message: string }>("/api/v1/ai/embeddings/refresh", { method: "POST" }),
  search: (q: string, mode: "hybrid" | "keyword" = "hybrid") =>
    call<HybridResult>(`/api/v1/search?q=${enc(q)}&mode=${mode}`),
  ask: (question: string) =>
    call<AskResult>("/api/v1/search/ask", { method: "POST", body: JSON.stringify({ question }) }),
  /** Tokens an admin flagged as used by a local AI assistant: through MCP they also see local_only sources. */
  assistantTokens: () => call<{ tokenIds: number[] }>("/api/v1/ai/assistant-tokens"),
  setAssistantToken: (id: number, localAssistant: boolean) =>
    call<{ localAssistant: boolean }>(`/api/v1/ai/assistant-tokens/${id}`, {
      method: "PUT",
      body: JSON.stringify({ localAssistant }),
    }),
};

// ---- Documents (ADR 0017): profiles, parts, reading and finding text. Text is always plain text.

export type Highlight = { start: number; length: number };
export type Snippet = { text: string; highlights: Highlight[] };

export type PageLabel = { number: number; label: string | null };

export type MailHeaders = {
  from: string | null;
  to: string | null;
  cc: string | null;
  subject: string | null;
  date: string | null;
};

export type DocumentProfile = {
  readable: boolean;
  problem: string | null;
  format: string | null;
  contentType: string | null;
  size: number | null;
  pages: number | null;
  words: number | null;
  characters: number | null;
  language: string | null;
  title: string | null;
  author: string | null;
  created: string | null;
  modified: string | null;
  owner: string | null;
  tags: Record<string, string>;
  mail: MailHeaders | null;
  notes: string[];
  truncated: boolean;
  textKept: boolean;
  /** No text is kept, but stewards may have it read from the source. */
  textOnDemand: boolean;
  chunks: number;
  pageList: PageLabel[];
  readAt: string;
};

/** A link to an asset inside another, or to the one it sits in. */
export type AssetLink = {
  urn: string;
  kind: string;
  name: string;
  readable: boolean;
  description: string | null;
  removed: boolean;
};

/** The parts of an asset response that only documents, sheets and attachments have. */
export type DocumentAsset = Asset & {
  document?: DocumentProfile | null;
  parent?: AssetLink | null;
  parts?: AssetLink[];
};

export type SearchHitWithSnippet = SearchHit & { snippet?: Snippet | null };

export type DocumentPage = {
  urn: string;
  page: number;
  pageCount: number;
  pageKind: "page" | "slide" | "sheet" | "part";
  pages: PageLabel[];
  text: string;
  truncated: boolean;
  extractedNow: boolean;
};

export type FindResult = {
  query: string;
  total: number;
  pages: { number: number; label: string | null; count: number; snippet: Snippet }[];
};

const docQuery = (urn: string) => `urn=${encodeURIComponent(urn)}`;

export const documentsApi = {
  text: (urn: string, page: number) => call<DocumentPage>(`/api/v1/documents/text?${docQuery(urn)}&page=${page}`),
  find: (urn: string, q: string) =>
    call<FindResult>(`/api/v1/documents/find?${docQuery(urn)}&q=${encodeURIComponent(q)}`),
};


// ---- Data quality (docs/design/data-quality.md) ----

export type QualityStatus = "pass" | "warn" | "fail" | "error";
export type Severity = "low" | "medium" | "high";

export type DimensionScore = { dimension: string; score: number; reasons: string[] };

export type ProfileVersion = {
  id: number;
  assetUrn: string;
  runId: number | null;
  observedAt: string;
  rowCount: number | null;
  rowCountComplete: boolean | null;
  estimate: boolean;
  sampledRows: number | null;
  samplePercent: number | null;
  estimatedRows: number | null;
  origin: string;
  method: string;
  baseline: boolean;
};

export type Measure = {
  field: string;
  dimension: string;
  score: number;
  reason: string;
  evidence: Record<string, unknown>;
};

export type Shape = {
  shape: string;
  count: number;
  share: number;
  regex: string;
  accepted: boolean;
  examples?: (string | null)[];
};

export type ColumnPatterns = {
  field: string;
  values: number;
  other: number;
  collapsed: boolean;
  exact: boolean;
  shapes: Shape[];
  otherExamples?: (string | null)[];
  formats: { format: string; count: number }[];
  suggestion?: { regex: string; coverage: number; basis: string; shapes: string[] };
};

export type AcceptedPattern = {
  id: number;
  assetUrn: string;
  field: string;
  shape: string;
  regex: string;
  ruleId: number | null;
  acceptedBy: string;
  acceptedAt: string;
};

export type AssetQuality = {
  urn: string;
  canSeeData: boolean;
  canUnmask: boolean;
  version: ProfileVersion | null;
  scores: { asset: DimensionScore[]; fields: Record<string, DimensionScore[]> };
  measures: Measure[];
  sensitive: string[];
  accepted: AcceptedPattern[];
  patterns: ColumnPatterns[];
};

export type RuleType =
  | "not_null"
  | "unique"
  | "allowed_values"
  | "matches_pattern"
  | "in_range"
  | "freshness"
  | "row_count"
  | "row_count_change"
  | "references"
  | "custom_sql";

export type QualityRule = {
  id: number;
  assetUrn: string;
  field: string | null;
  name: string;
  type: RuleType;
  dimension: string;
  params: Record<string, unknown>;
  thresholdUnit: "share" | "count";
  warnAbove: number | null;
  failAbove: number;
  actions: { webhook: string | null; task: boolean };
  enabled: boolean;
  origin: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

export type FailingRows = { columns: string[]; rows: (string | null)[][] };

export type RuleResult = {
  id: number;
  ruleId: number;
  assetUrn: string;
  status: QualityStatus;
  total: number | null;
  failing: number | null;
  method: string;
  failingQuery: string | null;
  failingRows?: FailingRows | null;
  message: string | null;
  errorDetail?: string | null;
  estimate?: boolean;
  observedAt: string;
};

export type RuleView = { rule: QualityRule; latest: RuleResult | null };

/** A rule as sent to the server; thresholds are shares (0 to 1) or counts. */
export type RuleDraft = {
  assetUrn: string;
  field?: string | null;
  name?: string;
  type: RuleType;
  dimension?: string;
  params?: Record<string, unknown>;
  thresholdUnit?: "share" | "count";
  warnAbove?: number | null;
  failAbove?: number | null;
  actions?: { webhook?: string | null; task?: boolean };
  enabled?: boolean;
};

export type RulePreview = {
  checked: number;
  failing: number;
  share: number;
  status: QualityStatus;
  message: string;
  failingRows: FailingRows | null;
};

export type DriftEvent = {
  id: number;
  assetUrn: string;
  field: string | null;
  kind: string;
  change: string;
  severity: Severity;
  message: string;
  detail?: string | null;
  against: "previous" | "baseline";
  detectedAt: string;
};

export type Issue = {
  id: number;
  assetUrn: string;
  field: string | null;
  ruleId: number | null;
  driftEventId: number | null;
  title: string;
  detail: string | null;
  severity: Severity;
  status: "open" | "resolved" | "dismissed";
  createdAt: string;
  updatedAt: string;
  closedBy: string | null;
};

export type SampleRows = {
  urn: string;
  kept: boolean;
  message?: string;
  method?: string;
  keyFields?: string[];
  seen?: number;
  takenAt?: string;
  columns?: string[];
  masked?: string[];
  rows?: (string | null)[][];
};

export type Recommendation = {
  field: string | null;
  type: RuleType;
  dimension: string;
  name: string;
  params: Record<string, unknown>;
  reason: string;
};

export type QualityRun = { id: number; status: "queued" | "running" | "succeeded" | "failed"; detail: string | null };

const q = (urn: string) => `urn=${encodeURIComponent(urn)}`;
/** Admins may ask for sensitive values unmasked; servers that do not mask ignore it. */
const unmasked = (unmask: boolean) => (unmask ? "&unmask=true" : "");

/**
 * Parameters the server held back because the column is sensitive (or the SQL from viewers):
 * exactly {"hidden": true}. Sent back unchanged, they keep the stored values.
 */
export const isHidden = (params: Record<string, unknown> | null | undefined) =>
  params != null && Object.keys(params).length === 1 && params.hidden === true;

/** A webhook address the server held back from this role. */
export const HIDDEN_WEBHOOK = "(set)";

export const qualityApi = {
  asset: (urn: string, unmask = false) => call<AssetQuality>(`/api/v1/quality/asset?${q(urn)}&unmask=${unmask}`),
  drift: (urn: string, unmask = false) =>
    call<DriftEvent[]>(`/api/v1/quality/asset/drift?${q(urn)}&limit=20${unmasked(unmask)}`),
  samples: (urn: string, unmask = false) =>
    call<SampleRows>(`/api/v1/quality/asset/samples?${q(urn)}&unmask=${unmask}`),
  setSensitive: (urn: string, field: string, sensitive: boolean) =>
    call<null>(`/api/v1/quality/asset/sensitive?${q(urn)}`, {
      method: "PUT",
      body: JSON.stringify({ field, sensitive }),
    }),
  accept: (urn: string, field: string, shape: string, regex?: string) =>
    call<AcceptedPattern>(`/api/v1/quality/asset/patterns?${q(urn)}`, {
      method: "POST",
      body: JSON.stringify({ field, shape, regex }),
    }),
  unaccept: (urn: string, id: number) =>
    call<null>(`/api/v1/quality/asset/patterns/${id}?${q(urn)}`, { method: "DELETE" }),
  recommendations: (urn: string, unmask = false) =>
    call<Recommendation[]>(`/api/v1/quality/asset/recommendations?${q(urn)}${unmasked(unmask)}`),
  runAsset: (urn: string) => call<QualityRun>(`/api/v1/quality/asset/runs?${q(urn)}`, { method: "POST" }),
  run: (id: number) => call<QualityRun>(`/api/v1/quality/runs/${id}`),
  rules: (filter: { urn?: string; source?: string } = {}, unmask = false) =>
    call<RuleView[]>(
      `/api/v1/quality/rules?${filter.urn ? q(filter.urn) : ""}${
        filter.source ? `&source=${encodeURIComponent(filter.source)}` : ""
      }${unmasked(unmask)}`,
    ),
  addRule: (r: RuleDraft) => call<QualityRule>("/api/v1/quality/rules", { method: "POST", body: JSON.stringify(r) }),
  /** version: the rule's updatedAt as read; the server refuses the change (409) if it has changed since. */
  updateRule: (id: number, r: RuleDraft, version?: string) =>
    call<QualityRule>(`/api/v1/quality/rules/${id}`, {
      method: "PUT",
      body: JSON.stringify(r),
      headers: version ? { "If-Match": `"${version}"` } : undefined,
    }),
  rule: (id: number, unmask = false) =>
    call<RuleView>(`/api/v1/quality/rules/${id}${unmask ? "?unmask=true" : ""}`),
  deleteRule: (id: number) => call<null>(`/api/v1/quality/rules/${id}`, { method: "DELETE" }),
  preview: (r: RuleDraft) =>
    call<RulePreview>("/api/v1/quality/rules/preview", { method: "POST", body: JSON.stringify(r) }),
  runRule: (id: number) => call<QualityRun>(`/api/v1/quality/rules/${id}/run`, { method: "POST" }),
  result: (id: number, unmask = false) =>
    call<RuleResult>(`/api/v1/quality/results/${id}${unmask ? "?unmask=true" : ""}`),
  /** A rule's recent results, newest first. */
  results: (ruleId: number, limit = 10) => call<RuleResult[]>(`/api/v1/quality/rules/${ruleId}/results?limit=${limit}`),
  /** Issues by status; the server returns at most `limit` (100 when left out, 500 at most). */
  issues: (status: string, limit?: number) =>
    call<Issue[]>(`/api/v1/quality/issues?status=${encodeURIComponent(status)}${limit ? `&limit=${limit}` : ""}`),
  setIssue: (id: number, status: Issue["status"]) =>
    call<Issue>(`/api/v1/quality/issues/${id}`, { method: "PUT", body: JSON.stringify({ status }) }),
};

const percentFormat = new Intl.NumberFormat("en-GB", { style: "percent", maximumFractionDigits: 1 });
export const formatShare = (share: number | null | undefined) => (share == null ? "–" : percentFormat.format(share));

/** A quality score (0 to 1) as people read it everywhere: a whole percentage, "98%". */
export const formatScore = (score: number) => `${Math.round(score * 100)}%`;

/** A score in words as well as a number, so it never depends on colour. */
export function rating(score: number): { word: string; tone: "good" | "fair" | "poor" } {
  if (score >= 0.95) return { word: "Good", tone: "good" };
  if (score >= 0.8) return { word: "Fair", tone: "fair" };
  return { word: "Poor", tone: "poor" };
}

// ---- Pipelines (docs/design/pipelines.md) ----

/** line and column count from 1; 0 when the problem has no place in the text. */
export type PipelineProblem = { line: number; column: number; step: string | null; message: string };
export type PipelineColumn = { name: string; type: string };
/** columns: per output port, or null for a port whose columns are not known before a run. */
export type PipelineStepOutline = {
  id: string;
  type: string | null;
  kind: string | null;
  line: number;
  inputs: string[];
  outputs: string[];
  columns: Record<string, PipelineColumn[] | null>;
};
export type PipelineEdge = { fromStep: string; fromPort: string; toStep: string; toPort: string };
export type PipelineValidation = {
  valid: boolean;
  name: string | null;
  description: string | null;
  problems: PipelineProblem[];
  notes: PipelineProblem[];
  steps: PipelineStepOutline[];
  edges: PipelineEdge[];
};
export type PipelineRunStatus = "queued" | "running" | "succeeded" | "failed" | "cancelled";
export type PipelineRun = {
  id: number;
  pipeline: string;
  version: number;
  status: PipelineRunStatus;
  requestedBy: string;
  requestedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  cancelRequested: boolean;
  error: string | null;
  rowsWritten: number | null;
  /** The lowest measured quality score (0 to 1) of what the run wrote; null when nothing was measured. */
  qualityScore?: number | null;
  /** The pipeline's quality.min_score for the version that ran; null when it has none. */
  qualityThreshold?: number | null;
};
export type PipelineSummary = {
  name: string;
  displayName: string;
  description: string | null;
  latestVersion: number;
  valid: boolean;
  updatedBy: string;
  updatedAt: string;
  lastRun: PipelineRun | null;
};
export type PipelineVersionSummary = {
  n: number;
  message: string | null;
  author: string;
  createdAt: string;
  valid: boolean;
  problems: number;
};
export type PipelineVersion = {
  n: number;
  yaml: string;
  message: string | null;
  author: string;
  createdAt: string;
  valid: boolean;
  problems: PipelineProblem[];
};
export type PipelineDetail = {
  name: string;
  displayName: string;
  description: string | null;
  latestVersion: number;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
  latest: PipelineVersion | null;
  lastRun: PipelineRun | null;
};
/** ports: rows per input and output port; null where the engine did not count a port on its own. */
export type PipelineStepRun = {
  step: string;
  type: string;
  position: number;
  rowsIn: number | null;
  rowsOut: number | null;
  ports: { in?: Record<string, number | null>; out?: Record<string, number | null> } | null;
  peakMemory: number | null;
  durationMs: number | null;
  error: string | null;
  /** How the step ended; a step the engine stopped is not a clean finish. Null when not recorded. */
  outcome: "finished" | "ended_early" | "stopped" | "failed" | null;
};
export type PipelineWrittenAsset = {
  step: string;
  urn: string;
  source: string;
  target: string;
  mode: string;
  rows: number | null;
  registered: boolean;
};
/** A score from 0 to 1, overall and per dimension, with its main reasons (words and counts only). */
export type QualityScore = { overall: number; dimensions: Record<string, number>; reasons: string[] };
export type GateRuleResult = {
  name: string;
  type: string;
  field: string | null;
  dimension: string;
  /** asset: one of the asset's own rules; inline: written on the gate. */
  origin: "asset" | "inline";
  /** error: the rule could not run, for example its column did not arrive. */
  status: "pass" | "warn" | "fail" | "error";
  failing: number;
  total: number;
  message: string | null;
};
export type GateOutcome = {
  onFail: "stop" | "quarantine" | "warn";
  decision: "passed" | "warned" | "quarantined" | "stopped";
  asset: string | null;
  rowsIn: number;
  rowsPassed: number;
  rowsFailing: number;
  rowsQuarantined: number;
  minScore: number | null;
  maxFailingShare: number | null;
  stopReason: string | null;
  rules: GateRuleResult[];
};
/**
 * A step's quality in a run: each write (output) and each gate. `predicted` is the score worked out
 * before the run from the inputs' metadata: null until Kilnmark predicts scores.
 */
export type PipelineStepQuality = {
  step: string;
  role: "output" | "gate";
  asset: string | null;
  measured: QualityScore | null;
  /** Worked out when the run started, from the catalogue's metadata: an estimate. */
  predicted: QualityScore | null;
  note: string | null;
  gate: GateOutcome | null;
  /** What the estimate said beside its score (pipelines/prediction.ts, PredictionDetail); missing from older servers. */
  prediction?: {
    unknown?: string[];
    assumptions?: string[];
    rows?: number | null;
    gate?: { decision: string | null; rowsPassed: number | null; rowsQuarantined: number | null } | null;
  } | null;
};
export type PipelineRunDetail = {
  run: PipelineRun;
  steps: PipelineStepRun[];
  written: PipelineWrittenAsset[];
  read: string[];
  lineage: { step: string; input: string; output: string }[];
  notes: string[];
  /** Missing from servers before measured quality. */
  quality?: PipelineStepQuality[];
};
export type StepTypeSetting = {
  key: string;
  label: string;
  kind: string;
  required: boolean;
  defaultValue: unknown;
  help: string;
  choices: string[];
};
export type StepType = {
  type: string;
  kind: string;
  label: string;
  help: string;
  inputs: string[];
  manyInputs: string[];
  outputs: string[];
  settings: StepTypeSetting[];
  /** write only: for each kind of source that can be written to, the keys of the writer settings it takes. */
  writerSettings?: Record<string, string[]> | null;
  /** The palette folder: Read, Write, Filter and route, Transform, Combine, Aggregate, Quality, AI or Other. */
  category?: string;
};

const pipe = (name: string) => `/api/v1/pipelines/${encodeURIComponent(name)}`;

export const pipelinesApi = {
  list: () => call<PipelineSummary[]>("/api/v1/pipelines"),
  get: (name: string) => call<PipelineDetail>(pipe(name)),
  create: (p: { name: string; displayName?: string; yaml: string; message?: string }) =>
    call<PipelineDetail>("/api/v1/pipelines", { method: "POST", body: JSON.stringify(p) }),
  /** version: the latest version as read; the server refuses the save (409) if someone has saved another since. */
  save: (name: string, p: { yaml: string; message: string }, version?: number) =>
    call<PipelineDetail>(pipe(name), {
      method: "PUT",
      body: JSON.stringify(p),
      headers: version ? { "If-Match": `"${version}"` } : undefined,
    }),
  remove: (name: string) => call<null>(pipe(name), { method: "DELETE" }),
  validate: (yaml: string, name?: string) =>
    call<PipelineValidation>(name ? `${pipe(name)}/validate` : "/api/v1/pipelines/validate", {
      method: "POST",
      body: JSON.stringify({ yaml }),
    }),
  versions: (name: string) => call<PipelineVersionSummary[]>(`${pipe(name)}/versions`),
  version: (name: string, n: number) => call<PipelineVersion>(`${pipe(name)}/versions/${n}`),
  run: (name: string, version?: number) =>
    call<PipelineRun>(`${pipe(name)}/runs`, { method: "POST", body: JSON.stringify({ version: version ?? null }) }),
  runs: (name: string) => call<PipelineRun[]>(`${pipe(name)}/runs?limit=50`),
  runDetail: (id: number) => call<PipelineRunDetail>(`/api/v1/pipeline-runs/${id}`),
  cancel: (id: number) => call<PipelineRun>(`/api/v1/pipeline-runs/${id}/cancel`, { method: "POST" }),
  /** The step catalogue with settings schemas, from which the canvas builds its palette and settings panels. */
  steps: () => call<StepType[]>("/api/v1/steps"),
};

// ---- Notifications (docs/guide/notifications.md): the in-app feed, each person's choices, and email for admins.

export type NotificationKindId =
  | "rule_failed"
  | "rule_recovered"
  | "issue_opened"
  | "scan_failed"
  | "drift"
  | "connection_failed"
  | "pipeline_failed"
  | "pipeline_low_quality";
/** The feed itself (items, unread count, marking read) is in src/notifications/api.ts, shared with the shell's bell. */
export type NotificationSeverity = "high" | "medium" | "low" | "info";
export type EmailStatus = "sent" | "queued" | "failed" | "skipped";
export type KindChoice = { id: NotificationKindId; label: string; description: string };
/** What a person hears about; sources null means every source. */
export type MyNotifications = {
  available: boolean;
  unavailable: string | null;
  saved: boolean;
  email: string | null;
  enabled: boolean;
  kinds: NotificationKindId[];
  minSeverity: NotificationSeverity;
  mode: "immediate" | "digest";
  digestHour: number;
  sources: string[] | null;
  inAppKinds: NotificationKindId[];
  timeZone: string;
  mailReady: boolean;
  mailNote: string | null;
  kindChoices: KindChoice[];
  confirmationRequired: boolean;
  confirmed: boolean;
  confirmationSentAt: string | null;
  paused: string | null;
  allowedDomains: string[];
};
export type SubscriptionInput = {
  email: string;
  enabled: boolean;
  kinds: NotificationKindId[];
  minSeverity: NotificationSeverity;
  mode: "immediate" | "digest";
  digestHour: number;
  /** Ignored when allSources is true. */
  sources: string[] | null;
  allSources: boolean;
};
export type MailSecurity = "none" | "starttls" | "tls";
export type MailSettingsView = {
  enabled: boolean;
  host: string | null;
  port: number | null;
  effectivePort: number;
  security: MailSecurity;
  username: string | null;
  hasPassword: boolean;
  fromAddress: string | null;
  fromName: string | null;
  baseUrl: string | null;
  allowPlainAuth: boolean;
  allowedDomains: string[];
  requireConfirmation: boolean;
  maxAttempts: number;
  rateLimitPerHour: number;
  undeliveredHours: number;
  undeliveredAction: "digest" | "drop";
  /** Settings that come from the server's configuration, by name (host, allowed-domains, ...). */
  locked: string[];
  saved: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
  ready: boolean;
  notReady: string | null;
  timeZone: string;
  pausedAddresses: { kind: "person" | "team"; name: string | null; email: string }[];
  limits: {
    minAttempts: number;
    maxAttempts: number;
    minRatePerHour: number;
    maxRatePerHour: number;
    minUndeliveredHours: number;
    maxUndeliveredHours: number;
  };
};
/** password: leave it out to keep the stored one, "" to remove it. */
export type MailSettingsInput = {
  enabled: boolean;
  host: string;
  port: number | null;
  security: MailSecurity;
  username: string;
  password?: string;
  fromAddress: string;
  fromName: string;
  baseUrl: string;
  allowPlainAuth: boolean;
  allowedDomains: string[];
  requireConfirmation: boolean;
  maxAttempts: number;
  rateLimitPerHour: number;
  undeliveredHours: number;
  undeliveredAction: "digest" | "drop";
};
export type TeamAddress = Omit<SubscriptionInput, "allSources"> & {
  id: number;
  name: string;
  paused: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};
export type MailLogEntry = {
  id: number;
  at: string;
  recipient: string;
  subscriberId: number | null;
  kind: string;
  eventIds: number[];
  subject: string | null;
  status: "sent" | "retrying" | "failed" | "rate_limited";
  attempt: number;
  error: string | null;
};

/** Notification settings and, for admins, email: the feed is notificationsApi in src/notifications/api.ts. */
export const notifyApi = {
  mine: () => call<MyNotifications>("/api/v1/me/notifications"),
  saveMine: (s: SubscriptionInput & { inAppKinds: NotificationKindId[] }) =>
    call<MyNotifications>("/api/v1/me/notifications", { method: "PUT", body: JSON.stringify(s) }),
  confirm: (code: string) =>
    call<MyNotifications>("/api/v1/me/notifications/confirm", { method: "POST", body: JSON.stringify({ code }) }),
  resendConfirmation: () => call<MyNotifications>("/api/v1/me/notifications/confirm/resend", { method: "POST" }),
  settings: () => call<MailSettingsView>("/api/v1/mail/settings"),
  saveSettings: (s: MailSettingsInput) =>
    call<MailSettingsView>("/api/v1/mail/settings", { method: "PUT", body: JSON.stringify(s) }),
  test: (to: string) =>
    call<{ ok: boolean; message: string }>("/api/v1/mail/test", { method: "POST", body: JSON.stringify({ to }) }),
  teams: () => call<TeamAddress[]>("/api/v1/mail/teams"),
  addTeam: (t: SubscriptionInput & { name: string }) =>
    call<TeamAddress>("/api/v1/mail/teams", { method: "POST", body: JSON.stringify(t) }),
  updateTeam: (id: number, t: SubscriptionInput & { name: string }) =>
    call<TeamAddress>(`/api/v1/mail/teams/${id}`, { method: "PUT", body: JSON.stringify(t) }),
  deleteTeam: (id: number) => call<null>(`/api/v1/mail/teams/${id}`, { method: "DELETE" }),
  log: (status: string, limit = 200) =>
    call<MailLogEntry[]>(`/api/v1/mail/log?status=${encodeURIComponent(status)}&limit=${limit}`),
};
