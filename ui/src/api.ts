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

export type Source = {
  name: string;
  kind: string;
  settings: Record<string, string>;
  hasPassword: boolean;
  createdAt: string;
  lastRun: Run | null;
};

export type ConnectorSetting = { key: string; label: string; required: boolean; hint: string; multiple: boolean };
export type Connector = { kind: string; displayName: string; settings: ConnectorSetting[] };

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
};

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
};

export type ImportRow = { line: number; name: string | null; status: "ok" | "created" | "error"; error: string | null };
export type ImportReport = { dryRun: boolean; created: number; errors: number; rows: ImportRow[] };

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

async function call<T>(path: string, init?: RequestInit, contentType = "application/json"): Promise<T> {
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
  if (response.status === 422 && body?.rows) {
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
  importSources: (text: string, format: "csv" | "json", dryRun: boolean) =>
    call<ImportReport>(
      `/api/v1/sources/import?dryRun=${dryRun}`,
      { method: "POST", body: text },
      format === "csv" ? "text/csv" : "application/json",
    ),
  scan: (name: string) => call<Run>(`/api/v1/sources/${encodeURIComponent(name)}/scans`, { method: "POST" }),
  run: (id: number) => call<Run>(`/api/v1/runs/${id}`),
  search: (q: string) => call<{ query: string; hits: SearchHit[] }>(`/api/v1/search?q=${encodeURIComponent(q)}`),
  asset: (urn: string) => call<Asset>(`/api/v1/assets/${encodeURIComponent(urn)}`),
};

const numbers = new Intl.NumberFormat("en-GB");
export const formatNumber = (n: number | null | undefined) => (n == null ? "–" : numbers.format(n));

const times = new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short" });
export const formatTime = (iso: string | null | undefined) => (iso ? times.format(new Date(iso)) : "–");

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

export type Preview = {
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
    call<Preview>("/api/v1/quality/rules/preview", { method: "POST", body: JSON.stringify(r) }),
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
