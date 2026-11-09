// @vitest-environment jsdom
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import type { Connector, ConnectorCatalogue } from "../api";
import { ConnectorGallery } from "./ConnectorGallery";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function connector(kind: string, displayName: string, driver: Partial<Connector>): Connector {
  return {
    kind,
    displayName,
    settings: [],
    containerSetting: "schemas",
    containerLabel: "Schemas",
    containerCounted: "tables",
    category: "databases",
    family: "relational",
    capabilities: ["discover", "read", "write"],
    formats: [],
    icon: "database",
    monogram: null,
    docsAnchor: kind,
    addressFields: true,
    driverBundled: false,
    ...driver,
  };
}

const catalogue = (oracleDriver: Connector["driver"]): ConnectorCatalogue => ({
  showPlanned: false,
  categories: [
    {
      id: "databases",
      label: "Databases",
      planned: [],
      connectors: [
        connector("oracle", "Oracle", { driver: oracleDriver, driverName: "ojdbc11", driverVersion: "23.26.3.0.0" }),
        connector("postgresql", "PostgreSQL", { driver: "bundled", driverBundled: true, driverName: null, driverVersion: null }),
      ],
    },
  ],
});

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

function card(name: string) {
  return [...host.querySelectorAll("li")].find((li) => li.querySelector(".card-name")?.textContent === name)!;
}

describe("a connector's driver on its card", () => {
  test("a missing driver names the exact version to install", () => {
    act(() => root.render(<ConnectorGallery catalogue={catalogue("missing")} chosen={null} onChoose={() => {}} />));
    const oracle = card("Oracle");
    expect(oracle.textContent).toContain("Needs its driver");
    expect(oracle.querySelector(".card-driver")?.textContent).toBe("Tested with ojdbc11 23.26.3.0.0");
    expect(oracle.querySelector(".card-driver-link")?.textContent).toContain("Install the Oracle driver");
  });

  test("an installed driver still shows which one", () => {
    act(() => root.render(<ConnectorGallery catalogue={catalogue("ready")} chosen={null} onChoose={() => {}} />));
    const oracle = card("Oracle");
    expect(oracle.textContent).not.toContain("Needs its driver");
    expect(oracle.querySelector(".card-driver")?.textContent).toBe("Driver ojdbc11 23.26.3.0.0");
    expect(oracle.querySelector(".card-driver-link")).toBeNull();
  });

  test("a driver Kilnmark ships shows none", () => {
    act(() => root.render(<ConnectorGallery catalogue={catalogue("missing")} chosen={null} onChoose={() => {}} />));
    expect(card("PostgreSQL").querySelector(".card-driver")).toBeNull();
  });
});
