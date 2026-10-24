/**
 * Generic line icons for categories and formats, drawn here: never a vendor's logo. Decorative
 * only (aria-hidden): every icon sits next to text that says the same.
 */
const PATHS: Record<string, string> = {
  database: "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zm0 0v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
  databases: "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zm0 0v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
  folder: "M3 6h6l2 2h10v11H3z",
  files: "M6 3h8l5 5v13H6zM14 3v5h5",
  bucket: "M4 6h16l-2 14H6zM4 6c0-1.1 3.6-2 8-2s8 .9 8 2",
  nosql: "M5 5h6v6H5zM13 13h6v6h-6zM11 8h4v5",
  streaming: "M3 8c3-3 6 3 9 0s6 3 9 0M3 14c3-3 6 3 9 0s6 3 9 0",
  applications: "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z",
  lakehouse: "M3 11l9-7 9 7M5 10v10h14V10M3 20h18",
  file: "M6 3h8l5 5v13H6zM14 3v5h5",
  other: "M12 3a9 9 0 100 18 9 9 0 000-18zM8 12h8",
};

export function Icon({ name, size = 28 }: { name: string; size?: number }) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinejoin="round"
      strokeLinecap="round"
      className="icon"
    >
      <path d={PATHS[name] ?? PATHS.other} />
    </svg>
  );
}
