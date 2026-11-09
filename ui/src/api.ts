/**
 * Typed calls to the Kilnmark API. Every error carries the server's own message. Calls send the
 * API token kept in sessionStorage; when the server stops accepting it, the token is dropped and
 * the app returns to the sign-in page.
 */

export type AccessRole = "viewer" | "steward" | "designer" | "admin";

/** Who is signed in. signInRequired is false when the server runs with sign-in turned off. */
export type Me = {
  /** What Kilnmark records them by: for a provider sign-in, the sso: name with the provider's ID. */
  name: string;
  /** What the screen greets them by: their name at the provider, or the token's name. Older servers send none. */
  displayName?: string;
  role: AccessRole;
  tokenId: number | null;
  expiresAt: string | null;
  signInRequired: boolean;
  /** Whether this token sees values of columns marked sensitive: designer tokens only (ADR 0027). */
  unmask: boolean;
  /** How they signed in: an API token, the organisation's identity provider (ADR 0036), or sign-in is off. */
  via: "token" | "provider" | "off";
};

/** Sign-in as the sign-in page needs it (ADR 0036). */
export type AuthStatus = {
  /** Whether sign-in with the organisation's identity provider is set up. */
  provider: boolean;
  providerName: string;
  /** Whether this browser has a session with the provider. */
  signedIn: boolean;
  /** Why that session gives no role, or null. */
  refusal: string | null;
};

export type ApiToken = {
  id: number;
  name: string;
  role: AccessRole;
  /** Whether it sees values of columns marked sensitive: designer tokens only. */
  unmask: boolean;
  status: "active" | "expired" | "set-aside" | "revoked";
  createdBy: string;
  createdAt: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
  revokedAt: string | null;
  /** Who asked for the unmask grant, while it waits for a second admin to approve it (ADR 0038). */
  unmaskRequestedBy: string | null;
};

/** A token just created: `token` is shown this once. */
export type CreatedToken = {
  id: number;
  name: string;
  role: AccessRole;
  unmask: boolean;
  createdAt: string;
  expiresAt: string | null;
  token: string;
  /** Set when the unmask grant waits for a second admin (ADR 0038). */
  unmaskRequestedBy?: string | null;
};

const RANK: Record<AccessRole, number> = { viewer: 0, steward: 1, designer: 2, admin: 3 };

/**
 * True if `role` includes `needed`: an admin can do what a steward can, and a steward what a viewer can.
 * Seeing unmasked values is not on this ladder: it is `Me.unmask` (designer tokens only).
 */
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

const SESSION_ENDED = "Your sign-in has ended. Sign in again.";

/** Whether this tab is signed in with the identity provider (a session cookie), not a token. */
let providerSession = false;
export const markProviderSession = (on: boolean) => {
  providerSession = on;
};

/**
 * Every request says it comes from Kilnmark's own pages (X-Requested-With): the server refuses
 * changes made with a provider session without it, and answers 499 rather than redirecting when
 * the session has ended (ADR 0036).
 */
function headers(extra: Record<string, string> = {}, token = currentToken()): Record<string, string> {
  return {
    Accept: "application/json",
    "X-Requested-With": "JavaScript",
    ...extra,
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

/** A 401 with a token means the token stopped working; one in a provider session means it ended. Back to sign-in. */
function signedOut(sentToken: boolean) {
  if (sentToken) {
    session.clear();
    window.dispatchEvent(new CustomEvent(SIGNED_OUT, { detail: NOT_ACCEPTED }));
  } else if (providerSession) {
    providerSession = false;
    window.dispatchEvent(new CustomEvent(SIGNED_OUT, { detail: SESSION_ENDED }));
  }
}

/** 401, or 499: the OIDC client's answer to a page's request once its session has ended. */
const notSignedIn = (status: number) => status === 401 || status === 499;

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

/** One table as Explore shows it (ADR 0045): names and types only, never a value. */
export type TableColumn = {
  name: string;
  position: number;
  sourceType: string;
  size: number | null;
  scale: number | null;
  nullable: boolean;
  hasDefault: boolean;
  generated: boolean;
  /** How Kilnmark reads it (int32, text…), or null when not known. */
  kind: string | null;
};
export type TableForeignKey = { name: string | null; table: string; columns: string[]; toTable: string; toColumns: string[] };
export type TableIndex = {
  name: string;
  columns: string[];
  unique: boolean;
  primaryKey: boolean;
  method: string | null;
  /** It covers only the rows a condition picks; the condition itself is never shown. */
  partial: boolean;
};
export type TableDetail = {
  path: string;
  schema: string;
  table: string;
  kind: string;
  columns: TableColumn[];
  primaryKey: string[];
  foreignKeys: TableForeignKey[];
  referencedBy: TableForeignKey[];
  indexes: TableIndex[];
  estimatedRows: number | null;
};
export type RowCount = { asset: string; rows: number };
export type SqlStatement = "create-table" | "add-columns" | "create-index" | "drop-index" | "drop-table" | "truncate";
export type SqlRequest = {
  statement: SqlStatement;
  asset: string;
  /** A source kind (postgresql, sqlserver, mysql, mariadb, oracle), or null for the source's own. */
  dialect: string | null;
  columns: {
    name: string;
    kind: string;
    length: number | null;
    precision: number | null;
    scale: number | null;
    nullable: boolean;
  }[] | null;
  index: { name: string; columns: string[] | null; unique: boolean | null } | null;
};
/** SQL the generator wrote (ADR 0045), never run, and what it left out. */
export type GeneratedSql = { dialect: string; sql: string[]; leftOut: string[] };
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
  if (notSignedIn(response.status)) {
    signedOut(token != null);
    if (response.status === 499) throw new ApiError(SESSION_ENDED, 401);
  }
  if (!response.ok) {
    throw new ApiError(body?.error ?? `The server answered ${response.status}`, response.status);
  }
  return body as T;
}

/** A GET whose answer is text (a file to save), with the same sign-in and errors as call. */
export async function callText(path: string, accept: string): Promise<string> {
  const token = currentToken();
  let response: Response;
  try {
    response = await fetch(path, { headers: headers({ Accept: accept }, token) });
  } catch {
    throw new ApiError("Cannot reach the Kilnmark server. Is it running?", 0);
  }
  const text = await response.text();
  if (notSignedIn(response.status)) signedOut(token != null);
  if (!response.ok) {
    let message = `The server answered ${response.status}`;
    try {
      message = JSON.parse(text)?.error ?? message;
    } catch {
      // not JSON
    }
    throw new ApiError(message, response.status);
  }
  return text;
}

/** Whether this tab is signed in with an API token, which a plain link cannot send (a provider session's cookie it can). */
export const signedInWithToken = () => currentToken() != null;

/** A GET whose answer is a file to save, with the same sign-in and errors as call. */
export async function callBlob(path: string): Promise<Blob> {
  const token = currentToken();
  let response: Response;
  try {
    response = await fetch(path, { headers: headers({ Accept: "*/*" }, token) });
  } catch {
    throw new ApiError("Cannot reach the Kilnmark server. Is it running?", 0);
  }
  if (notSignedIn(response.status)) signedOut(token != null);
  if (!response.ok) {
    let message = `The server answered ${response.status}`;
    try {
      message = JSON.parse(await response.text())?.error ?? message;
    } catch {
      // not JSON
    }
    throw new ApiError(message, response.status);
  }
  return response.blob();
}

/** "?node=a&level=warn", leaving out what is not set. */
function logQuery(q: Record<string, string | number | null | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(q)) if (v != null && v !== "") p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : "";
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

/** A JDBC driver an admin registered for the generic JDBC connector (Settings › JDBC drivers). */
export type RegisteredDriverItem = {
  name: string;
  fileName: string;
  sha256: string;
  driverClass: string;
  urlPrefix: string;
  /** The driver properties sources may set, lower case. */
  allowedProperties: string[];
  registeredBy: string;
  registeredAt: string;
  /** ready, or missing (the jar is gone), mismatch (the jar changed) or unreadable: then it is not used. */
  state: "ready" | "missing" | "mismatch" | "unreadable";
  message: string | null;
  usedBy: string[];
};

/** A jar in the drivers folder, for picking one to register. */
export type DriverFolderFile = {
  fileName: string;
  size: number;
  modified: string;
  /** The registration using it, if any. */
  registeredAs: string | null;
  /** A connector pins this file (installed under Settings › Drivers), so it is not registered. */
  pinned: boolean;
};

/** maxUploadBytes: the largest jar this server accepts as an upload; larger ones go in the folder by hand. */
export type DriverFolderListing = { folder: string; files: DriverFolderFile[]; maxUploadBytes: number };

/** One registration or removal, for the audit trail. */
export type DriverLogEntry = {
  action: "registered" | "removed";
  name: string;
  fileName: string;
  sha256: string;
  driverClass: string;
  urlPrefix: string;
  allowedProperties: string[];
  by: string;
  at: string;
};

export type DriverRegistration = {
  name: string;
  fileName: string;
  driverClass: string;
  urlPrefix: string;
  /** Property names sources may set, separated by commas or spaces. */
  allowedProperties: string;
};

export const api = {
  me: () => call<Me>("/api/v1/me"),
  authStatus: () => call<AuthStatus>("/api/v1/auth/status"),
  /**
   * Ends this browser's session with the identity provider's sign-in. endSession, when there is
   * one, is the provider's own sign-out page: going there ends the provider's session too.
   */
  signOutProvider: () => call<{ endSession?: string | null }>("/api/v1/auth/sign-out", { method: "POST" }),
  signIn: async (token: string) => {
    const me = await checkToken(token);
    session.save(token);
    return me;
  },
  tokens: () => call<ApiToken[]>("/api/v1/tokens"),
  createToken: (t: { name: string; role: AccessRole; expiresAt: string | null; unmask?: boolean }) =>
    call<CreatedToken>("/api/v1/tokens", { method: "POST", body: JSON.stringify(t) }),
  revokeToken: (id: number) => call<null>(`/api/v1/tokens/${id}`, { method: "DELETE" }),
  /** Gives a designer token the unmask grant it asked for: a second admin only (ADR 0038). */
  approveUnmask: (id: number) => call<ApiToken>(`/api/v1/tokens/${id}/unmask/approve`, { method: "POST" }),
  /** Refuses a designer token's request for the unmask grant; the token stays, without it. */
  refuseUnmask: (id: number) => call<ApiToken>(`/api/v1/tokens/${id}/unmask/refuse`, { method: "POST" }),
  info: () => call<{ product: string; version: string; roles: string[] }>("/api/v1/info"),
  sources: () => call<Source[]>("/api/v1/sources"),
  connectors: () => call<Connector[]>("/api/v1/connectors"),
  drivers: () => call<DriverItem[]>("/api/v1/drivers"),
  acceptDriver: (id: string) => call<DriverItem>(`/api/v1/drivers/${encodeURIComponent(id)}/accept`, { method: "POST" }),
  registeredDrivers: () => call<RegisteredDriverItem[]>("/api/v1/drivers/registered"),
  driverFiles: () => call<DriverFolderListing>("/api/v1/drivers/files"),
  driverHistory: () => call<DriverLogEntry[]>("/api/v1/drivers/registered/history"),
  uploadDriver: (fileName: string, file: Blob) =>
    call<DriverFolderFile>(`/api/v1/drivers/files/${encodeURIComponent(fileName)}`, { method: "POST", body: file },
      "application/octet-stream"),
  registerDriver: (r: DriverRegistration) =>
    call<RegisteredDriverItem>("/api/v1/drivers/registered", { method: "POST", body: JSON.stringify(r) }),
  removeDriver: (name: string) =>
    call<null>(`/api/v1/drivers/registered/${encodeURIComponent(name)}`, { method: "DELETE" }),
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
  /** One table's columns, keys, indexes and estimated rows, read live (Explore). */
  explore: (name: string, asset: string) =>
    call<TableDetail>(`/api/v1/sources/${encodeURIComponent(name)}/explore?asset=${encodeURIComponent(asset)}`),
  /** SQL for one table, written by Kilnmark as text and never run. */
  generateSql: (name: string, request: SqlRequest) =>
    call<GeneratedSql>(`/api/v1/sources/${encodeURIComponent(name)}/explore/sql`, {
      method: "POST",
      body: JSON.stringify(request),
    }),
  /** The exact number of rows of one table, counted by the database. */
  countRows: (name: string, asset: string) =>
    call<RowCount>(`/api/v1/sources/${encodeURIComponent(name)}/explore/count?asset=${encodeURIComponent(asset)}`),
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

/**
 * A count with its noun, singular for exactly one: "1 row", "2 rows", "1,204 sign-ins". Every count
 * the screen shows goes through this, so none reads "1 rows". `many` for a plural that is not the
 * noun with an s ("1 address", "2 addresses").
 */
export const plural = (n: number | null | undefined, one: string, many = `${one}s`) =>
  `${formatNumber(n)} ${n === 1 ? one : many}`;

/** The app's one way of telling the time: 24-hour, as in Britain ("8 Nov 2026, 17:17"; "17:17:06"). */
const times = new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short" });
const clockMinutes = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const clockSeconds = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
export const formatTime = (iso: string | null | undefined) => (iso ? times.format(new Date(iso)) : "–");
/**
 * A time of day alone, 24-hour as formatTime is: "17:17", or "17:17:06" with `seconds`, for a run's
 * timeline, where a node can take less than a minute. "–" when there is none.
 */
export const formatClock = (iso: string | null | undefined, seconds = false) =>
  iso ? (seconds ? clockSeconds : clockMinutes).format(new Date(iso)) : "–";

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
  /** Whether a mark of a sensitive column may be cleared (admins); missing from older servers. */
  canClearMarks?: boolean;
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
/** Designers may ask for sensitive values unmasked; for anyone else the server ignores it. */
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
export type PipelineProblem = { line: number; column: number; node: string | null; message: string };
export type PipelineColumn = { name: string; type: string };
/** columns: per output port, or null for a port whose columns are not known before a run. */
export type PipelineNodeOutline = {
  id: string;
  type: string | null;
  kind: string | null;
  line: number;
  inputs: string[];
  outputs: string[];
  columns: Record<string, PipelineColumn[] | null>;
};
export type PipelineEdge = { fromNode: string; fromPort: string; toNode: string; toPort: string };
export type PipelineValidation = {
  valid: boolean;
  name: string | null;
  description: string | null;
  problems: PipelineProblem[];
  notes: PipelineProblem[];
  nodes: PipelineNodeOutline[];
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
} & RunTries;

/**
 * A run's tries (ADR 0053): tryNumber is the try it is on or ended on, from 1, of the tries it may have by
 * itself; retryAt is when its next try starts while it waits for one. Absent from older servers.
 */
export type RunTries = {
  tryNumber?: number;
  tries?: number;
  retryAt?: string | null;
};

/** One ended try of a run: how it ended, when, why, what came next, and who asked for it by hand. */
export type RunTry = {
  number: number;
  status: "succeeded" | "failed" | "cancelled" | "lost";
  startedAt: string | null;
  finishedAt: string | null;
  /** null when it is held back from you, as the run's own error is. */
  message: string | null;
  next: string | null;
  askedBy: string | null;
};

/** Who answers for a pipeline or flow (ADR 0053); a team address is shown to designers and admins only. */
export type Owner = { entry: string | null; kind: "person" | "team"; label: string };
export type Owners = { owners: Owner[]; none: boolean; note: string | null };
export type PipelineSummary = {
  name: string;
  displayName: string;
  description: string | null;
  latestVersion: number;
  valid: boolean;
  updatedBy: string;
  updatedAt: string;
  lastRun: PipelineRun | null;
  /** "sample": added from the samples, listed in the Library, not on the Pipelines or Flows page (ADR 0043). Older servers send none. */
  library?: "sample" | null;
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
  /** "sample": added from the samples, listed in the Library, not on the Pipelines or Flows page (ADR 0043). Older servers send none. */
  library?: "sample" | null;
};
/** ports: rows per input and output port; null where the engine did not count a port on its own. */
export type PipelineNodeRun = {
  node: string;
  type: string;
  position: number;
  rowsIn: number | null;
  rowsOut: number | null;
  ports: { in?: Record<string, number | null>; out?: Record<string, number | null> } | null;
  peakMemory: number | null;
  durationMs: number | null;
  error: string | null;
  /** How the node ended; a node the engine stopped is not a clean finish. Null when not recorded. */
  outcome: "finished" | "ended_early" | "stopped" | "failed" | null;
  /**
   * When it started, had its first row (in; out for a node with no input), handed on its last row
   * (out; in for a node with no output) and ended. Null for a node that never started; missing from
   * older servers.
   */
  startedAt?: string | null;
  firstRowAt?: string | null;
  lastRowAt?: string | null;
  finishedAt?: string | null;
  /** Time it waited for rows on its inputs, and for room on its outputs (held up by the next node); the rest is working. */
  waitingForRowsMs?: number | null;
  heldUpMs?: number | null;
};
/** One node while a run goes: its rows so far and its rate since the snapshot before. */
export type PipelineNodeProgress = {
  node: string;
  state: "not_started" | "waiting" | "running" | "ended";
  rowsIn: number;
  rowsOut: number;
  rowsPerSecond: number;
  startedAt: string | null;
  firstRowAt: string | null;
  lastRowAt: string | null;
  finishedAt: string | null;
  /** So far: time it waited for rows, and was held up by the next node; how full its fullest output queue is now (0 to 1). */
  waitingForRowsMs?: number;
  heldUpMs?: number;
  outputFill?: number;
};
/** One connection while a run goes: rows so far, and whether its queue is full or empty now. */
export type PipelineEdgeProgress = {
  from: string;
  fromPort: string;
  to: string;
  toPort: string;
  rows: number;
  queued: number;
  capacity: number;
  full: boolean;
  empty: boolean;
  /** Batches waiting over capacity, 0 to 1. */
  fill?: number;
};
/**
 * Where a run's rows are, as the worker last wrote it (every two seconds while it runs). `slowest`
 * is the node holding the run back: its input queues are full while its output queues are not.
 */
export type PipelineRunProgress = {
  at: string;
  /** When the worker started on the run, and finished with it: every time on the run page counts from the worker's clock. */
  workerStartedAt?: string;
  workerFinishedAt?: string | null;
  slowest: string | null;
  nodes: PipelineNodeProgress[];
  edges: PipelineEdgeProgress[];
};
export type PipelineRunEventLevel = "info" | "warn" | "error";
/** One event of a run's log: Kilnmark's words, and the engine's detail unless it is held back (withheld says why). */
export type PipelineRunEvent = {
  seq: number;
  at: string;
  node: string | null;
  level: PipelineRunEventLevel;
  kind: string;
  text: string;
  detail: string | null;
  withheld: string | null;
};
export type PipelineRunEvents = { events: PipelineRunEvent[]; next: number | null };
export type RunLogFilter = { node?: string | null; level?: PipelineRunEventLevel | null };
export type PipelineWrittenAsset = {
  node: string;
  urn: string;
  source: string;
  target: string;
  mode: string;
  rows: number | null;
  registered: boolean;
};
/**
 * A file a run wrote, by its index in the run's record (what a download names; never a path).
 * state: ready (there as the run wrote it), unrecorded (there, but the run recorded no size, so it
 * may have changed), changed, deleted or unavailable. reason: why it cannot be downloaded by the
 * caller now; note: what to know when it can.
 */
export type PipelineWrittenFile = {
  index: number;
  node: string;
  source: string;
  path: string;
  format: string;
  rows: number | null;
  bytes: number | null;
  modified: string | null;
  state: "ready" | "unrecorded" | "changed" | "deleted" | "unavailable";
  downloadable: boolean;
  reason: string | null;
  note: string | null;
};
export type PipelineWrittenFiles = { run: number; finished: boolean; files: PipelineWrittenFile[] };
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
 * A node's quality in a run: each write (output) and each gate. `predicted` is the score worked out
 * before the run from the inputs' metadata: null until Kilnmark predicts scores.
 */
export type PipelineNodeQuality = {
  node: string;
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
  nodes: PipelineNodeRun[];
  written: PipelineWrittenAsset[];
  read: string[];
  lineage: { node: string; input: string; output: string }[];
  notes: string[];
  /** Missing from servers before measured quality. */
  quality?: PipelineNodeQuality[];
  /** Where the rows are, while the run goes (and the last snapshot once it has ended); missing from older servers. */
  progress?: PipelineRunProgress | null;
  /** Each ended try, the first first (ADR 0053); missing from older servers. */
  tries?: RunTry[];
};
/**
 * A run's rows a second over time, read on its own (pipelinesApi.series): t is ms from workerStartedAt; r each node's
 * rows/s, q its output queue's fill (0 to 1), in nodes' order. When stride changes the points were merged: read again.
 */
export type PipelineProgressSeries = { nodes: string[]; stride: number; points: { t: number; r: number[]; q: number[] }[] };
export type NodeTypeSetting = {
  key: string;
  label: string;
  kind: string;
  required: boolean;
  defaultValue: unknown;
  help: string;
  choices: string[];
};
export type NodeType = {
  type: string;
  kind: string;
  label: string;
  help: string;
  inputs: string[];
  manyInputs: string[];
  outputs: string[];
  settings: NodeTypeSetting[];
  /** write only: for each kind of source that can be written to, the keys of the writer settings it takes. */
  writerSettings?: Record<string, string[]> | null;
  /** The palette folder: Read, Write, Filter and route, Transform, Combine, Aggregate, Quality, AI or Other. */
  category?: string;
};

/** A source name the bundled samples use in place of a real source, and what it stands for. */
export type SamplePlaceholder = { name: string; category: string; description: string };
/**
 * What a sample or template explains about itself, read from the comment its file starts with: what it
 * shows (for a template, what it is for), why it matters, what to look for when it runs, and what it
 * needs. A part the comment leaves out is null. Lines are joined into sentences; an example to copy has
 * a line of its own.
 */
export type SampleExplanation = {
  title: string | null;
  shows: string | null;
  /** Why it matters: the need it meets, and who typically has it. */
  why: string | null;
  lookFor: string | null;
  needs: string | null;
};
/** One bundled sample; exists: a pipeline or flow of its name is already there. */
export type SampleInfo = {
  file: string;
  name: string;
  type: "pipeline" | "flow";
  description: string | null;
  sources: string[];
  exists: boolean;
  explanation: SampleExplanation | null;
};
/** Where the sample tables were last created, and the run that created them. */
export type SampleTables = { source: string; schema: string; lastRun: PipelineRun | null };
export type SampleCatalogue = { placeholders: SamplePlaceholder[]; samples: SampleInfo[]; tables: SampleTables | null };
export type SampleOutcome = {
  name: string;
  file: string;
  outcome: "added" | "exists" | "skipped" | "failed";
  reason: string | null;
  valid: boolean | null;
};

/** A template in the Library: a pipeline or flow to start one's own from, with <...> gaps (ADR 0043). */
export type Template = {
  file: string;
  name: string;
  type: "pipeline" | "flow";
  description: string | null;
  yaml: string;
  explanation: SampleExplanation | null;
};

/** The samples that ship with Kilnmark (GET/POST /api/v1/samples), and the templates (GET /api/v1/templates). */
export const samplesApi = {
  templates: () => call<Template[]>("/api/v1/templates"),
  list: () => call<SampleCatalogue>("/api/v1/samples"),
  /** sources: placeholder to saved source; a placeholder left out skips the samples that use it. */
  /** schema: the samples read the sample tables created there. */
  add: (sources: Record<string, string>, schema?: string) =>
    call<{ samples: SampleOutcome[]; added: number }>("/api/v1/samples", {
      method: "POST",
      body: JSON.stringify({ sources, schema: schema ?? null }),
    }),
  /** Copies Kilnmark's sample data into a schema of a database, with the sample-tables pipeline, and queues a run. */
  createTables: (source: string, schema: string) =>
    call<{ pipeline: string; run: PipelineRun; source: string; schema: string }>("/api/v1/samples/tables", {
      method: "POST",
      body: JSON.stringify({ source, schema }),
    }),
  /** Whether this server offers the samples; an admin turns them off or on. */
  /** allowed: false on a production install, which never has the samples (ADR 0051). */
  offered: () => call<{ offered: boolean; allowed?: boolean }>("/api/v1/samples/offered"),
  setOffered: (offered: boolean) =>
    call<{ offered: boolean }>("/api/v1/samples/offered", { method: "PUT", body: JSON.stringify({ offered }) }),
};

/** Whether the Starter Pack (ADR 0051) is on: everyone reads it, for the banner. */
/** The install's deployment mode (ADR 0051), fixed at install. */
export type DeploymentMode = "production" | "starter-pack";

export type StarterPackState = { mode: DeploymentMode; on: boolean; since: string | null; by: string | null };

/** One step of turning the Starter Pack on or off, and how it went. */
export type StarterPackStep = { step: string; outcome: "done" | "skipped" | "failed"; detail: string | null };

/** Settings › Starter Pack, for admins. */
export type StarterPackDetails = {
  on: boolean;
  since: string | null;
  by: string | null;
  /** The install's mode: on a production install the pack is never turned on. */
  mode: DeploymentMode;
  /** Whether the pack can be turned on here; notReady says why not. */
  ready: boolean;
  notReady: string | null;
  database: string;
  files: string | null;
  tablesRun: PipelineRun | null;
  samples: number;
  /**
   * The example people, which the control script makes and removes (Kilnmark holds no Keycloak
   * credential): its commands, and the kilnmark.com accounts that have signed in here.
   */
  people: { add: string; remove: string; signedIn: string[] };
};

export type StarterPackResult = {
  on: boolean;
  steps: StarterPackStep[];
  tablesRun: PipelineRun | null;
};

export const starterPackApi = {
  state: () => call<StarterPackState>("/api/v1/starter-pack"),
  details: () => call<StarterPackDetails>("/api/v1/starter-pack/details"),
  turnOn: () => call<StarterPackResult>("/api/v1/starter-pack", { method: "POST" }),
  turnOff: (confirm: string) =>
    call<StarterPackResult>("/api/v1/starter-pack", { method: "DELETE", body: JSON.stringify({ confirm }) }),
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
  owners: (name: string) => call<Owners>(`${pipe(name)}/owners`),
  setOwners: (name: string, owners: string[]) =>
    call<Owners>(`${pipe(name)}/owners`, { method: "PUT", body: JSON.stringify({ owners }) }),
  /** A run's rows a second over time, the points after `after` ms (all when left out). */
  series: (id: number, after?: number) =>
    call<PipelineProgressSeries>(`/api/v1/pipeline-runs/${id}/series${after != null ? `?after=${after}` : ""}`),
  /** A page of a run's log; level is the least serious shown (warn: warnings and errors). */
  events: (id: number, f: RunLogFilter & { after?: number; limit?: number } = {}) =>
    call<PipelineRunEvents>(`/api/v1/pipeline-runs/${id}/events${logQuery({ ...f, after: f.after ?? 0, limit: f.limit ?? 200 })}`),
  /** The whole log (with the same filters) as text, or NDJSON, for saving. */
  downloadLog: (id: number, format: "text" | "ndjson", f: RunLogFilter = {}) =>
    callText(
      `/api/v1/pipeline-runs/${id}/events/download${logQuery({ ...f, format })}`,
      format === "ndjson" ? "application/x-ndjson" : "text/plain",
    ),
  /** The files a run wrote, and whether the caller may download each now (ADR 0055). */
  files: (id: number) => call<PipelineWrittenFiles>(`/api/v1/pipeline-runs/${id}/files`),
  /** Where a file a run wrote is downloaded from: the run and the file's index, never a path. */
  fileUrl: (id: number, index: number) => `/api/v1/pipeline-runs/${id}/files/${index}`,
  /** The file, fetched with the sign-in's token, for a tab signed in with one (a link cannot carry it). */
  fileBlob: (id: number, index: number) => callBlob(`/api/v1/pipeline-runs/${id}/files/${index}`),
  /** The node catalogue with settings schemas, from which the canvas builds its palette and settings panels. */
  nodes: () => call<NodeType[]>("/api/v1/node-types"),
  /**
   * The first rows leaving one output of one node of the YAML as it stands (saved or not). Only the
   * node and the nodes before it run; nothing is written. Stewards and admins.
   */
  preview: (p: { yaml: string; node: string; port?: string | null; rows?: number }, signal?: AbortSignal) =>
    call<NodePreview>("/api/v1/pipelines/preview", { method: "POST", body: JSON.stringify(p), signal }),
};

// ---- Flows (docs/design/flows.md) ----

export type FlowProblem = { line: number; column: number; node: string | null; message: string };
/**
 * A parameter a flow declares. defaultVariable: its default is that server variable (${vars.name}),
 * whose value is not shown. required: it has no default, so a run must give it.
 */
export type FlowParameter = {
  name: string;
  type: "text" | "number" | "boolean" | "text list" | "number list";
  defaultValue: unknown;
  defaultVariable: string | null;
  required: boolean;
  description: string | null;
};
export type FlowValidation = { valid: boolean; name: string | null; problems: FlowProblem[]; parameters: FlowParameter[] };
export type FlowRunStatus = "queued" | "running" | "succeeded" | "failed" | "cancelled";
export type FlowRun = {
  id: number;
  flow: string;
  version: number;
  status: FlowRunStatus;
  requestedBy: string;
  requestedRole: string | null;
  requestedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  cancelRequested: boolean;
  /** In Kilnmark's words and the flow's own, never a pipeline's error. */
  error: string | null;
  /** The parameter values the run started with. */
  parameters: Record<string, unknown> | null;
  /** How many pipeline runs it has started (absent from older servers). */
  pipelineRuns?: number;
  /** The rows its pipeline runs wrote in all; null until one says. */
  rowsWritten?: number | null;
  /** The lowest measured quality score (0 to 1) among its pipeline runs; null when nothing was measured. */
  qualityScore?: number | null;
} & RunTries;
export type FlowSummary = {
  name: string;
  displayName: string;
  description: string | null;
  latestVersion: number;
  valid: boolean;
  updatedBy: string;
  updatedAt: string;
  lastRun: FlowRun | null;
  /** "sample": added from the samples, listed in the Library, not on the Pipelines or Flows page (ADR 0043). Older servers send none. */
  library?: "sample" | null;
};
export type FlowVersionSummary = PipelineVersionSummary;
export type FlowVersion = {
  n: number;
  yaml: string;
  message: string | null;
  author: string;
  createdAt: string;
  valid: boolean;
  problems: FlowProblem[];
  parameters: FlowParameter[];
};
export type FlowDetail = {
  name: string;
  displayName: string;
  description: string | null;
  latestVersion: number;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
  latest: FlowVersion | null;
  lastRun: FlowRun | null;
  /** "sample": added from the samples, listed in the Library, not on the Pipelines or Flows page (ADR 0043). Older servers send none. */
  library?: "sample" | null;
};
export type FlowNodeStatus = "pending" | "running" | "succeeded" | "failed" | "cancelled" | "skipped";
/** One node's part in a flow run; pipelineRunId is the pipeline run a run-pipeline node started. */
export type FlowNodeRun = {
  node: string;
  type: string;
  position: number;
  status: FlowNodeStatus;
  startedAt: string | null;
  finishedAt: string | null;
  pipelineRunId: number | null;
  pipeline: string | null;
  message: string | null;
  /**
   * What it reported: status, score, quarantined_share, rows_written, duration_s; rows_affected for
   * sql; items, succeeded and failed for for-each.
   */
  reported: Record<string, unknown> | null;
  /** A for-each node's items, each with its pipeline run; absent for other nodes. */
  items?: FlowItemRun[] | null;
  /**
   * The times of the pipeline run a run-pipeline node started: with the node's own times, how long
   * it waited before asking for the run, how long the run was queued for a worker, and how long it ran.
   */
  pipelineRunTimes?: PipelineRunTimes | null;
  /**
   * Every pipeline run of a node that was tried again after a failure (ADR 0041), by item then try;
   * absent or null when it never was.
   */
  tries?: FlowTry[] | null;
};
/** One try of a run-pipeline node, or of a for-each item (`item` from 0; null for run-pipeline): `number` from 1. */
export type FlowTry = {
  item: number | null;
  number: number;
  pipelineRunId: number;
  status: FlowNodeStatus | "queued";
  startedAt: string | null;
  finishedAt: string | null;
};
/** When a pipeline run was asked for, taken by a worker (started) and finished; null until each happens. */
export type PipelineRunTimes = { requestedAt: string | null; startedAt: string | null; finishedAt: string | null };
/**
 * One item of a for-each node in a run. `value` is null for viewers when the items came from a
 * parameter of the run. `status` is pending before its run starts, skipped if the node ended without
 * starting it, failed with no `pipelineRunId` if it could not be queued (the node's message says
 * why), else its pipeline run's.
 */
export type FlowItemRun = {
  item: number;
  value: string | number | null;
  status: FlowNodeStatus | "queued";
  pipelineRunId: number | null;
  /** When its pipeline run was asked for (absent from older servers). */
  requestedAt?: string | null;
  startedAt: string | null;
  finishedAt: string | null;
};
export type FlowRunDetail = { run: FlowRun; nodes: FlowNodeRun[]; tries?: RunTry[] };

const flowPath = (name: string) => `/api/v1/flows/${encodeURIComponent(name)}`;

export const flowsApi = {
  list: () => call<FlowSummary[]>("/api/v1/flows"),
  get: (name: string) => call<FlowDetail>(flowPath(name)),
  /** The name is the YAML's. */
  create: (p: { yaml: string; message?: string }) =>
    call<FlowDetail>("/api/v1/flows", { method: "POST", body: JSON.stringify(p) }),
  /** version: the latest version as read; the server refuses the save (409) if someone has saved another since. */
  save: (name: string, p: { yaml: string; message: string }, version?: number) =>
    call<FlowDetail>(flowPath(name), {
      method: "PUT",
      body: JSON.stringify(p),
      headers: version ? { "If-Match": `"${version}"` } : undefined,
    }),
  validate: (yaml: string, name?: string) =>
    call<FlowValidation>(name ? `${flowPath(name)}/validate` : "/api/v1/flows/validate", {
      method: "POST",
      body: JSON.stringify({ yaml }),
    }),
  versions: (name: string) => call<FlowVersionSummary[]>(`${flowPath(name)}/versions`),
  version: (name: string, n: number) => call<FlowVersion>(`${flowPath(name)}/versions/${n}`),
  run: (name: string, parameters: Record<string, unknown> = {}, version?: number) =>
    call<FlowRun>(`${flowPath(name)}/runs`, {
      method: "POST",
      body: JSON.stringify({ version: version ?? null, parameters }),
    }),
  runs: (name: string) => call<FlowRun[]>(`${flowPath(name)}/runs?limit=50`),
  runDetail: (id: number) => call<FlowRunDetail>(`/api/v1/flow-runs/${id}`),
  cancel: (id: number) => call<FlowRun>(`/api/v1/flow-runs/${id}/cancel`, { method: "POST" }),
  /** One more try of a failed run, keeping what succeeded (ADR 0053); 409 says why when it is not safe. */
  runAgain: (id: number) => call<FlowRun>(`/api/v1/flow-runs/${id}/run-again`, { method: "POST" }),
  owners: (name: string) => call<Owners>(`${flowPath(name)}/owners`),
  setOwners: (name: string, owners: string[]) =>
    call<Owners>(`${flowPath(name)}/owners`, { method: "PUT", body: JSON.stringify({ owners }) }),
  /** Deletes the flow with its versions, runs and schedules; refused (409) while a run is queued or running. */
  remove: (name: string) => call<null>(flowPath(name), { method: "DELETE" }),
};

// ---- Schedules (docs/design/schedules.md, ADR 0046) ----

/**
 * A schedule: a pipeline or a flow (one is set) run at the times a cron expression gives, in a
 * zone. parameters is null for viewers, who never see the values a run starts with. nextAt is
 * null while it is paused, or when it never fires again. lastOutcome says what happened the last
 * time it was due ("started", "starting", "skipped: …", "missed: …", "failed: …"; viewers see a
 * failure only as "failed"), with the run it started.
 */
export type Schedule = {
  id: number;
  pipeline: string | null;
  flow: string | null;
  cron: string;
  zone: string;
  parameters: Record<string, unknown> | null;
  runAs: "steward" | "designer";
  enabled: boolean;
  description: string | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
  nextAt: string | null;
  lastAt: string | null;
  lastOutcome: string | null;
  lastPipelineRun: number | null;
  lastFlowRun: number | null;
};
/** A schedule to add, or everything to replace one with. */
export type ScheduleChange = {
  pipeline?: string | null;
  flow?: string | null;
  cron: string;
  zone: string;
  parameters: Record<string, unknown>;
  runAs: "steward" | "designer";
  enabled: boolean;
  description: string | null;
};
/** When an expression would fire next (up to five times), or why it cannot be used. */
export type SchedulePreview = { valid: boolean; problem: string | null; cron: string; zone: string; next: string[] };

/** What a schedule runs, as the list filters take it. */
export type ScheduleTarget = { pipeline: string } | { flow: string };

/** The schedule's change as it is now, for a PUT that changes only `enabled`. */
export const scheduleChange = (s: Schedule): ScheduleChange => ({
  pipeline: s.pipeline,
  flow: s.flow,
  cron: s.cron,
  zone: s.zone,
  parameters: s.parameters ?? {},
  runAs: s.runAs,
  enabled: s.enabled,
  description: s.description,
});

const schedulePath = (id: number) => `/api/v1/schedules/${id}`;

export const schedulesApi = {
  /** Every schedule, or one pipeline's or flow's. */
  list: (target?: ScheduleTarget) =>
    call<Schedule[]>(
      target == null
        ? "/api/v1/schedules"
        : "pipeline" in target
          ? `/api/v1/schedules?pipeline=${encodeURIComponent(target.pipeline)}`
          : `/api/v1/schedules?flow=${encodeURIComponent(target.flow)}`,
    ),
  get: (id: number) => call<Schedule>(schedulePath(id)),
  create: (s: ScheduleChange) => call<Schedule>("/api/v1/schedules", { method: "POST", body: JSON.stringify(s) }),
  /** Replaces everything a schedule is set to: send enabled false to pause it, true to resume it. */
  replace: (id: number, s: ScheduleChange) => call<Schedule>(schedulePath(id), { method: "PUT", body: JSON.stringify(s) }),
  remove: (id: number) => call<null>(schedulePath(id), { method: "DELETE" }),
  /** The next five times an expression fires in a zone, or its problem. Nothing is saved. */
  preview: (cron: string, zone: string, signal?: AbortSignal) =>
    call<SchedulePreview>("/api/v1/schedules/preview", { method: "POST", body: JSON.stringify({ cron, zone }), signal }),
};

/**
 * A preview at a node: shaped as a source's preview (asset is "node.port"), plus whether these are
 * all the node's rows, how long it took, the columns whose values are hidden (made from columns
 * marked sensitive) and, in words, the nodes before it that read all their rows first.
 */
export type NodePreview = Preview & {
  node: string;
  port: string;
  complete: boolean;
  millis: number;
  masked: string[];
  waits: string[];
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
  | "pipeline_low_quality"
  | "flow_failed"
  | "flow_notice"
  | "pipeline_recovered"
  | "flow_recovered"
  | "run_taking_long";
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

/** One event of the audit log (Settings › Audit log, admins only). */
export type AuditEvent = {
  id: number;
  at: string;
  actor: string;
  tokenId: number | null;
  action: string;
  target: string;
  detail: Record<string, unknown>;
};

export const auditApi = {
  /** A page of events, newest first; `before` is the previous page's `next`. */
  list: (action: string | null, before: number | null, limit = 100) => {
    const q = new URLSearchParams({ limit: String(limit) });
    if (action) q.set("action", action);
    if (before != null) q.set("before", String(before));
    return call<{ events: AuditEvent[]; next: number | null }>(`/api/v1/audit?${q.toString()}`);
  },
};

/** One line of the access review (ADR 0037): an API token, or a person who signs in with the identity provider. */
export type AccessReviewEntry = {
  kind: "token" | "provider";
  name: string;
  role: AccessRole | null;
  unmask: boolean;
  status: string;
  createdBy: string | null;
  createdAt: string | null;
  expiresAt: string | null;
  /** For a token, its last use; for a person, their last sign-in. */
  lastUsedAt: string | null;
  signIns: number | null;
  /** A kilnmark.com example account, such as the Starter Pack's people (ADR 0051): not for real data. */
  example?: boolean;
};

/** Settings › Access review: who can do what, for admins. */
export type AccessReview = {
  generatedAt: string;
  /** The longest a token may last, in days; null when tokens may never expire. */
  tokenMaxLifetimeDays: number | null;
  entries: AccessReviewEntry[];
};

export const accessReviewApi = {
  get: () => call<AccessReview>("/api/v1/access-review"),
  /** The review as CSV, for keeping as evidence; each download is in the audit log. */
  csv: () => callText("/api/v1/access-review?format=csv", "text/csv"),
  /** The longest a new token may last, in days, or null for no limit. */
  tokenPolicy: () => call<{ maxLifetimeDays: number | null; unmaskApproval: boolean }>("/api/v1/tokens/policy"),
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
