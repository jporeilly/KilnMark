// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { SampleAbout as About } from "../api";
import { ABOUT_OPEN_KEY, SampleAbout } from "./Explanation";

// About this sample opens and closes from its heading, and a viewer's choice is remembered.

const ABOUT: About = {
  file: "10-scenarios/1-onboarding/merge.yaml",
  explanation: { title: "Merge", shows: "Merges the customers.", why: null, lookFor: "the quarantine node.", needs: null },
  changed: false,
};

let root: Root | null = null;
let host: HTMLElement | null = null;

beforeEach(() => window.localStorage.clear());
afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  host?.remove();
  root = null;
});

async function mount(node: React.ReactNode) {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(node));
  return host;
}

describe("About this sample opens and closes", () => {
  test("it starts open; its heading closes it and opens it again", async () => {
    const el = await mount(<SampleAbout about={ABOUT} kind="pipeline" id="about" />);
    const toggle = el.querySelector<HTMLButtonElement>("h2 button")!;
    const body = el.querySelector<HTMLElement>("#about-body")!;
    expect(toggle.textContent).toBe("About this sample");
    expect(toggle.getAttribute("aria-controls")).toBe("about-body");
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(body.hidden).toBe(false);
    expect(body.textContent).toContain("Merges the customers.");

    await act(async () => toggle.click());
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(body.hidden).toBe(true);
    // Closed, its text is still on the page for the section's name, but hidden from everyone.
    expect(el.querySelector("section")!.getAttribute("aria-labelledby")).toBe("about");

    await act(async () => toggle.click());
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(body.hidden).toBe(false);
  });

  test("a viewer who closed it finds it closed on the next sample, in the settings under the canvas too", async () => {
    const el = await mount(<SampleAbout about={ABOUT} kind="pipeline" id="about" />);
    await act(async () => el.querySelector<HTMLButtonElement>("h2 button")!.click());
    expect(window.localStorage.getItem(ABOUT_OPEN_KEY)).toBe("collapsed");
    await act(async () => root!.unmount());
    host!.remove();

    const next = await mount(<SampleAbout about={ABOUT} kind="flow" id="other" level={3} />);
    const toggle = next.querySelector<HTMLButtonElement>("h3 button")!;
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(next.querySelector<HTMLElement>("#other-body")!.hidden).toBe(true);
    await act(async () => toggle.click());
    expect(window.localStorage.getItem(ABOUT_OPEN_KEY)).toBe("open");
  });
});
