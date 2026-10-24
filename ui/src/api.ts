/** Typed calls to the Kilnmark API. Every error carries the server's own message. */

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
  url: string;
  username: string | null;
  schemas: string[];
  hasPassword: boolean;
  createdAt: string;
  lastRun: Run | null;
};

export type SearchHit = {
  urn: string;
  source: string;
  kind: string;
  schema: string;
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
  schema: string;
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
  url: string;
  username: string;
  password: string;
  schemas: string[];
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

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { Accept: "application/json", ...(init?.body ? { "Content-Type": "application/json" } : {}) },
    });
  } catch {
    throw new ApiError("Cannot reach the Kilnmark server. Is it running?", 0);
  }
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  if (response.status === 422 && body?.rows) {
    return body as T; // an import report listing what to fix
  }
  if (!response.ok) {
    throw new ApiError(body?.error ?? `The server answered ${response.status}`, response.status);
  }
  return body as T;
}

export const api = {
  info: () => call<{ product: string; version: string; roles: string[] }>("/api/v1/info"),
  sources: () => call<Source[]>("/api/v1/sources"),
  addSource: (s: NewSource) => call<Source>("/api/v1/sources", { method: "POST", body: JSON.stringify(s) }),
  importSources: (text: string, format: "csv" | "json", dryRun: boolean) =>
    fetch(`/api/v1/sources/import?dryRun=${dryRun}`, {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": format === "csv" ? "text/csv" : "application/json" },
      body: text,
    }).then(async (r) => {
      const body = await r.json().catch(() => null);
      if (r.ok || (r.status === 422 && body?.rows)) return body as ImportReport;
      throw new ApiError(body?.error ?? `The server answered ${r.status}`, r.status);
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
