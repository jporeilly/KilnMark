/** Words for what the browse tree holds. The icons themselves live in components/icons.tsx. */
import { formatIcon, Icon as AppIcon } from "../components/icons";

const LABELS: Record<string, string> = {
  table: "Table",
  view: "View",
  csv: "CSV",
  tsv: "TSV",
  json: "JSON",
  jsonl: "JSON Lines",
  xml: "XML",
  parquet: "Parquet",
  avro: "Avro",
  pdf: "PDF",
  word: "Word",
  excel: "Excel",
  powerpoint: "PowerPoint",
  email: "Email",
  text: "Text",
  file: "File",
  document: "Document",
  archive: "Archive",
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

/** A small decorative icon for an asset format or a container; the words beside it say the same. */
export function Icon({ name, file }: { name: string; file?: string }) {
  return <AppIcon name={formatIcon(name, file)} size={16} className="km-icon" />;
}

/** Icon names for a source by kind, and for its containers. */
export const sourceIcon = (kind: string) => (kind === "files" ? "folder" : kind === "s3" ? "bucket" : "database");
export const containerIcon = (kind: string) => (kind === "files" || kind === "s3" ? "folder" : "schema");
