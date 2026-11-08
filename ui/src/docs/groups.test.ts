import { describe, expect, test } from "vitest";
import { DOC_GROUPS, DOC_MOVES, INSTALL_GROUP, movedDoc } from "./groups";
import { DOC_PAGES, docHeadings, docPage, WHATS_NEW } from "./index";

const guides = DOC_PAGES.filter((p) => p.id !== WHATS_NEW);
const slugsOf = (id: string) => new Set(docHeadings(docPage(id)!).map((h) => h.slug));

describe("the documentation's groups", () => {
  test("every guide outside docs/guide/install is in exactly one group, and every group's page exists", () => {
    const listed = DOC_GROUPS.flatMap((g) => g.pages);
    expect(new Set(listed).size, "a page listed twice").toBe(listed.length);
    for (const id of listed) expect(docPage(id), `${id} is in a group but is no guide`).toBeDefined();
    const missing = guides.filter((p) => p.folder !== "install" && !listed.includes(p.id)).map((p) => p.id);
    expect(missing, "guides in no group: add each to a group in groups.ts").toEqual([]);
  });

  test("the install group takes the install guides, and no page is in it by name", () => {
    expect(DOC_GROUPS.find((g) => g.id === INSTALL_GROUP)?.pages).toEqual([]);
    for (const p of guides.filter((g) => g.folder === "install")) {
      expect(DOC_GROUPS.some((g) => g.pages.includes(p.id)), `${p.id} is an install guide`).toBe(false);
    }
  });

  test("page ids are unique across docs/guide and its install folder", () => {
    const ids = DOC_PAGES.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe("moved pages and sections", () => {
  test.each(Object.entries(DOC_MOVES))("%s still arrives, at %s", (from, to) => {
    const [fromPage, fromSection] = from.split("/", 2);
    const [toPage, toSection] = to.split("/", 2);
    if (fromSection) {
      // A section that moved is no longer on its page, if the page is still there.
      if (docPage(fromPage)) expect(slugsOf(fromPage).has(fromSection), `${from} is still there`).toBe(false);
    } else {
      expect(docPage(fromPage), `${fromPage} is a page again: remove its move`).toBeUndefined();
    }
    expect(docPage(toPage), `${toPage} is no page`).toBeDefined();
    if (toSection) expect(slugsOf(toPage).has(toSection), `${to} is no section`).toBe(true);
    expect(DOC_MOVES[to], "a move ends on another move").toBeUndefined();
  });

  test("a page-wide move keeps the section; a section moved on its own goes to its new place", () => {
    expect(movedDoc("pipeline-nodes", "join")).toEqual({ id: "reference-pipeline-nodes", section: "join" });
    expect(movedDoc("pipeline-nodes", "functions")).toEqual({ id: "expressions", section: "functions" });
    expect(movedDoc("pipeline-nodes", "rows-that-fail")).toEqual({ id: "rows-that-fail", section: null });
    expect(movedDoc("pipelines", "predicted-quality")).toEqual({ id: "quality-gates", section: "predicted-quality" });
    expect(movedDoc("pipelines", "reading-a-run")).toEqual({ id: "pipelines", section: "reading-a-run" });
    expect(movedDoc("pipeline-nodes", null)).toEqual({ id: "reference-pipeline-nodes", section: null });
  });

  test("every section of a page moved as a whole is on the page it moved to", () => {
    // pipeline-nodes' sections that did not move on their own (filter, join, which-row-wins, ...).
    for (const s of ["how-nodes-connect", "filter", "compute", "select", "lookup", "join", "aggregate", "union",
      "dedupe", "which-row-wins", "route", "limit", "sort", "window"]) {
      const to = movedDoc("pipeline-nodes", s);
      expect(slugsOf(to.id).has(to.section!), `pipeline-nodes/${s}`).toBe(true);
    }
  });
});

describe("links between guides", () => {
  // Links as the viewer reads them (render.ts): other.md#part, install/other.md, ../other.md, #part.
  const link = /\]\(((?:\.\/)?(?:\.\.\/|install\/)?([\w-]+)\.md(?:#([^)\s]*))?|#([^)\s]+))\)/g;
  test.each(guides.map((p) => [p.id, p]))("%s links only to pages and sections that exist", (_, page) => {
    const broken: string[] = [];
    const fenced = page.markdown.replace(/^```[\s\S]*?^```/gm, "");
    for (const m of fenced.matchAll(link)) {
      const to = m[2] ? movedDoc(m[2], m[3] ? decodeURIComponent(m[3]) : null) : { id: page.id, section: m[4] };
      if (m[2] && to.id !== m[2]) broken.push(`${m[1]} has moved to ${to.id}${to.section ? "#" + to.section : ""}`);
      else if (!docPage(to.id)) broken.push(`${m[1]}: no page ${to.id}`);
      else if (to.section && !slugsOf(to.id).has(to.section)) broken.push(`${m[1]}: no section ${to.section}`);
    }
    expect(broken).toEqual([]);
  });
});
