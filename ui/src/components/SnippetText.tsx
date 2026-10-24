import type { ReactNode } from "react";
import type { Snippet } from "../api";
import "./documents.css";

/**
 * A passage of document text with its matched words marked. The text is only ever text: the
 * highlights are positions, turned into <mark> elements here, so nothing in a document is
 * treated as markup.
 */
export function SnippetText({ snippet }: { snippet: Snippet }) {
  const parts: ReactNode[] = [];
  let at = 0;
  const marks = [...snippet.highlights].sort((a, b) => a.start - b.start);
  marks.forEach((h, i) => {
    if (h.start < at) return;
    if (h.start > at) parts.push(snippet.text.slice(at, h.start));
    parts.push(<mark key={i}>{snippet.text.slice(h.start, h.start + h.length)}</mark>);
    at = h.start + h.length;
  });
  if (at < snippet.text.length) parts.push(snippet.text.slice(at));
  return <span className="snippet">{parts}</span>;
}
