/**
 * How the documentation is arranged for its readers (docs/README.md): the groups the docs page shows,
 * in order, each with its guides in reading order, and where pages and sections that moved went, so
 * an old link still arrives. Page ids are the guides' file names (docs/guide/<id>.md); the install
 * guides in docs/guide/install/ keep their own names and fill the Install and deploy group by
 * themselves. groups.test.ts checks that every guide is in exactly one group and that every move
 * ends on a page and section that exist.
 */

export type DocGroup = {
  id: string;
  title: string;
  /** One line under the heading: who the group is for. */
  about: string;
  /** Page ids in reading order. The install group's pages come from docs/guide/install/ instead. */
  pages: string[];
};

/** The group whose pages are the guides in docs/guide/install/, in title order. */
export const INSTALL_GROUP = "install";

export const DOC_GROUPS: DocGroup[] = [
  {
    id: "get-started",
    title: "Get started",
    about: "Signing in, the samples, a first pipeline, and asking Kilnmark for help.",
    pages: ["signing-in", "library", "first-pipeline", "assistant"],
  },
  {
    id: "build",
    title: "Build",
    about: "Pipelines and flows: the canvas, expressions, writing data, schedules and notices.",
    pages: ["pipelines", "flows", "expressions", "writing-data", "database-tools", "schedules", "notifications"],
  },
  {
    id: "data-quality",
    title: "Data quality",
    about: "Rules on what you read, gates on what a pipeline moves, and rows a node cannot work out.",
    pages: ["quality-rules", "quality-gates", "rows-that-fail"],
  },
  {
    id: INSTALL_GROUP,
    title: "Install and deploy",
    about: "Choosing a deployment, installing, upgrading and removing Kilnmark.",
    pages: [],
  },
  {
    id: "administer",
    title: "Administer",
    about: "For admins and operators: people and access, drivers, the audit log, backups and branding.",
    pages: ["people-and-access", "drivers", "audit-log", "backup-and-restore", "branding", "external-schedulers"],
  },
  {
    id: "reference",
    title: "Reference",
    about: "Every node, connector and quality rule, with every setting, its type and default.",
    pages: [
      "components-reference",
      "reference-pipeline-nodes",
      "reference-flow-nodes",
      "reference-connectors",
      "reference-quality-rules",
    ],
  },
];

/**
 * Where moved pages and sections went: "page" or "page/section" to "page" or "page/section". A page
 * that moved keeps its section anchors unless one is listed on its own; a section that moved to
 * another page is listed by itself.
 */
export const DOC_MOVES: Record<string, string> = {
  // Pipeline nodes and expressions, merged into the node reference (0.1.2).
  "pipeline-nodes": "reference-pipeline-nodes",
  "pipeline-nodes/rows-that-fail": "rows-that-fail",
  "pipeline-nodes/expressions": "expressions",
  "pipeline-nodes/values": "expressions/values",
  "pipeline-nodes/operators": "expressions/operators",
  "pipeline-nodes/empty-values-null": "expressions/empty-values-null",
  "pipeline-nodes/types": "expressions/types",
  "pipeline-nodes/functions": "expressions/functions",
  "pipeline-nodes/regular-expressions-are-kept-safe": "expressions/regular-expressions-are-kept-safe",
  "pipeline-nodes/limits": "expressions/limits",
  // Quality gates and what they measure, out of the pipelines guide.
  "pipelines/writing-rules-on-a-gate": "quality-gates/writing-rules-on-a-gate",
  "pipelines/what-happens-when-rows-fail": "quality-gates/what-happens-when-rows-fail",
  "pipelines/quality-of-what-a-run-writes": "quality-gates/quality-of-what-a-run-writes",
  "pipelines/predicted-quality": "quality-gates/predicted-quality",
  "pipelines/suggestions": "quality-gates/suggestions",
  // Setting up access, out of the sign-in guide.
  "signing-in/api-tokens": "people-and-access/api-tokens",
  "signing-in/keycloak-if-you-have-no-identity-provider": "people-and-access/keycloak-if-you-have-no-identity-provider",
  "signing-in/token-lifetime-and-the-access-review": "people-and-access/token-lifetime-and-the-access-review",
  "signing-in/two-people-for-sensitive-values": "people-and-access/two-people-for-sensitive-values",
  "signing-in/what-keeps-it-honest": "people-and-access/what-keeps-it-honest",
  "signing-in/still-open": "people-and-access/still-open",
};

/** Where a link to a page, or a section of it, now arrives: itself unless it moved. */
export function movedDoc(id: string, section: string | null): { id: string; section: string | null } {
  const to = (section ? DOC_MOVES[`${id}/${section}`] : undefined) ?? DOC_MOVES[id];
  if (!to) return { id, section };
  const [page, part] = to.split("/", 2);
  // A page-wide move keeps the section; a section moved on its own names its new one.
  if (section && DOC_MOVES[`${id}/${section}`]) return { id: page, section: part ?? null };
  return { id: page, section: part ?? section };
}
