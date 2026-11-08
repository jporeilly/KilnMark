// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { describe, expect, test } from "vitest";
import { RowIcon } from "./FlowRunTimeline";
import type { TreeRow } from "./runTree";

const row = (r: Partial<TreeRow>) => ({ id: "x", kind: "node", label: "x", depth: 1, ...r }) as TreeRow;

/** The Tabler icon a row draws, by its class (tabler-icon-hand-stop). */
async function drawn(r: TreeRow): Promise<string> {
  const host = document.createElement("div");
  const root = createRoot(host);
  await act(async () => root.render(<RowIcon row={r} />));
  const svg = host.querySelector("svg");
  const name = [...(svg?.classList ?? [])].find((c) => c.startsWith("tabler-icon-")) ?? "none";
  await act(async () => root.unmount());
  return name;
}

describe("a flow run's timeline draws each node as the diagram does", () => {
  test("a stop node has its own icon, not a tick", async () => {
    expect(await drawn(row({ type: "stop" }))).toBe("tabler-icon-hand-stop");
    expect(await drawn(row({ type: "run-pipeline" }))).toBe("tabler-icon-player-play");
    expect(await drawn(row({ type: "wait" }))).toBe("tabler-icon-hourglass");
    for (const type of ["stop", "notify", "sql", "check-source", "wait-for", "for-each", "unknown-type"])
      expect(await drawn(row({ type }))).not.toBe("tabler-icon-check");
  });
});
