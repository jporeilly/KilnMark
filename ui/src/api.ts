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
};

export type ConnectorSetting = { key: string; label: string; required: boolean; hint: string; multiple: boolean };
/** containerSetting holds the containers (schemas, folders) chosen for scanning. */
export type Connector = {
  kind: string;
  displayName: string;
  settings: ConnectorSetting[];
  containerSetting: string;
  containerLabel: string;
  containerCounted: string;
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
