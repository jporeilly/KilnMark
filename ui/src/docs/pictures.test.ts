import { describe, expect, test } from "vitest";

const svgs = import.meta.glob("../../../docs/guide/images/*.svg", { query: "?raw", import: "default", eager: true }) as Record<string, string>;
const files = new Set(Object.keys(import.meta.glob("../../../docs/guide/images/*")).map((p) => p.replace(/^.*\//, "")));
const guides = import.meta.glob("../../../docs/guide/*.md", { query: "?raw", import: "default", eager: true }) as Record<string, string>;

/**
 * A browser shows an SVG picture only when it is well-formed XML and, under the app's security
 * policy, carries no styles of its own; a page around it forgives what a picture does not.
 */
describe("the guides' pictures", () => {
  test("there are some", () => {
    expect(Object.keys(svgs).length).toBeGreaterThan(0);
  });

  test.each(Object.entries(svgs))("%s has no repeated attribute and no styles", (name, text) => {
    for (const tag of text.match(/<[a-zA-Z][^>]*>/g) ?? []) {
      const names = [...tag.matchAll(/\s([a-zA-Z:-]+)=/g)].map((m) => m[1]);
      expect(new Set(names).size, `${name}: ${tag.slice(0, 80)}`).toBe(names.length);
    }
    expect(text).not.toMatch(/<style|\sstyle=/);
  });

  test("every picture a guide names is there", () => {
    for (const [g, text] of Object.entries(guides)) {
      for (const m of text.matchAll(/!\[[^\]]*\]\(images\/([^)]+)\)/g)) {
        expect(files.has(m[1]), `${g} names images/${m[1]}`).toBe(true);
      }
    }
  });
});
