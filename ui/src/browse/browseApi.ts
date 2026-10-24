/** Calls for browsing the catalogue, schema diagrams and relationships (see BrowseResource). */
import { call } from "../api";

export type BrowseSource = { name: string; label: string; kind: string; containers: number; assets: number };
export type BrowseContainer = { name: string; assets: number; kinds: Record<string, number> };
export type BrowseAsset = {
  urn: string;
  name: string;
  kind: string;
  /** What to show it as: table, view, csv, tsv, json, parquet, pdf, word, excel, email, text, file, document. */
  format: string;
  container: string;
  rowCount: number | null;
  fields: number;
  description: string | null;
};
export type Page<T> = { items: T[]; total: number; offset: number; limit: number };

export type RelationshipEnd = {
  urn: string;
  source: string;
  container: string;
  name: string;
  kind: string;
  fields: string[];
};

export type Evidence = {
  rule?: string;
  nameScore?: number;
  containment?: number | null;
  containmentMargin?: number | null;
  fromDistinct?: number;
  toDistinct?: number;
  toRows?: number;
  unique?: string;
  fromType?: string;
  toType?: string;
};

export type Relationship = {
  id: number;
  from: RelationshipEnd;
  to: RelationshipEnd;
  kind: "declared" | "inferred";
  status: "proposed" | "accepted" | "rejected";
  name: string | null;
  confidence: number | null;
  evidence: Evidence | null;
  decidedBy: string | null;
  decidedAt: string | null;
  foundAt: string;
  refreshedAt: string;
};

export type DiagramColumn = {
  name: string;
  sourceType: string;
  kind: string;
  nullable: boolean;
  primaryKey: boolean;
  foreignKey: boolean;
};

export type DiagramTable = {
  urn: string;
  source: string;
  container: string;
  name: string;
  kind: string;
  format: string;
  rowCount: number | null;
  primaryKey: string[];
  external: boolean;
  columns: DiagramColumn[];
};

export type Diagram = {
  tables: DiagramTable[];
  relationships: Relationship[];
  total: number;
  limit: number;
  truncated: boolean;
};

/** What a diagram is of: a container (optionally filtered), or one asset and its neighbours. */
export type DiagramScope =
  | { kind: "container"; source: string; container: string; q?: string }
  | { kind: "asset"; urn: string; depth: 1 | 2 };

const enc = encodeURIComponent;
const qs = (params: Record<string, string | number | undefined | null>) =>
  Object.entries(params)
    .filter(([, v]) => v != null && v !== "")
    .map(([k, v]) => `${enc(k)}=${enc(String(v))}`)
    .join("&");

export const browseApi = {
  sources: () => call<BrowseSource[]>("/api/v1/browse/sources"),
  containers: (source: string, q: string, offset = 0, limit = 100) =>
    call<Page<BrowseContainer>>(`/api/v1/browse/sources/${enc(source)}/containers?${qs({ q, offset, limit })}`),
  assets: (source: string, container: string, q: string, offset = 0, limit = 100) =>
    call<Page<BrowseAsset>>(
      `/api/v1/browse/sources/${enc(source)}/assets?container=${enc(container)}&${qs({ q, offset, limit })}`,
    ),
  diagram: (scope: DiagramScope) =>
    call<Diagram>(
      scope.kind === "container"
        ? `/api/v1/diagram?${qs({ source: scope.source, q: scope.q })}&container=${enc(scope.container)}`
        : `/api/v1/diagram?${qs({ asset: scope.urn, depth: scope.depth })}`,
    ),
  relationshipsOf: (urn: string) => call<Page<Relationship>>(`/api/v1/relationships?asset=${enc(urn)}`),
  relationships: (filter: { source?: string; status?: string; kind?: string; offset?: number; limit?: number }) =>
    call<Page<Relationship>>(`/api/v1/relationships?${qs(filter)}`),
  decide: (id: number, decision: "accept" | "reject") =>
    call<Relationship>(`/api/v1/relationships/${id}/${decision}`, { method: "POST" }),
};
