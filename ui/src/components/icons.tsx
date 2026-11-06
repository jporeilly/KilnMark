import {
  IconAffiliate,
  IconAlertTriangle,
  IconAntenna,
  IconApi,
  IconAppWindow,
  IconArrowRight,
  IconArrowsShuffle,
  IconBell,
  IconBook,
  IconBooks,
  IconBraces,
  IconBucket,
  IconBuildingFactory2,
  IconBuildingWarehouse,
  IconChartBar,
  IconChartLine,
  IconCheck,
  IconChecks,
  IconChevronDown,
  IconCircleCheck,
  IconCalendarTime,
  IconClockHour4,
  IconCloudComputing,
  IconCloudDataConnection,
  IconColumns3,
  IconContrast2,
  IconCpu,
  IconDatabase,
  IconDeviceDesktop,
  IconExternalLink,
  IconFile,
  IconFileCode,
  IconFileDatabase,
  IconFileImport,
  IconFileSpreadsheet,
  IconFileText,
  IconFileTypeCsv,
  IconFileTypeDoc,
  IconFileTypeHtml,
  IconFileTypePdf,
  IconFileTypePpt,
  IconFileTypeTxt,
  IconFileTypeXls,
  IconFileTypeXml,
  IconFileTypeZip,
  IconFiles,
  IconFolder,
  IconFolderShare,
  IconHierarchy2,
  IconHome,
  IconInbox,
  IconJson,
  IconKey,
  IconLayoutColumns,
  IconLayoutDashboard,
  IconListCheck,
  IconLogout,
  IconMail,
  IconMailFast,
  IconMailX,
  IconMarkdown,
  IconMenu2,
  IconNews,
  IconMessages,
  IconMoon,
  IconPlayerPlay,
  IconPlug,
  IconPlugConnected,
  IconPlus,
  IconRoute,
  IconSchema,
  IconSearch,
  IconServer2,
  IconSettings,
  IconShieldCheck,
  IconSitemap,
  IconSparkles,
  IconStack2,
  IconSun,
  IconTable,
  IconTableShortcut,
  IconTimeline,
  IconTopologyStar3,
  IconTransfer,
  IconUser,
  IconUsers,
  IconWaveSine,
  IconWorld,
  IconX,
  type TablerIcon,
} from "@tabler/icons-react";

/**
 * Every icon the UI shows, by name, in one place: categories, connector families, file formats,
 * the kinds in the browse tree and a few for the interface. Icons are Tabler Icons (MIT,
 * docs/legal/third-party-ui.md), imported one by one so only these are bundled. Never a vendor
 * logo: products get monogram tiles (ConnectorTile), and operators may add their own logos
 * through the branding folder.
 */
const ICONS: Record<string, TablerIcon> = {
  // Categories of connector.
  databases: IconDatabase,
  nosql: IconTopologyStar3,
  files: IconFiles,
  streaming: IconWaveSine,
  applications: IconApi,
  lakehouse: IconBuildingWarehouse,
  other: IconPlug,

  // Connector families (the sub-groups in the gallery).
  relational: IconDatabase,
  "cloud warehouse": IconCloudDataConnection,
  document: IconBraces,
  search: IconSearch,
  "wide column": IconColumns3,
  "key-value": IconKey,
  graph: IconSitemap,
  "local or network folder": IconFolderShare,
  "object storage": IconBucket,
  "file transfer": IconTransfer,
  "distributed file system": IconServer2,
  "message broker": IconMessages,
  iot: IconAntenna,
  "change data capture": IconArrowsShuffle,
  "cloud streams": IconCloudComputing,
  industrial: IconBuildingFactory2,
  apis: IconApi,
  email: IconMail,
  office: IconFileSpreadsheet,
  "business applications": IconAppWindow,
  "table formats": IconStack2,

  // Places and containers.
  database: IconDatabase,
  folder: IconFolder,
  bucket: IconBucket,
  schema: IconSchema,

  // What the browse tree holds.
  table: IconTable,
  view: IconTableShortcut,
  column: IconLayoutColumns,
  file: IconFile,
  sheet: IconFileSpreadsheet,

  // File formats.
  csv: IconFileTypeCsv,
  tsv: IconFileTypeCsv,
  json: IconJson,
  jsonl: IconJson,
  xml: IconFileTypeXml,
  parquet: IconColumns3,
  avro: IconFileDatabase,
  orc: IconColumns3,
  yaml: IconFileCode,
  excel: IconFileTypeXls,
  pdf: IconFileTypePdf,
  word: IconFileTypeDoc,
  powerpoint: IconFileTypePpt,
  html: IconFileTypeHtml,
  markdown: IconMarkdown,
  text: IconFileTypeTxt,
  archive: IconFileTypeZip,
  documents: IconFileText,
  timeline: IconTimeline,

  // Interface.
  "theme-system": IconDeviceDesktop,
  "theme-light": IconSun,
  "theme-dark": IconMoon,
  "theme-contrast": IconContrast2,
  web: IconWorld,
  chart: IconChartBar,

  // Navigation (the sidebar's items; colours are set by the sidebar, see src/nav.ts).
  home: IconHome,
  sources: IconDatabase,
  pipelines: IconRoute,
  flows: IconHierarchy2,
  schedules: IconCalendarTime,
  browse: IconSitemap,
  relationships: IconAffiliate,
  rules: IconShieldCheck,
  issues: IconAlertTriangle,
  docs: IconBook,
  library: IconBooks,
  "whats-new": IconNews,
  settings: IconSettings,

  // The top bar, cards and lists.
  ask: IconSparkles,
  bell: IconBell,
  menu: IconMenu2,
  close: IconX,
  "chevron-down": IconChevronDown,
  check: IconCheck,
  checks: IconChecks,
  "circle-check": IconCircleCheck,
  user: IconUser,
  users: IconUsers,
  "sign-out": IconLogout,
  plus: IconPlus,
  "arrow-right": IconArrowRight,
  "chart-line": IconChartLine,
  connected: IconPlugConnected,
  "list-check": IconListCheck,
  inbox: IconInbox,
  mail: IconMail,
  "mail-failed": IconMailX,
  "mail-queued": IconMailFast,
  clock: IconClockHour4,
  external: IconExternalLink,
  import: IconFileImport,
  run: IconPlayerPlay,
  model: IconCpu,
  dashboard: IconLayoutDashboard,
};

/** True if there is an icon of this name (so a caller can choose another). */
export const hasIcon = (name: string) => Object.hasOwn(ICONS, name.toLowerCase());

/**
 * An icon by name; unknown names get a plain file. Decorative (hidden from assistive technology)
 * unless `label` is given, for an icon that is the only label of what it stands for.
 */
export function Icon({
  name,
  size = 20,
  label,
  className,
}: {
  name: string;
  size?: number;
  label?: string;
  className?: string;
}) {
  const Svg = ICONS[name.toLowerCase()] ?? IconFile;
  const a11y = label
    ? { role: "img", "aria-label": label }
    : { "aria-hidden": true as const, focusable: "false" as const };
  return <Svg size={size} stroke={1.75} className={`icon${className ? ` ${className}` : ""}`} {...a11y} />;
}

/** The icon name for a file, from its format and, for documents, its name. */
export function formatIcon(format: string, name?: string): string {
  if (format === "document" || format === "documents" || format === "other") {
    const ext = name?.match(/\.([a-z0-9]+)$/i)?.[1]?.toLowerCase() ?? "";
    if (/^(eml|msg|mbox)$/.test(ext)) return "email";
    if (/^(docx?|odt|rtf)$/.test(ext)) return "word";
    if (/^(pptx?|odp)$/.test(ext)) return "powerpoint";
    if (/^(xlsx?|ods)$/.test(ext)) return "excel";
    if (ext === "pdf") return "pdf";
    if (/^(html?|xhtml)$/.test(ext)) return "html";
    if (/^(md|markdown)$/.test(ext)) return "markdown";
    if (/^(zip|gz|tgz|bz2|7z|tar)$/.test(ext)) return "archive";
    if (ext === "txt") return "text";
    return "documents";
  }
  return hasIcon(format) ? format : "file";
}

/** The icon for a connector family, falling back to its category. */
export const familyIcon = (family: string, category: string) =>
  hasIcon(family) ? family.toLowerCase() : hasIcon(category) ? category : "other";
