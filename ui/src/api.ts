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

export type ConnectorSetting = {
  key: string;
  label: string;
  required: boolean;
  hint: string;
  multiple: boolean;
  example: string | null;
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
  docsAnchor: string;
  /** Whether host, port and database fields can build its address. */
  addressFields: boolean;
  driverBundled: boolean;
};
export type PlannedConnector = { name: string; category: string; family: string; capabilities: Capability[]; wave: string };
export type ConnectorCategory = { id: string; label: string; connectors: Connector[]; planned: PlannedConnector[] };
export type ConnectorCatalogue = { showPlanned: boolean; categories: ConnectorCategory[] };
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
      headers: headers(init?.body ? { "Content-Type": contentType } : {}, token),
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

export const qualityApi = {
  asset: (urn: string, unmask = false) => call<AssetQuality>(`/api/v1/quality/asset?${q(urn)}&unmask=${unmask}`),
  drift: (urn: string) => call<DriftEvent[]>(`/api/v1/quality/asset/drift?${q(urn)}&limit=20`),
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
  recommendations: (urn: string) => call<Recommendation[]>(`/api/v1/quality/asset/recommendations?${q(urn)}`),
  runAsset: (urn: string) => call<QualityRun>(`/api/v1/quality/asset/runs?${q(urn)}`, { method: "POST" }),
  run: (id: number) => call<QualityRun>(`/api/v1/quality/runs/${id}`),
  rules: (filter: { urn?: string; source?: string } = {}) =>
    call<RuleView[]>(
      `/api/v1/quality/rules?${filter.urn ? q(filter.urn) : ""}${
        filter.source ? `&source=${encodeURIComponent(filter.source)}` : ""
      }`,
    ),
  addRule: (r: RuleDraft) => call<QualityRule>("/api/v1/quality/rules", { method: "POST", body: JSON.stringify(r) }),
  updateRule: (id: number, r: RuleDraft) =>
    call<QualityRule>(`/api/v1/quality/rules/${id}`, { method: "PUT", body: JSON.stringify(r) }),
  deleteRule: (id: number) => call<null>(`/api/v1/quality/rules/${id}`, { method: "DELETE" }),
  preview: (r: RuleDraft) =>
    call<RulePreview>("/api/v1/quality/rules/preview", { method: "POST", body: JSON.stringify(r) }),
  runRule: (id: number) => call<QualityRun>(`/api/v1/quality/rules/${id}/run`, { method: "POST" }),
  result: (id: number) => call<RuleResult>(`/api/v1/quality/results/${id}`),
  issues: (status: string) => call<Issue[]>(`/api/v1/quality/issues?status=${encodeURIComponent(status)}`),
  setIssue: (id: number, status: Issue["status"]) =>
    call<Issue>(`/api/v1/quality/issues/${id}`, { method: "PUT", body: JSON.stringify({ status }) }),
};

const percentFormat = new Intl.NumberFormat("en-GB", { style: "percent", maximumFractionDigits: 1 });
export const formatShare = (share: number | null | undefined) => (share == null ? "–" : percentFormat.format(share));

/** A score in words as well as a number, so it never depends on colour. */
export function rating(score: number): { word: string; tone: "good" | "fair" | "poor" } {
  if (score >= 0.95) return { word: "Good", tone: "good" };
  if (score >= 0.8) return { word: "Fair", tone: "fair" };
  return { word: "Poor", tone: "poor" };
}
