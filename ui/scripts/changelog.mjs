// The changelog's fragments: each pull request writes its entries to changelog.d/<branch>.md
// instead of editing CHANGELOG.md's Unreleased part, so open pull requests never conflict there.
// Everything that reads the changelog (the in-app What's new through vite.config.ts, the anchor
// check, the server's bundled docs through server/build.gradle.kts) reads CHANGELOG.md with the
// fragments merged in by this one module; a release merges them into the file for good (--collect).
//
//   node scripts/changelog.mjs --check          checks every fragment; exits 1 saying what is wrong
//   node scripts/changelog.mjs --write <file>   writes CHANGELOG.md with the fragments merged in
//   node scripts/changelog.mjs --collect        merges the fragments into CHANGELOG.md and deletes them
//
// Plain JavaScript on any Node that Vite supports, with no packages: Gradle runs it before npm ci.
import { readdirSync, readFileSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** The sections a release's notes have, in their order (CLAUDE.md, CHANGELOG.md's introduction). */
export const SECTIONS = ["What's new", "Security", "Fixed", "Changed", "Details"];

/** The folder of fragments, beside CHANGELOG.md. */
export const FRAGMENT_DIR = "changelog.d";

const README = "readme.md";
const UNRELEASED = /^## Unreleased[ \t]*$/;

/** A changelog or fragment that cannot be merged; the message names the file and what to fix. */
export class ChangelogError extends Error {}

const lf = (text) => text.replace(/\r\n/g, "\n");
/** Lines with blank lines at both ends taken off, joined again. */
const trimBlank = (lines) => {
  let a = 0;
  let b = lines.length;
  while (a < b && lines[a].trim() === "") a++;
  while (b > a && lines[b - 1].trim() === "") b--;
  return lines.slice(a, b).map((l) => l.replace(/[ \t]+$/, "")).join("\n");
};

/**
 * Reads one fragment's text: one or more "### <Section>" headings from SECTIONS, each followed by
 * its entries. Returns a Map of section to its entries' text (LF line ends, no blank lines around).
 */
export function parseFragment(name, text) {
  const fail = (why) => {
    throw new ChangelogError(`${FRAGMENT_DIR}/${name}: ${why}`);
  };
  const lines = lf(text).split("\n");
  if (lines.every((l) => l.trim() === "")) fail("is empty; write its entries under a ### heading, or delete it");
  const sections = new Map();
  let current = null;
  let body = [];
  const close = () => {
    if (current === null) return;
    const entries = trimBlank(body);
    if (!entries) fail(`"### ${current}" has no entries under it`);
    sections.set(current, entries);
  };
  for (const line of lines) {
    // A Markdown heading: one to six # at the start of the line, then a space or nothing.
    const hashes = /^(#{1,6})(?:[ \t]|$)/.exec(line)?.[1];
    if (hashes) {
      if (hashes.length !== 3) {
        fail(`has a ${hashes} heading ("${line.trim()}"); a fragment has only ### section headings, ` +
          "and the release step writes the version heading");
      }
      const section = line.slice(3).trim().replace(/[ \t]+#+$/, "").trim();
      if (!SECTIONS.includes(section)) {
        fail(`has an unknown section "### ${section}"; use one of: ${SECTIONS.join(", ")}`);
      }
      if (sections.has(section) || section === current) fail(`has "### ${section}" twice; put its entries under one heading`);
      close();
      current = section;
      body = [];
    } else if (current === null) {
      if (line.trim() !== "") fail(`has text before its first heading; start it with a ### heading (${SECTIONS.join(", ")})`);
    } else {
      body.push(line);
    }
  }
  close();
  return sections;
}

/**
 * Returns the changelog's text with the fragments ([{ name, text }], in the order given) merged
 * into its Unreleased part: section by section in SECTIONS order, the changelog's own entries
 * first, then each fragment's. A missing "## Unreleased" or section is made. With no fragments
 * the text is returned exactly as it was. The changelog's own line ends (LF or CRLF) are kept.
 */
export function mergeChangelog(changelog, fragments) {
  const parsed = fragments.map((f) => parseFragment(f.name, f.text));
  if (parsed.length === 0) return changelog;
  const crlf = changelog.includes("\r\n");
  const lines = lf(changelog).split("\n");

  let start = lines.findIndex((l) => UNRELEASED.test(l));
  let end;
  if (start < 0) {
    // No Unreleased part (just after a release): one goes above the latest release.
    start = lines.findIndex((l) => /^## /.test(l));
    if (start < 0) start = lines.length;
    end = start;
  } else {
    end = lines.findIndex((l, i) => i > start && /^## /.test(l));
    if (end < 0) end = lines.length;
  }
  const after = lines.slice(end);
  const atEnd = after.length === 0 || (after.length === 1 && after[0] === "");

  // The Unreleased part as it is: anything before its first ### heading, then its sections in order.
  const own = start < end ? lines.slice(start + 1, end) : [];
  const preamble = [];
  const existing = [];   // [section, entries]
  for (const line of own) {
    const heading = /^### (.*?)[ \t]*$/.exec(line);
    if (heading) existing.push([heading[1], []]);
    else if (existing.length) existing[existing.length - 1][1].push(line);
    else preamble.push(line);
  }

  const order = [...SECTIONS, ...existing.map(([s]) => s).filter((s) => !SECTIONS.includes(s))];
  const blocks = [];
  const intro = trimBlank(preamble);
  if (intro) blocks.push(intro);
  for (const section of order) {
    const entries = [
      ...existing.filter(([s]) => s === section).map(([, body]) => trimBlank(body)),
      ...parsed.map((p) => p.get(section) ?? ""),
    ].filter((e) => e !== "");
    const had = existing.some(([s]) => s === section);
    if (entries.length || had) blocks.push(`### ${section}` + (entries.length ? "\n\n" + entries.join("\n") : ""));
  }

  const before = lines.slice(0, start);
  while (before.length && before[before.length - 1].trim() === "") before.pop();
  const part = ["## Unreleased", "", blocks.join("\n\n")].join("\n");
  const out = [...before, ...(before.length ? [""] : []), part, ...(atEnd ? [""] : ["", ...after])].join("\n");
  return crlf ? out.replace(/\n/g, "\r\n") : out;
}

/** The fragments in <root>/changelog.d, in file-name order; README.md is not one. */
export function readFragments(root) {
  const dir = join(root, FRAGMENT_DIR);
  let names;
  try {
    names = readdirSync(dir);
  } catch {
    return [];
  }
  const fragments = [];
  for (const name of names.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))) {
    if (name.toLowerCase() === README) continue;
    if (!name.endsWith(".md") || !statSync(join(dir, name)).isFile()) {
      throw new ChangelogError(`${FRAGMENT_DIR}/${name}: only fragments (<branch>.md) and README.md belong in ${FRAGMENT_DIR}`);
    }
    fragments.push({ name, text: readFileSync(join(dir, name), "utf8") });
  }
  return fragments;
}

/** CHANGELOG.md under root, with every fragment in changelog.d merged into its Unreleased part. */
export function assembleChangelog(root) {
  return mergeChangelog(readFileSync(join(root, "CHANGELOG.md"), "utf8"), readFragments(root));
}

/** Checks every fragment, reporting all that are wrong. Returns the problems (none when all is well). */
export function checkFragments(root) {
  let fragments;
  try {
    fragments = readFragments(root);
  } catch (e) {
    if (e instanceof ChangelogError) return [e.message];
    throw e;
  }
  const problems = [];
  for (const f of fragments) {
    try {
      parseFragment(f.name, f.text);
    } catch (e) {
      if (!(e instanceof ChangelogError)) throw e;
      problems.push(e.message);
    }
  }
  if (problems.length === 0) {
    try {
      assembleChangelog(root);
    } catch (e) {
      if (!(e instanceof ChangelogError)) throw e;
      problems.push(e.message);
    }
  }
  return problems;
}

/** The release step: merges the fragments into CHANGELOG.md and deletes them. Returns their names. */
export function collectChangelog(root) {
  const fragments = readFragments(root);
  writeFileSync(join(root, "CHANGELOG.md"), assembleChangelog(root));
  for (const f of fragments) unlinkSync(join(root, FRAGMENT_DIR, f.name));
  return fragments.map((f) => f.name);
}

function main(args) {
  const root = fileURLToPath(new URL("../../", import.meta.url));
  const usage = "Use: node scripts/changelog.mjs --check | --write <file> | --collect";
  try {
    if (args[0] === "--check" && args.length === 1) {
      const problems = checkFragments(root);
      if (problems.length) {
        console.error(`The changelog's fragments in ${FRAGMENT_DIR} cannot be merged (${FRAGMENT_DIR}/README.md says how to write one):\n- ` +
          problems.join("\n- "));
        return 1;
      }
      console.log(`Changelog fragments: ${readFragments(root).length} in ${FRAGMENT_DIR}, all well formed.`);
    } else if (args[0] === "--write" && args.length === 2) {
      writeFileSync(args[1], assembleChangelog(root));
    } else if (args[0] === "--collect" && args.length === 1) {
      const names = collectChangelog(root);
      console.log(names.length
        ? `Merged ${names.length} fragment(s) into CHANGELOG.md's Unreleased part and deleted them: ${names.join(", ")}.`
        : `No fragments in ${FRAGMENT_DIR}; CHANGELOG.md is unchanged.`);
    } else {
      console.error(usage);
      return 2;
    }
  } catch (e) {
    if (!(e instanceof ChangelogError)) throw e;
    console.error(`The changelog could not be assembled: ${e.message}`);
    return 1;
  }
  return 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = main(process.argv.slice(2));
