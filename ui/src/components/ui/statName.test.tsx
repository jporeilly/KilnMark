import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { IconHourglass } from "@tabler/icons-react";
import { StatName, StatTile, STAT_NAME_LONG, STAT_NAME_LONGER } from "./StatTile";

// A node's id as a tile's value ("What limited this run", "Longest node"): an id can be 64
// characters, and a tile's figures are large. It must keep inside the tile.

/** The longest id a node may have: 64 characters, in words joined by _. */
const LONGEST = "accounts_per_customer_joined_to_orders_then_grouped_by_region_qa";

/** A stylesheet's text, read from disk: in tests a CSS import is empty. Relative to ui/, where the tests run. */
async function stylesheet(path: string): Promise<string> {
  const fs = await import(/* @vite-ignore */ ["node", "fs"].join(":"));
  return fs.readFileSync(path, "utf8");
}

/** The declarations of the rule whose selector is exactly `selector`. */
function rule(css: string, selector: string): string {
  for (const m of css.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
    const selectors = m[1].replace(/\/\*[\s\S]*?\*\//g, "").split(",").map((x) => x.trim());
    if (selectors.includes(selector)) return m[2];
  }
  return "";
}

const tile = (name: string) =>
  renderToStaticMarkup(
    <StatTile label="What limited this run" value={<StatName name={name} />} icon={<IconHourglass size={18} />} tint="teal" />,
  );

describe("a name as a tile's value", () => {
  test("the longest id is 64 characters", () => {
    expect(LONGEST).toHaveLength(64);
  });

  test("a 64-character id is set at the smallest size, may wrap after each _, and has its full name on hover", () => {
    const out = tile(LONGEST);
    expect(out).toContain(`class="km-stat-name mono km-stat-name--longer"`);
    expect(out).toContain(`title="${LONGEST}"`);
    // A place to wrap after every _ but none inside a word: the pieces, joined, are the id.
    const inner = /<span class="km-stat-name[^>]*>(.*?)<\/span>/.exec(out)![1];
    const pieces = inner.split("<wbr/>");
    expect(pieces).toHaveLength(LONGEST.split("_").length);
    expect(pieces.join("")).toBe(LONGEST);
    for (const p of pieces.slice(0, -1)) expect(p.endsWith("_")).toBe(true);
  });

  test("a short id keeps the tile's large figure; a longer one is set smaller", () => {
    expect(tile("check")).toContain(`class="km-stat-name mono"`);
    // The id from the owner's run: 21 characters, which ran past the tile at the large size.
    expect("accounts_per_customer".length).toBeGreaterThan(STAT_NAME_LONG);
    expect(tile("accounts_per_customer")).toContain(`class="km-stat-name mono km-stat-name--long"`);
    expect(tile("x".repeat(STAT_NAME_LONGER))).toContain("km-stat-name--long\"");
    expect(tile("x".repeat(STAT_NAME_LONGER + 1))).toContain("km-stat-name--longer");
  });

  test("the styles keep it inside the tile: it may shrink to the tile's width and break anywhere at need", async () => {
    const css = await stylesheet("src/app.css");
    const name = rule(css, ".km-stat-name");
    expect(name).toMatch(/overflow-wrap:\s*anywhere/);
    expect(name).toMatch(/min-width:\s*0/);
    expect(name).toMatch(/max-width:\s*100%/);
    expect(name).not.toMatch(/nowrap/);
    const px = (decls: string) => Number(/font-size:\s*(\d+)px/.exec(decls)![1]);
    // Smaller the longer the name: both steps under the tile's own figure (--km-stat, 30px).
    expect(px(rule(css, ".km-stat-name--long"))).toBeLessThan(30);
    expect(px(rule(css, ".km-stat-name--longer"))).toBeLessThan(px(rule(css, ".km-stat-name--long")));
    // The tiles' grid tracks may be narrower than their content at every width (desktop: 4, phone: 2).
    expect(css).toMatch(/\.km-grid-4\s*\{[^}]*repeat\(4, minmax\(0, 1fr\)\)/);
    expect(css).toMatch(/\.km-grid-4\s*\{[^}]*repeat\(2, minmax\(0, 1fr\)\)/);
  });
});
