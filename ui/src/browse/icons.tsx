/** Small line icons for the browse tree. Decorative: every icon sits next to words that say the same. */

const LABELS: Record<string, string> = {
  table: "Table",
  view: "View",
  csv: "CSV",
  tsv: "TSV",
  json: "JSON",
  parquet: "Parquet",
  pdf: "PDF",
  word: "Word",
  excel: "Excel",
  email: "Email",
  text: "Text",
  file: "File",
  document: "Document",
};

/** The words for an asset's format, as the tree and lists show it. */
export const formatLabel = (format: string) => LABELS[format] ?? "Other document";

/** Words for a source's kind, and the kind of container it holds. */
export function sourceWords(kind: string): { kind: string; container: string; containers: string } {
  if (kind === "files") return { kind: "Folder", container: "folder", containers: "folders" };
  if (kind === "s3") return { kind: "S3 bucket", container: "prefix", containers: "prefixes" };
  const names: Record<string, string> = { postgresql: "PostgreSQL", sqlserver: "SQL Server" };
  return { kind: names[kind] ?? kind, container: "schema", containers: "schemas" };
}

const PATHS: Record<string, string> = {
  // A grid: rows and columns.
  table: "M2 3h12v10H2zM2 6.5h12M2 10h12M6 6.5V13",
  // A table seen through a frame: dashed outline.
  view: "M2 3h12v10H2zM2 6.5h12M6 6.5V13M9 9.5l2 1.5-2 1.5",
  // A page with a folded corner, used for every file kind with its own mark inside.
  page: "M4 1.5h5.5L12.5 4.5V14.5H4zM9.5 1.5v3h3",
  email: "M2 4h12v8H2zM2 4l6 5 6-5",
  database: "M3 3.5c0-1 2.2-1.8 5-1.8s5 .8 5 1.8v9c0 1-2.2 1.8-5 1.8s-5-.8-5-1.8zM3 3.5c0 1 2.2 1.8 5 1.8s5-.8 5-1.8M3 8c0 1 2.2 1.8 5 1.8s5-.8 5-1.8",
  folder: "M1.5 4h4.5l1.5 1.5h7v7.5h-13z",
  bucket: "M2.5 4h11l-1.5 9.5h-8zM2.5 4c0-.8 2.5-1.5 5.5-1.5s5.5.7 5.5 1.5",
  schema: "M2 2.5h5v4H2zM9 9.5h5v4H9zM4.5 6.5v5H9",
};

const MARKS: Record<string, string> = {
  csv: "M6 9.5h4M6 11.5h4M6 7.5h4",
  tsv: "M6 9.5h4M6 11.5h4M6 7.5h4",
  json: "M7 7c-1 0-1 .8-1 1.5S5.5 10 5.5 10s.5 0 .5 1.5S6 13 7 13M9 7c1 0 1 .8 1 1.5s.5 1.5.5 1.5-.5 0-.5 1.5S10 13 9 13",
  parquet: "M6 7.5h4v2H6zM6 10.5h4v2H6z",
  pdf: "M6 12.5V8h1.5a1.2 1.2 0 010 2.4H6",
  word: "M5.5 8l1 4.5L8 9l1.5 3.5 1-4.5",
  excel: "M6 8l4 4.5M10 8l-4 4.5",
  text: "M6 8h4M6 10h4M6 12h3",
};

/** The icon for an asset format, a source kind or a container. */
export function Icon({ name }: { name: string }) {
  const own = PATHS[name];
  const mark = MARKS[name];
  const d = own ?? PATHS.page;
  return (
    <svg className="km-icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
      <path d={d} fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" strokeLinecap="round" />
      {!own && mark && (
        <path d={mark} fill="none" stroke="currentColor" strokeWidth="1.1" strokeLinecap="round" strokeLinejoin="round" />
      )}
    </svg>
  );
}

/** Icon names for a source by kind, and for its containers. */
export const sourceIcon = (kind: string) => (kind === "files" ? "folder" : kind === "s3" ? "bucket" : "database");
export const containerIcon = (kind: string) => (kind === "files" || kind === "s3" ? "folder" : "schema");
