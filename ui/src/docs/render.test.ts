import { describe, expect, test } from "vitest";
import { docPicture } from "./index";
import { renderDoc } from "./render";

const page = (markdown: string) => ({ id: "t", title: "T", markdown });

describe("pictures in guides", () => {
  test("show a picture bundled with the guides, with its description as its alternative text", () => {
    const html = renderDoc(page("![How a pipeline run goes](images/pipeline-run.png)"));
    expect(html).toMatch(/<img class="doc-picture" src="[^"]+" alt="How a pipeline run goes" loading="lazy">/);
  });

  test("show any other image as its description, loading nothing", () => {
    for (const src of ["https://example.com/x.png", "images/missing.png", "../images/pipeline-run.png", "images/x.svg"]) {
      const html = renderDoc(page(`![A picture](${src})`));
      expect(html).not.toContain("<img");
      expect(html).toContain("A picture");
    }
    expect(docPicture("images/pipeline-run.png")).not.toBeNull();
    expect(docPicture("javascript:alert(1)")).toBeNull();
  });
});
