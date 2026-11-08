import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import DocsPage from "./DocsPage";
import { DOC_PAGES } from "../docs";
import { DOC_GROUPS } from "../docs/groups";

// Rendered as markup, as a browser-like environment may not read the bundled changelog. Where a
// moved link goes is tested in docs/groups.test.ts; here, that the page shows the groups.
const page = (doc: string | null, section: string | null = null) =>
  renderToStaticMarkup(<DocsPage doc={doc} section={section} />);

describe("the documentation page", () => {
  test("lists What's new, then each group's heading and guides, in the group's order", () => {
    const html = page(null);
    const headings = [...html.matchAll(/<h2 id="docs-group-[\w-]+">([^<]+)<\/h2>/g)].map((m) => m[1]);
    expect(headings).toEqual(DOC_GROUPS.map((g) => g.title));
    const build = /<ul class="km-gallery" aria-label="Build">(.*?)<\/ul>/.exec(html)![1];
    const links = [...build.matchAll(/href="([^"]+)"/g)].map((m) => m[1]);
    expect(links.slice(0, 3)).toEqual(["#/docs/pipelines", "#/docs/flows", "#/docs/expressions"]);
    expect(html.indexOf('href="#/docs/whats-new"')).toBeLessThan(html.indexOf('id="docs-group-get-started"'));
  });

  test("the install group lists the guides in docs/guide/install, or points to INSTALL.md until there are any", () => {
    const html = page(null);
    const install = html.slice(html.indexOf('id="docs-group-install"'), html.indexOf('id="docs-group-administer"'));
    const guides = DOC_PAGES.filter((p) => p.folder === "install");
    if (guides.length === 0) expect(install).toContain("INSTALL.md");
    for (const g of guides) expect(install).toContain(`href="#/docs/${g.id}"`);
  });

  test("a page that moved is not called missing while the reader is sent on", () => {
    expect(page("pipeline-nodes", "join")).not.toContain('role="alert"');
    expect(page("pipelines", "predicted-quality")).not.toContain('role="alert"');
  });

  test("a guide that never existed still says so", () => {
    expect(page("no-such-guide")).toContain("There is no guide called “no-such-guide”");
  });
});
