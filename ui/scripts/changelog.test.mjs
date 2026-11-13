// The changelog's fragments (scripts/changelog.mjs): how they merge into CHANGELOG.md's Unreleased
// part, and every fragment the merge refuses.
import { mkdtempSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import {
  assembleChangelog,
  checkFragments,
  collectChangelog,
  mergeChangelog,
  parseFragment,
  readFragments,
} from "./changelog.mjs";

const CHANGELOG = [
  "# Changelog",
  "",
  "Intro.",
  "",
  "## Unreleased",
  "",
  "### Fixed",
  "",
  "- **Own fix one.** Wraps",
  "  onto a second line.",
  "- **Own fix two.**",
  "",
  "### Details",
  "",
  "- Own detail.",
  "",
  "## 0.1.2 (11 November 2026)",
  "",
  "### What's new",
  "",
  "- Released.",
  "",
].join("\n");

const frag = (name, text) => ({ name, text });

describe("mergeChangelog", () => {
  it("returns the changelog byte for byte with no fragments", () => {
    expect(mergeChangelog(CHANGELOG, [])).toBe(CHANGELOG);
    const crlf = CHANGELOG.replace(/\n/g, "\r\n");
    expect(mergeChangelog(crlf, [])).toBe(crlf);
  });

  it("puts the changelog's own entries first, then fragments in the order given, in house section order", () => {
    const out = mergeChangelog(CHANGELOG, [
      frag("a-first.md", "### Details\n\n- A detail.\n\n### Fixed\n\n- A fix.\n"),
      frag("b-second.md", "### Fixed\n\n- B fix,\n  two lines.\n"),
    ]);
    expect(out).toBe([
      "# Changelog",
      "",
      "Intro.",
      "",
      "## Unreleased",
      "",
      "### Fixed",
      "",
      "- **Own fix one.** Wraps",
      "  onto a second line.",
      "- **Own fix two.**",
      "- A fix.",
      "- B fix,",
      "  two lines.",
      "",
      "### Details",
      "",
      "- Own detail.",
      "- A detail.",
      "",
      "## 0.1.2 (11 November 2026)",
      "",
      "### What's new",
      "",
      "- Released.",
      "",
    ].join("\n"));
  });

  it("makes the sections Unreleased does not have yet, in their place", () => {
    const out = mergeChangelog(CHANGELOG, [
      frag("x.md", "### Details\n\n- D.\n\n### Security\n\n- S.\n\n### What's new\n\n- W.\n\n### Changed\n\n- C.\n"),
    ]);
    const unreleased = out.slice(out.indexOf("## Unreleased"), out.indexOf("## 0.1.2"));
    expect(unreleased.match(/^### .*$/gm)).toEqual(["### What's new", "### Security", "### Fixed", "### Changed", "### Details"]);
    expect(unreleased).toContain("### What's new\n\n- W.\n\n### Security\n\n- S.\n\n### Fixed\n");
    expect(unreleased).toContain("### Changed\n\n- C.\n\n### Details\n\n- Own detail.\n- D.\n\n");
  });

  it("makes ## Unreleased above the latest release when there is none", () => {
    const released = CHANGELOG.replace(/## Unreleased[\s\S]*?(?=## 0\.1\.2)/, "");
    const out = mergeChangelog(released, [frag("x.md", "### Fixed\n\n- A fix.\n")]);
    expect(out).toBe("# Changelog\n\nIntro.\n\n## Unreleased\n\n### Fixed\n\n- A fix.\n\n## 0.1.2 (11 November 2026)\n\n### What's new\n\n- Released.\n");
  });

  it("keeps CRLF line ends when the changelog has them, whatever the fragment has", () => {
    const crlf = CHANGELOG.replace(/\n/g, "\r\n");
    const out = mergeChangelog(crlf, [frag("x.md", "### Fixed\n\n- LF fix.\n"), frag("y.md", "### Fixed\r\n\r\n- CRLF fix.\r\n")]);
    expect(out).not.toMatch(/[^\r]\n/);
    expect(out.replace(/\r\n/g, "\n")).toBe(mergeChangelog(CHANGELOG, [frag("x.md", "### Fixed\n\n- LF fix.\n"), frag("y.md", "### Fixed\n\n- CRLF fix.\n")]));
  });

  it("keeps LF line ends for an LF changelog given a CRLF fragment", () => {
    const out = mergeChangelog(CHANGELOG, [frag("y.md", "### Fixed\r\n\r\n- CRLF fix.\r\n")]);
    expect(out).not.toContain("\r");
    expect(out).toContain("- **Own fix two.**\n- CRLF fix.\n\n### Details");
  });
});

describe("parseFragment refuses", () => {
  const cases = [
    ["an unknown section", "### Bug fixes\n\n- A fix.\n", /unknown section "### Bug fixes"/],
    ["a section in the wrong case", "### fixed\n\n- A fix.\n", /unknown section/],
    ["text before the first heading", "A fix.\n\n### Fixed\n\n- A fix.\n", /text before its first heading/],
    ["an empty fragment", "", /is empty/],
    ["a fragment of blank lines", "\n  \r\n\n", /is empty/],
    ["a # heading", "# Kilnmark 0.1.3\n\n### Fixed\n\n- A fix.\n", /has a # heading/],
    ["a ## heading", "### Fixed\n\n- A fix.\n\n## 0.1.3\n", /has a ## heading/],
    ["a #### heading", "### Fixed\n\n#### More\n\n- A fix.\n", /has a #### heading/],
    ["a section with no entries", "### Fixed\n\n### Details\n\n- D.\n", /"### Fixed" has no entries/],
    ["a section given twice", "### Fixed\n\n- A.\n\n### Fixed\n\n- B.\n", /"### Fixed" twice/],
  ];
  for (const [what, text, message] of cases) {
    it(what, () => {
      expect(() => parseFragment("bad.md", text)).toThrow(message);
      expect(() => parseFragment("bad.md", text)).toThrow(/^changelog\.d\/bad\.md: /);
      expect(() => mergeChangelog(CHANGELOG, [frag("bad.md", text)])).toThrow(message);
    });
  }

  it("nothing that is not a heading: # inside a line, or indented", () => {
    const sections = parseFragment("ok.md", "### Fixed\n\n- Issue #12 is fixed.\n  # not a heading\n");
    expect(sections.get("Fixed")).toBe("- Issue #12 is fixed.\n  # not a heading");
  });
});

describe("the fragments folder", () => {
  let root;
  const make = (files) => {
    root = mkdtempSync(join(tmpdir(), "kilnmark-changelog-"));
    writeFileSync(join(root, "CHANGELOG.md"), CHANGELOG);
    mkdirSync(join(root, "changelog.d"));
    for (const [name, text] of Object.entries(files)) writeFileSync(join(root, "changelog.d", name), text);
  };
  afterEach(() => rmSync(root, { recursive: true, force: true }));

  it("ignores the README and reads fragments in file-name order", () => {
    make({ "README.md": "# Changelog fragments\n\nNot a fragment.\n", "b.md": "### Fixed\n\n- B.\n", "a.md": "### Fixed\n\n- A.\n" });
    expect(readFragments(root).map((f) => f.name)).toEqual(["a.md", "b.md"]);
    expect(assembleChangelog(root)).toContain("- **Own fix two.**\n- A.\n- B.\n");
    expect(checkFragments(root)).toEqual([]);
  });

  it("is the changelog unchanged with only the README", () => {
    make({ "README.md": "# Changelog fragments\n" });
    expect(assembleChangelog(root)).toBe(CHANGELOG);
  });

  it("refuses a file that is not a fragment", () => {
    make({ "fix.txt": "### Fixed\n\n- A.\n" });
    expect(() => readFragments(root)).toThrow(/changelog\.d\/fix\.txt: only fragments/);
    expect(checkFragments(root)).toHaveLength(1);
  });

  it("reports every bad fragment at once", () => {
    make({ "a.md": "", "b.md": "### Fixes\n\n- B.\n", "c.md": "### Fixed\n\n- C.\n" });
    const problems = checkFragments(root);
    expect(problems).toHaveLength(2);
    expect(problems[0]).toMatch(/^changelog\.d\/a\.md: is empty/);
    expect(problems[1]).toMatch(/^changelog\.d\/b\.md: has an unknown section/);
  });

  it("collects the fragments into CHANGELOG.md and deletes them, keeping the README", () => {
    make({ "README.md": "# Changelog fragments\n", "a.md": "### Security\n\n- **High.** A.\n" });
    const expected = assembleChangelog(root);
    expect(collectChangelog(root)).toEqual(["a.md"]);
    expect(readFileSync(join(root, "CHANGELOG.md"), "utf8")).toBe(expected);
    expect(readdirSync(join(root, "changelog.d"))).toEqual(["README.md"]);
    expect(assembleChangelog(root)).toBe(expected);
  });
});

describe("this repository's fragments", () => {
  it("are well formed", () => {
    expect(checkFragments(fileURLToPath(new URL("../../", import.meta.url)))).toEqual([]);
  });
});
