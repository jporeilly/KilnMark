import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { SampleAbout as About } from "../api";
import { docHeadings, docPage } from "../docs";
import { SampleAbout, sampleGuideHref } from "./Explanation";

const ABOUT: About = {
  file: "07-parameters/orders-by-status.yaml",
  explanation: {
    title: "Parameters",
    shows: "Reads the shop orders in the statuses asked for.",
    why: "One pipeline replaces many near-copies.",
    lookFor: "the note under the read node.",
    needs: "shop-db (the shop schema) and files.",
  },
  changed: false,
};

/** The guide's page and section a docs link names exist, so the link lands where it says. */
function lands(href: string) {
  const [, id, section] = /^#\/docs\/([^/]+)(?:\/(.+))?$/.exec(href) ?? [];
  const page = docPage(id);
  expect(page, href).toBeTruthy();
  if (section) expect(docHeadings(page!).map((h) => h.slug), href).toContain(section);
}

describe("About this sample", () => {
  test("the four parts, as the server sends them, with a link to the samples in the guide", () => {
    const out = renderToStaticMarkup(<SampleAbout about={ABOUT} kind="pipeline" id="a" />);
    expect(out).toMatch(/<h2 id="a" class="lib-sample-about-h"><button type="button" class="lib-sample-about-toggle" aria-expanded="true" aria-controls="a-body">.*About this sample<\/button><\/h2>/);
    for (const part of ["What it shows", "Why it matters", "Look for when it runs", "Needs"]) expect(out).toContain(`<dt>${part}</dt>`);
    expect(out).toContain("<dd>The note under the read node.</dd>");
    expect(out).toContain('href="#/docs/library/samples"');
    expect(out).not.toContain("changed since");
  });

  test("changed since it was added: still shown, and labelled so", () => {
    const out = renderToStaticMarkup(<SampleAbout about={{ ...ABOUT, changed: true }} kind="flow" id="a" level={3} />);
    expect(out).toContain("<h3");
    expect(out).toContain("Based on a sample; changed since");
    expect(out).toContain("This flow started as the sample below");
  });

  test("uses the width of what holds it: no rule holds the panel or its text to a column", async () => {
    const fs = await import(/* @vite-ignore */ ["node", "fs"].join(":"));
    // Relative to ui/, where the tests run.
    const css: string = fs.readFileSync("src/library/explanation.css", "utf8");
    expect(css).toContain(".lib-sample-about {");
    expect(css).not.toMatch(/max-width|max-inline-size/);
  });

  test("nothing for a pipeline not added from the samples", () => {
    expect(renderToStaticMarkup(<SampleAbout about={null} kind="pipeline" id="a" />)).toBe("");
  });

  test("the business scenarios link to their own guide; the links land on real sections", () => {
    expect(sampleGuideHref("10-scenarios/1-onboarding/flow.yaml")).toBe("#/docs/scenarios");
    expect(sampleGuideHref(ABOUT.file)).toBe("#/docs/library/samples");
    lands(sampleGuideHref("10-scenarios/1-onboarding/flow.yaml"));
    lands(sampleGuideHref(ABOUT.file));
    lands("#/docs/pipelines/parameters-and-server-variables");
  });
});
