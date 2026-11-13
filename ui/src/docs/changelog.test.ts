import { describe, expect, test } from "vitest";
import { DOC_PAGES, WHATS_NEW } from "./index";

// What's new is CHANGELOG.md with the fragments in changelog.d merged into its Unreleased part
// (vite.config.ts, scripts/changelog.mjs), so a pull request's entry shows before its release.
const fragments = import.meta.glob("../../../changelog.d/*.md", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;

describe("What's new", () => {
  test("holds every changelog fragment's entries, in the Unreleased part", () => {
    const markdown = DOC_PAGES.find((p) => p.id === WHATS_NEW)!.markdown.replace(/\r\n/g, "\n");
    const unreleased = markdown.slice(markdown.indexOf("## Unreleased"), markdown.search(/^## (?!Unreleased)/m));
    const entries = Object.entries(fragments).filter(([path]) => !/\/README\.md$/i.test(path));
    for (const [path, text] of entries) {
      for (const line of text.replace(/\r\n/g, "\n").split("\n")) {
        if (line.trim() === "" || line.startsWith("### ")) continue;
        expect(unreleased, `${path}: "${line}" is not in What's new`).toContain(line.trimEnd());
      }
    }
  });
});
