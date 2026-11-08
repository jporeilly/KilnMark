// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { describe, expect, test } from "vitest";
import { href, parse, routeTitle } from "../router";
import { useRouteTitle, useTitle } from "./useTitle";

describe("every route sets the tab's title", () => {
  test("each page has a title of its own", () => {
    const hashes = [
      href.search("orders"), href.ask("orders"), href.asset("urn:x"), href.sources(), href.source("crm-db"), href.tokens(),
      href.settings(), href.notifications(), href.email(), href.emailLog(), href.audit(), href.accessReview(),
      href.starterPack(), href.drivers(), href.jdbcDrivers(), href.rules(), href.issues(), href.signOut(), href.ai(),
      href.aiUsage(), "#/docs", href.browse(), href.relationships(), href.pipelines(), href.newPipeline(),
      href.pipeline("sample-join"), href.pipeline("sample-join", 2), href.pipelineRun(4), href.flows(), href.newFlow(),
      href.flow("sample-retries"), href.flowRun(7), href.schedules(), href.library(), href.library("templates"),
    ];
    const titles = hashes.map((h) => routeTitle(parse(h)));
    expect(titles.filter((t) => !t)).toEqual([]);
    expect(routeTitle(parse(href.flow("sample-retries")))).toBe("sample-retries");
    expect(routeTitle(parse(href.pipeline("sample-join", 2)))).toBe("sample-join, version 2");
    expect(routeTitle(parse(href.home()))).toBe("");
  });

  test("moving on never leaves the page before's title, even to a page that sets none", async () => {
    function Page({ title }: { title: string }) {
      useTitle(title);
      return null;
    }
    function Shell({ hash, own }: { hash: string; own: string | null }) {
      useRouteTitle(routeTitle(parse(hash)));
      return own == null ? null : <Page key={hash} title={own} />;
    }
    const host = document.createElement("div");
    const root = createRoot(host);
    await act(async () => root.render(<Shell hash={href.pipeline("sample-join")} own="sample-join" />));
    expect(document.title).toBe("sample-join · Kilnmark");
    // A flow page that has not loaded yet (or an Admins only notice): the route's title, at once.
    await act(async () => root.render(<Shell hash={href.flow("sample-retries")} own={null} />));
    expect(document.title).toBe("sample-retries · Kilnmark");
    // Once loaded, the page may say more; when it goes, the route's title is back.
    await act(async () => root.render(<Shell hash={href.flow("sample-retries")} own="Sample: retries" />));
    expect(document.title).toBe("Sample: retries · Kilnmark");
    await act(async () => root.render(<Shell hash={href.accessReview()} own={null} />));
    expect(document.title).toBe("Access review · Kilnmark");
    await act(async () => root.render(<Shell hash={href.home()} own="" />));
    expect(document.title).toBe("Kilnmark");
    await act(async () => root.unmount());
  });
});
