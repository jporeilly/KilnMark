// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { AccessRole } from "../api";
import { DeleteFlowButton, DeleteFlowConfirm } from "./DeleteFlow";

let root: Root | null = null;
let host: HTMLDivElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.append(host);
  window.location.hash = "#/flows/nightly-load";
});

afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  root = null;
  document.body.innerHTML = "";
  vi.unstubAllGlobals();
});

async function show(node: React.ReactNode) {
  root = createRoot(host);
  await act(async () => root!.render(node));
}

const button = (label: string) => [...host.querySelectorAll("button")].find((b) => b.textContent === label);

/** A server that answers the delete with `status` (and an error message for a refusal). */
function server(status: number) {
  const fetch = vi.fn(async (_url: string, _init?: RequestInit) =>
    status === 204
      ? new Response(null, { status })
      : new Response(JSON.stringify({ error: "A run of nightly-load is queued or running." }), {
          status,
          headers: { "Content-Type": "application/json" },
        }),
  );
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

describe("deleting a flow", () => {
  test.each<[AccessRole, boolean]>([
    ["viewer", false],
    ["steward", false],
    ["designer", true],
    ["admin", true],
  ])("a %s sees Delete: %s", async (role, shown) => {
    const ask = vi.fn();
    await show(<DeleteFlowButton role={role} onAsk={ask} />);
    expect(button("Delete") != null).toBe(shown);
    if (shown) {
      await act(async () => button("Delete")!.click());
      expect(ask).toHaveBeenCalledOnce();
    }
  });

  test("the confirmation names the flow, says what goes and what stays, and starts on Cancel", async () => {
    const fetch = server(204);
    const cancel = vi.fn();
    await show(<DeleteFlowConfirm name="nightly-load" title="Nightly load" onCancel={cancel} onError={vi.fn()} />);
    const text = host.querySelector('[role="alert"]')!.textContent!;
    expect(text).toContain("Delete the flow Nightly load (nightly-load)?");
    expect(text).toContain("Its versions, its runs with what each node did, and its schedules are deleted.");
    expect(text).toContain("The pipeline runs it started stay");
    expect(document.activeElement).toBe(button("Cancel"));
    await act(async () => button("Cancel")!.click());
    expect(cancel).toHaveBeenCalledOnce();
    expect(fetch).not.toHaveBeenCalled();
  });

  test("Delete sends DELETE for the flow, then shows the Flows list", async () => {
    const fetch = server(204);
    await show(<DeleteFlowConfirm name="nightly-load" title="nightly-load" onCancel={vi.fn()} onError={vi.fn()} />);
    expect(host.textContent).toContain("Delete the flow nightly-load?");
    await act(async () => button("Delete nightly-load")!.click());
    expect(fetch).toHaveBeenCalledOnce();
    const [url, init] = fetch.mock.calls[0];
    expect(url).toBe("/api/v1/flows/nightly-load");
    expect(init?.method).toBe("DELETE");
    expect(window.location.hash).toBe("#/flows");
  });

  test("a sample goes back to the Library", async () => {
    server(204);
    await show(<DeleteFlowConfirm name="sample-flow" title="sample-flow" sample onCancel={vi.fn()} onError={vi.fn()} />);
    await act(async () => button("Delete sample-flow")!.click());
    expect(window.location.hash).toBe("#/library");
  });

  test("a refusal is passed on and the page stays", async () => {
    server(409);
    const error = vi.fn();
    await show(<DeleteFlowConfirm name="nightly-load" title="nightly-load" onCancel={vi.fn()} onError={error} />);
    await act(async () => button("Delete nightly-load")!.click());
    expect(error).toHaveBeenCalledOnce();
    expect((error.mock.calls[0][0] as Error).message).toContain("queued or running");
    expect(window.location.hash).toBe("#/flows/nightly-load");
  });
});
