import "./documents.css";
import { formatIcon, Icon } from "./icons";

/**
 * A small picture for kinds of asset whose difference matters at a glance: documents (by type:
 * PDF, Word, email and so on), sheets and attachments. Decorative: the kind is always written
 * next to it as text.
 */
export function KindIcon({ kind, name }: { kind: string; name?: string }) {
  if (kind === "sheet") return <Icon name="sheet" size={16} className="doc-icon" />;
  if (kind !== "document") return null;
  return <Icon name={formatIcon("document", name)} size={16} className="doc-icon" />;
}
