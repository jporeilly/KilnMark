import { useEffect, useId, useState, type FormEvent } from "react";
import { pipelinesApi } from "../api";
import type { FlowSelection } from "./FlowDiagram";
import { addNode, clearLayout, connect, disconnect, NODE_TYPES, removeNode, runsPipeline, setWhen, type Change, type NodeTypeName } from "./edit";
import { connectionSentence, kindOf, TYPE_WORDS, type FlowGraph } from "./graph";

/** The ways a connection is taken, as the form offers them. */
const WHEN_CHOICES = [
  ["success", "When it succeeds"],
  ["failure", "When it fails"],
  ["always", "Always, when it ends"],
  ["condition", "When it succeeds and a condition holds"],
] as const;
type WhenChoice = (typeof WHEN_CHOICES)[number][0];

/** A `when` from the form: the word, or the condition's text. */
const whenOf = (choice: WhenChoice, condition: string) => (choice === "condition" ? condition : choice);

const NO_CONDITION: Change = { error: "Write the condition, for example clean.score >= good." };
const blank = (choice: WhenChoice, condition: string) => choice === "condition" && condition.trim() === "";

/** After a removal its tools are gone: the focus goes to the note saying what happened. */
const focusNote = () => requestAnimationFrame(() => document.getElementById("fd-note")?.focus());

function WhenFields({
  choice,
  condition,
  onChoice,
  onCondition,
  idBase,
}: {
  choice: WhenChoice;
  condition: string;
  onChoice: (c: WhenChoice) => void;
  onCondition: (c: string) => void;
  idBase: string;
}) {
  return (
    <>
      <div className="field">
        <label htmlFor={`${idBase}-when`}>Taken</label>
        <select id={`${idBase}-when`} value={choice} onChange={(e) => onChoice(e.target.value as WhenChoice)}>
          {WHEN_CHOICES.map(([v, words]) => (
            <option key={v} value={v}>
              {words}
            </option>
          ))}
        </select>
      </div>
      {choice === "condition" && (
        <div className="field fd-tools-wide">
          <label htmlFor={`${idBase}-cond`}>Condition</label>
          <input
            id={`${idBase}-cond`}
            className="mono"
            value={condition}
            maxLength={1000}
            placeholder="clean.score >= good"
            aria-describedby={`${idBase}-cond-hint`}
            onChange={(e) => onCondition(e.target.value)}
          />
          <p id={`${idBase}-cond-hint`} className="hint">
            What earlier nodes reported and the flow&apos;s parameters, for example{" "}
            <span className="mono">clean.quarantined_share &lt; 0.02</span>.
          </p>
        </div>
      )}
    </>
  );
}

/**
 * The diagram's editing tools, under it: add a node, connect two, change or remove what is chosen
 * on the diagram, and put the nodes back where the diagram lays them out. Every change goes through
 * `apply`, as a change to the YAML (edit.ts); so does anything done on the diagram itself.
 */
export function FlowCanvasTools({
  graph,
  text,
  selected,
  onSelect,
  apply,
}: {
  graph: FlowGraph;
  text: string;
  selected: FlowSelection | null;
  onSelect: (s: FlowSelection | null) => void;
  apply: (c: Change, label: string) => boolean;
}) {
  const id = useId();
  const [pipelines, setPipelines] = useState<string[] | null>(null);
  const [type, setType] = useState<NodeTypeName>("run-pipeline");
  const [pipeline, setPipeline] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [choice, setChoice] = useState<WhenChoice>("success");
  const [condition, setCondition] = useState("");
  const [editChoice, setEditChoice] = useState<WhenChoice>("success");
  const [editCondition, setEditCondition] = useState("");

  useEffect(() => {
    pipelinesApi
      .list()
      .then((ps) => setPipelines(ps.map((p) => p.name)))
      .catch(() => setPipelines([]));
  }, []);

  const ids = graph.nodes.map((n) => n.id);
  const fromId = ids.includes(from) ? from : (ids[0] ?? "");
  const toId = ids.includes(to) ? to : (ids.find((n) => n !== fromId) ?? "");
  const pipelineName = pipelines?.includes(pipeline) ? pipeline : (pipelines?.[0] ?? "");
  const byHand = Object.keys(graph.positions).length > 0;

  const chosenNode = selected && "node" in selected ? graph.nodes.find((n) => n.id === selected.node) : undefined;
  const chosenConnection =
    selected && "connection" in selected
      ? graph.connections.find((c) => c.index === selected.connection && c.from === selected.from && c.to === selected.to)
      : undefined;

  // The inspector starts from how the chosen connection is taken.
  useEffect(() => {
    if (!chosenConnection) return;
    const kind = kindOf(chosenConnection.when);
    setEditChoice(kind);
    setEditCondition(kind === "condition" ? chosenConnection.when : "");
  }, [chosenConnection?.index, chosenConnection?.when]);

  const add = (e: FormEvent) => {
    e.preventDefault();
    // Placed by hand, a new node goes to the right of the others.
    const xs = Object.values(graph.positions);
    const at = xs.length ? { x: Math.max(...xs.map((p) => p.x)) + 280, y: Math.min(...xs.map((p) => p.y)) } : undefined;
    const c = addNode(text, type, { pipeline: runsPipeline(type) ? pipelineName || undefined : undefined, at });
    if (apply(c, "id" in c && c.id ? `Added node ${c.id}.` : "")) {
      if ("id" in c && c.id) onSelect({ node: c.id });
    }
  };

  const join = (e: FormEvent) => {
    e.preventDefault();
    apply(blank(choice, condition) ? NO_CONDITION : connect(text, fromId, toId, whenOf(choice, condition)), `Connected ${fromId} to ${toId}.`);
  };

  return (
    <div className="fd-tools">
      <form className="fd-tools-row" onSubmit={add} aria-labelledby={`${id}-add`}>
        <h3 id={`${id}-add`} className="fd-tools-h">
          Add a node
        </h3>
        <div className="field">
          <label htmlFor={`${id}-type`}>Type</label>
          <select id={`${id}-type`} value={type} onChange={(e) => setType(e.target.value as NodeTypeName)}>
            {NODE_TYPES.map((t) => (
              <option key={t} value={t}>
                {TYPE_WORDS[t]}
              </option>
            ))}
          </select>
        </div>
        {runsPipeline(type) && (
          <div className="field">
            <label htmlFor={`${id}-pipeline`}>Pipeline</label>
            {pipelines && pipelines.length > 0 ? (
              <select id={`${id}-pipeline`} value={pipelineName} onChange={(e) => setPipeline(e.target.value)}>
                {pipelines.map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            ) : (
              <p id={`${id}-pipeline`} className="hint">
                {pipelines ? "No pipelines yet: name one in the YAML once it is saved." : "Loading…"}
              </p>
            )}
          </div>
        )}
        <div className="buttons">
          <button type="submit">Add node</button>
        </div>
      </form>

      {ids.length > 1 && (
        <form className="fd-tools-row" onSubmit={join} aria-labelledby={`${id}-join`}>
          <h3 id={`${id}-join`} className="fd-tools-h">
            Connect two nodes
          </h3>
          <div className="field">
            <label htmlFor={`${id}-from`}>From</label>
            <select id={`${id}-from`} value={fromId} onChange={(e) => setFrom(e.target.value)}>
              {ids.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${id}-to`}>To</label>
            <select id={`${id}-to`} value={toId} onChange={(e) => setTo(e.target.value)}>
              {ids.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </div>
          <WhenFields choice={choice} condition={condition} onChoice={setChoice} onCondition={setCondition} idBase={`${id}-new`} />
          <div className="buttons">
            <button type="submit">Connect</button>
          </div>
          <p className="hint fd-tools-wide">Or drag from a node&apos;s right edge to another node: that connection is taken when it succeeds.</p>
        </form>
      )}

      {chosenNode && (
        <div id="fd-chosen" tabIndex={-1} className="fd-tools-row fd-chosen" role="group" aria-labelledby={`${id}-node`}>
          <h3 id={`${id}-node`} className="fd-tools-h">
            Node <span className="mono">{chosenNode.id}</span>
          </h3>
          <p className="small fd-tools-wide">
            {TYPE_WORDS[chosenNode.type] ?? chosenNode.type}: {chosenNode.summary}. Its settings are in the YAML below.
          </p>
          <div className="buttons">
            <button
              type="button"
              className="danger"
              onClick={() => {
                if (apply(removeNode(text, chosenNode.id), `Removed node ${chosenNode.id} and its connections.`)) {
                  onSelect(null);
                  focusNote();
                }
              }}
            >
              Remove node
            </button>
          </div>
        </div>
      )}

      {chosenConnection && (
        <form
          id="fd-chosen"
          tabIndex={-1}
          className="fd-tools-row fd-chosen"
          aria-labelledby={`${id}-conn`}
          onSubmit={(e) => {
            e.preventDefault();
            apply(
              blank(editChoice, editCondition) ? NO_CONDITION : setWhen(text, chosenConnection.index, whenOf(editChoice, editCondition)),
              `Changed when the connection from ${chosenConnection.from} to ${chosenConnection.to} is taken.`,
            );
          }}
        >
          <h3 id={`${id}-conn`} className="fd-tools-h">
            Connection from <span className="mono">{chosenConnection.from}</span> to <span className="mono">{chosenConnection.to}</span>
          </h3>
          <p className="small fd-tools-wide">{connectionSentence(chosenConnection)}.</p>
          <WhenFields
            choice={editChoice}
            condition={editCondition}
            onChoice={setEditChoice}
            onCondition={setEditCondition}
            idBase={`${id}-edit`}
          />
          <div className="buttons">
            <button type="submit">Change</button>
            <button
              type="button"
              className="danger"
              onClick={() => {
                if (apply(disconnect(text, chosenConnection.index), `Removed the connection from ${chosenConnection.from} to ${chosenConnection.to}.`)) {
                  onSelect(null);
                  focusNote();
                }
              }}
            >
              Remove connection
            </button>
          </div>
        </form>
      )}

      {byHand && (
        <div className="fd-tools-row">
          <p className="small fd-tools-wide">
            The nodes are placed by hand, in the <span className="mono">layout:</span> block.
          </p>
          <div className="buttons">
            <button type="button" onClick={() => apply(clearLayout(text), "The diagram lays the flow out itself again.")}>
              Lay out again
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
