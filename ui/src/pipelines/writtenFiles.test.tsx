// @vitest-environment jsdom
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { PipelineWrittenFile, PipelineWrittenFiles } from "../api";

const SENSITIVE = "Holds sensitive columns: only people who may see sensitive values unmasked can download it.";
const DELETED = "The file this run wrote has since been deleted. Run the pipeline again to write it again.";

const file = (index: number, more: Partial<PipelineWrittenFile> = {}): PipelineWrittenFile => ({
  index,
  node: `out${index}`,
  source: "files",
  path: `samples/out${index}.csv`,
  format: "csv",
  rows: 12,
  bytes: 2048,
  modified: "2026-11-09T10:00:00Z",
  state: "ready",
  downloadable: true,
  reason: null,
  note: null,
  ...more,
});

/** What the server answers, in turn: the list as the page loads it, then as a click asks again. */
let answers: PipelineWrittenFiles[] = [];
let token = false;

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return {
    ...real,
    signedInWithToken: () => token,
    pipelinesApi: {
      ...real.pipelinesApi,
      files: vi.fn(async () => answers.length > 1 ? answers.shift()! : answers[0]),
      fileBlob: vi.fn(async () => new Blob(["id\n1\n"], { type: "text/csv" })),
    },
  };
});

const { WrittenFilesList } = await import("./WrittenFilesList");
const { pipelinesApi } = await import("../api");

let container: HTMLDivElement;
let root: Root;
const clicked: { href: string; download: string }[] = [];

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
  clicked.length = 0;
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
    clicked.push({ href: this.getAttribute("href") ?? "", download: this.download });
  });
  URL.createObjectURL = vi.fn(() => "blob:file");
  URL.revokeObjectURL = vi.fn();
  token = false;
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.restoreAllMocks();
});

async function show(finished = true) {
  await act(async () => root.render(<WrittenFilesList runId={7} finished={finished} />));
}

const button = (path: string) => container.querySelector<HTMLButtonElement>(`button[aria-label="Download ${path}"]`)!;

describe("the files a run wrote", () => {
  test("offers each file the caller may have, and says why one is off", async () => {
    answers = [{ run: 7, finished: true, files: [file(0), file(2, { downloadable: false, reason: SENSITIVE })] }];
    await show();
    expect(button("samples/out0.csv").disabled).toBe(false);
    const off = button("samples/out2.csv");
    expect(off.disabled).toBe(true);
    const why = document.getElementById(off.getAttribute("aria-describedby")!);
    expect(why?.textContent).toBe(SENSITIVE);
    expect(container.textContent).toContain("2.0 KB");
  });

  test("shows nothing while the run goes", async () => {
    answers = [{ run: 7, finished: true, files: [file(0)] }];
    await show(false);
    expect(container.textContent).toBe("");
    expect(pipelinesApi.files).not.toHaveBeenCalled();
  });

  test("a provider sign-in follows a link to the run's file by its index, never a path", async () => {
    answers = [{ run: 7, finished: true, files: [file(0)] }];
    await show();
    await act(async () => button("samples/out0.csv").click());
    expect(clicked).toEqual([{ href: "/api/v1/pipeline-runs/7/files/0", download: "out0.csv" }]);
    expect(pipelinesApi.fileBlob).not.toHaveBeenCalled();
  });

  test("a token sign-in fetches the file with its token and saves it", async () => {
    token = true;
    answers = [{ run: 7, finished: true, files: [file(0)] }];
    await show();
    await act(async () => button("samples/out0.csv").click());
    expect(pipelinesApi.fileBlob).toHaveBeenCalledWith(7, 0);
    expect(clicked).toEqual([{ href: "blob:file", download: "out0.csv" }]);
  });

  test("a file deleted since the page loaded says so, and is not fetched", async () => {
    answers = [
      { run: 7, finished: true, files: [file(0)] },
      { run: 7, finished: true, files: [file(0, { state: "deleted", downloadable: false, reason: DELETED })] },
    ];
    await show();
    await act(async () => button("samples/out0.csv").click());
    expect(container.querySelector('[role="alert"]')?.textContent).toBe(`samples/out0.csv: ${DELETED}`);
    expect(clicked).toEqual([]);
    expect(button("samples/out0.csv").disabled).toBe(true);
    expect(container.textContent).toContain("Deleted");
  });

  test("a refusal from the server is shown", async () => {
    token = true;
    answers = [{ run: 7, finished: true, files: [file(0)] }];
    vi.mocked(pipelinesApi.fileBlob).mockRejectedValueOnce(new Error(SENSITIVE));
    await show();
    await act(async () => button("samples/out0.csv").click());
    expect(container.querySelector('[role="alert"]')?.textContent).toBe(`samples/out0.csv: ${SENSITIVE}`);
  });
});
