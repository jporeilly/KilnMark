import "./documents.css";

/**
 * A small picture for kinds of asset whose difference matters at a glance: documents, sheets and
 * attachments. Decorative: the kind is always written next to it as text.
 */
export function KindIcon({ kind, name }: { kind: string; name?: string }) {
  if (kind === "sheet") {
    return (
      <svg className="doc-icon" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
        <rect x="1.5" y="2.5" width="13" height="11" rx="1" fill="none" stroke="currentColor" />
        <path d="M1.5 6.5h13M1.5 10h13M6 2.5v11" stroke="currentColor" fill="none" />
      </svg>
    );
  }
  if (kind !== "document") return null;
  const mail = name != null && /\.(eml|msg)$/i.test(name);
  if (mail) {
    return (
      <svg className="doc-icon" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
        <rect x="1.5" y="3.5" width="13" height="9" rx="1" fill="none" stroke="currentColor" />
        <path d="M1.5 4l6.5 5 6.5-5" stroke="currentColor" fill="none" />
      </svg>
    );
  }
  return (
    <svg className="doc-icon" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <path d="M3.5 1.5h6l3 3v10h-9z" fill="none" stroke="currentColor" />
      <path d="M9.5 1.5v3h3M5.5 8h5M5.5 10.5h5" stroke="currentColor" fill="none" />
    </svg>
  );
}
