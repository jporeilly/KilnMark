import { useState } from "react";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";
import type { SampleAbout as About, SampleExplanation } from "../api";
import { docHref } from "../docs/links";
import "./explanation.css";

/** Whether an explanation has anything to show beyond its title. */
export function explains(e: SampleExplanation | null | undefined): e is SampleExplanation {
  return !!e && !!(e.shows || e.why || e.lookFor || e.needs);
}

/**
 * A part of an explanation as the start of a sentence: its first word capitalised ("the note under
 * the read node" reads "The note under the read node") when it is a plain word. A part that starts
 * with something to type (inputs:, lpad, group_by, shop-db, ${…}) is left as written.
 */
export function sentenceStart(text: string): string {
  return /^[a-z][a-z']*\s/.test(text) ? text.charAt(0).toUpperCase() + text.slice(1) : text;
}

/**
 * A sample's or template's explanation, from the comment its file starts with: what it shows (for a
 * template, what it is for), why it matters, what to look for, and what it needs. Parts left out are
 * not shown.
 */
export function Explanation({ explanation: e, template = false }: { explanation: SampleExplanation | null; template?: boolean }) {
  if (!explains(e)) return null;
  const parts: [string, string | null][] = [
    [template ? "What it is for" : "What it shows", e.shows],
    ["Why it matters", e.why],
    [template ? "Look for" : "Look for when it runs", e.lookFor],
    ["Needs", e.needs],
  ];
  return (
    <dl className="lib-explain">
      {parts
        .filter((p): p is [string, string] => !!p[1])
        .map(([label, text]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{sentenceStart(text)}</dd>
          </div>
        ))}
    </dl>
  );
}

/** The business scenarios' samples, which the scenarios guide walks through; the rest are in the Library guide. */
export const isScenario = (file: string) => file.startsWith("10-scenarios/");

/** Where the guide says more about a sample: the business scenarios, or the Library's samples. */
export const sampleGuideHref = (file: string) => (isScenario(file) ? docHref("scenarios") : docHref("library", "samples"));

/** Where a viewer's choice to keep About this sample closed is remembered. */
export const ABOUT_OPEN_KEY = "kilnmark.sample.about";

function savedAboutOpen(): boolean {
  try {
    return window.localStorage.getItem(ABOUT_OPEN_KEY) !== "collapsed";
  } catch {
    return true;
  }
}

/**
 * About this sample, on the page of a pipeline or flow added from the samples (and in the canvas's
 * pipeline settings): the sample's own explanation, sent by the server from the bundled file so it
 * reads as the Library and the YAML do, with a link to the guide. Once the pipeline or flow has been
 * changed since it was added, it says so: the explanation is of the sample, not of the changes.
 * Its heading opens and closes it. It starts open; a viewer who closes it finds it closed on every
 * sample until they open it again.
 */
export function SampleAbout({
  about,
  kind,
  id,
  level = 2,
}: {
  about: About | null | undefined;
  kind: "pipeline" | "flow";
  /** The heading's id, unique on the page. */
  id: string;
  level?: 2 | 3;
}) {
  const [open, setOpenState] = useState(savedAboutOpen);
  if (!about || !explains(about.explanation)) return null;
  const setOpen = (o: boolean) => {
    setOpenState(o);
    try {
      window.localStorage.setItem(ABOUT_OPEN_KEY, o ? "open" : "collapsed");
    } catch {
      // A remembered panel is a convenience only.
    }
  };
  const Heading = level === 2 ? "h2" : "h3";
  const Chevron = open ? IconChevronDown : IconChevronRight;
  return (
    <section className={`lib-sample-about${open ? "" : " is-closed"}`} aria-labelledby={id}>
      <Heading id={id} className="lib-sample-about-h">
        <button
          type="button"
          className="lib-sample-about-toggle"
          aria-expanded={open}
          aria-controls={`${id}-body`}
          onClick={() => setOpen(!open)}
        >
          <Chevron size={18} stroke={1.8} aria-hidden="true" />
          About this sample
        </button>
        {about.changed && <span className="lib-changed">Based on a sample; changed since</span>}
      </Heading>
      <div id={`${id}-body`} className="lib-sample-about-body" hidden={!open}>
        {about.changed && (
          <p className="small muted">
            This {kind} started as the sample below and has been changed since it was added, so it may no longer do all of
            what the sample describes.
          </p>
        )}
        <Explanation explanation={about.explanation} />
        <p className="small">
          <a href={sampleGuideHref(about.file)}>{isScenario(about.file) ? "The business scenarios, in the guide" : "The samples, in the guide"}</a>
          <span className="muted"> · samples/{about.file}</span>
        </p>
      </div>
    </section>
  );
}
