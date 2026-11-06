// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { Asset, AssetQuality, QualityRun, RuleView } from "../api";

const URN = "urn:kilnmark:postgresql:warehouse:public.orders";

const api = vi.hoisted(() => ({
  asset: vi.fn(),
  rules: vi.fn(),
  drift: vi.fn(),
  results: vi.fn(),
  recommendations: vi.fn(),
  runAsset: vi.fn(),
  run: vi.fn(),
}));

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return { ...real, qualityApi: { ...real.qualityApi, ...api } };
});

const { QualityPanel } = await import("./QualityPanel");
const { RoleContext } = await import("./role");

const quality: AssetQuality = {
  urn: URN,
  canSeeData: false,
  canUnmask: false,
  version: {
    id: 1,
    assetUrn: URN,
    runId: 1,
    observedAt: "2026-11-06T10:00:00Z",
    rowCount: 10,
    rowCountComplete: true,
    estimate: false,
    sampledRows: null,
    samplePercent: null,
    estimatedRows: null,
    origin: "kilnmark",
    method: "scan",
    baseline: false,
  },
  scores: { asset: [], fields: {} },
  measures: [],
  sensitive: [],
  accepted: [],
  patterns: [],
};

const rule: RuleView = {
  rule: {
    id: 7,
    assetUrn: URN,
    field: "id",
    name: "id is never empty",
    type: "not_null",
    dimension: "completeness",
    params: {},
    thresholdUnit: "share",
    warnAbove: null,
    failAbove: 0,
    actions: { webhook: null, task: false },
    enabled: true,
    origin: "kilnmark",
    createdBy: "dana",
    createdAt: "2026-11-06T10:00:00Z",
    updatedAt: "2026-11-06T10:00:00Z",
  },
  latest: null,
};

const queued: QualityRun = { id: 5, status: "queued", detail: null };

let hidden = false;
Object.defineProperty(document, "hidden", { configurable: true, get: () => hidden });

const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

beforeEach(() => {
  vi.useFakeTimers();
  hidden = false;
  api.asset.mockReset().mockResolvedValue(quality);
  api.rules.mockReset().mockResolvedValue([rule]);
  api.drift.mockReset().mockResolvedValue([]);
  api.results.mockReset().mockResolvedValue([]);
  api.recommendations.mockReset().mockResolvedValue([]);
  api.runAsset.mockReset().mockResolvedValue(queued);
  api.run.mockReset().mockResolvedValue(queued);
});

afterEach(async () => {
  await act(async () => root.render(<></>));
  vi.useRealTimers();
});

/** Lets the clock run on by `ms`, with React's updates and the promises they wait on. */
const pass = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

async function showAndRun() {
  await act(async () =>
    root.render(
      <RoleContext.Provider value="steward">
        <QualityPanel asset={{ urn: URN } as Asset} />
      </RoleContext.Provider>,
    ),
  );
  await pass(0);
  const button = [...host.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Run rules now");
  expect(button).toBeDefined();
  await act(async () => button!.click());
  await pass(0);
  expect(api.runAsset).toHaveBeenCalledWith(URN);
}

describe("running an asset's rules from its Quality tab", () => {
  test("stops asking about the run once the panel is gone", async () => {
    await showAndRun();
    await pass(3000);
    expect(api.run).toHaveBeenCalledTimes(3);
    await act(async () => root.render(<></>));
    await pass(10_000);
    expect(api.run).toHaveBeenCalledTimes(3);
  });

  test("stops asking about the run once another asset is shown", async () => {
    await showAndRun();
    await pass(2000);
    expect(api.run).toHaveBeenCalledTimes(2);
    await act(async () =>
      root.render(
        <RoleContext.Provider value="steward">
          <QualityPanel asset={{ urn: `${URN}_archive` } as Asset} />
        </RoleContext.Provider>,
      ),
    );
    await pass(10_000);
    expect(api.run).toHaveBeenCalledTimes(2);
    expect(host.textContent).not.toContain("Rules did not finish");
  });

  test("stops asking once the run has finished, and says how it went", async () => {
    api.run.mockReset().mockResolvedValueOnce(queued).mockResolvedValue({ id: 5, status: "succeeded", detail: "1 rule run" });
    await showAndRun();
    await pass(10_000);
    expect(api.run).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain("Rules run: 1 rule run.");
  });

  test("does not ask while the tab is hidden, and asks again when it shows", async () => {
    await showAndRun();
    await pass(2000);
    expect(api.run).toHaveBeenCalledTimes(2);
    hidden = true;
    await pass(5000);
    expect(api.run).toHaveBeenCalledTimes(2);
    hidden = false;
    await pass(2000);
    expect(api.run).toHaveBeenCalledTimes(4);
  });
});
