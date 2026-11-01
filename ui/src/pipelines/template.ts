import { api, type Source } from "../api";
import { browseApi } from "../browse/browseApi";

/** What a new pipeline reads and writes, from the sources this person has; null where there is none. */
export type StarterPicks = {
  read: { source: string; asset: string | null } | null;
  write: { source: string; files: boolean } | null;
};

/** Placeholders the check flags until they are replaced: never a real source's name. */
export const NO_PICKS: StarterPicks = { read: null, write: null };

/**
 * The YAML a new pipeline starts from: a read from one of the person's sources and a write to one
 * that allows writing, with comments on what to change. Where there is no such source yet, the
 * names are placeholders in angle brackets, which the check reports until they are replaced.
 */
export function starterYaml(name: string, picks: StarterPicks = NO_PICKS): string {
  const read = picks.read;
  const write = picks.write;
  const target = write?.files
    ? `    path: exports/sample.csv
    format: csv`
    : `    table: <schema>.pipeline_sample   # a table in the target source`;
  return `kilnmark: pipeline/v1
name: ${name}
description: A sample of rows from one source, written to another.
nodes:
  # Read a table or file from a saved source. The source holds the credentials.
  - id: rows
    type: read
    source: ${read?.source ?? "<your source>"}
    asset: ${read?.asset ?? "<schema.table, or a file's path>"}
    row_limit: 100            # 0 reads every row

  # Add nodes here, from the types this server lists (GET /api/v1/node-types): each names the
  # node feeding it with input: <id>, or input: <id>.<port> for another of its outputs.

  # Write the rows. The source must allow writing: a designer or an admin turns on Allow writing for it.
  - id: sample
    type: write
    input: rows
    source: ${write?.source ?? "<a source that allows writing>"}
${target}
    mode: replace             # create | append | replace | upsert (with key)
`;
}

/** The YAML with its top-level name changed, if it has one. */
export function renamed(yaml: string, name: string): string {
  return yaml.replace(/^name:.*$/m, `name: ${name}`);
}

/** Formats a pipeline can read rows from. */
const READABLE = new Set(["table", "view", "csv", "tsv", "json", "parquet", "excel"]);

/**
 * The first source with scanned assets to read from (and its first table or file), and the first
 * source that allows writing, from the sources API. Anything that cannot be found stays a
 * placeholder; a failed call leaves the template as it is.
 */
export async function findStarterPicks(): Promise<StarterPicks> {
  const sources: Source[] = await api.sources();
  const byName = (a: Source, b: Source) => a.name.localeCompare(b.name);
  const writable = sources.filter((s) => s.settings.allow_writing === "true").sort(byName)[0];
  const readable = sources.filter((s) => s.assets > 0 && s !== writable).sort(byName)[0]
    ?? sources.filter((s) => s.assets > 0).sort(byName)[0];
  let asset: string | null = null;
  if (readable) {
    try {
      const containers = await browseApi.containers(readable.name, "", 0, 20);
      for (const c of containers.items) {
        const assets = await browseApi.assets(readable.name, c.name, "", 0, 50);
        const first = assets.items.find((a) => READABLE.has(a.format));
        if (first) {
          const prefix = `urn:kilnmark:${readable.name}:`;
          asset = first.urn.startsWith(prefix) ? first.urn.slice(prefix.length) : null;
          break;
        }
      }
    } catch {
      asset = null;
    }
  }
  return {
    read: readable ? { source: readable.name, asset } : null,
    write: writable ? { source: writable.name, files: writable.category === "files" } : null,
  };
}

/** A pipeline with no nodes yet, for starting on an empty canvas. The check reports that it needs a node. */
export function emptyYaml(name: string): string {
  return `kilnmark: pipeline/v1
name: ${name}
nodes: []
`;
}

/**
 * The YAML without its top-level layout block (where the canvas draws nodes) and the comment the
 * canvas writes above it: what decides whether a change is more than moving nodes.
 */
export function withoutLayout(yaml: string): string {
  const lines = yaml.split("\n");
  const at = lines.findIndex((l) => /^layout\s*:/.test(l));
  if (at < 0) return yaml;
  let end = at + 1;
  while (end < lines.length && !/^[^\s#]/.test(lines[end])) end++;
  let start = at;
  while (start > 0 && /^#.*Kilnmark ignores this/.test(lines[start - 1])) start--;
  while (start > 0 && lines[start - 1].trim() === "") start--;
  return [...lines.slice(0, start), ...lines.slice(end)].join("\n").replace(/\n*$/, "\n");
}
