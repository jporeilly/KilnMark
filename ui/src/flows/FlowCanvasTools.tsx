import { useEffect, useId, useState, type FormEvent } from "react";
import { formatTime, pipelinesApi, type FlowRunDetail } from "../api";
import { href } from "../router";
import type { FlowSelection } from "./FlowDiagram";
import { took } from "./FlowRunSummary";
import {
  addNode,
  clearLayout,
  connect,
  disconnect,
  NODE_TYPES,
  removeNode,
  runsPipeline,
  setNodeSettings,
  setWhen,
  type Change,
  type NodeTypeName,
  type SettingKind,
} from "./edit";
import { connectionSentence, kindOf, TYPE_WORDS, whenWords, type FlowGraph, type FlowGraphNode } from "./graph";
import { FlowStatus } from "./RunFlow";
import { reportedWords } from "./runFacts";

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

/**
 * The names of the saved pipelines, for the forms that ask which one a node runs; null while
 * loading, and never read unless `wanted`.
 */
export function usePipelineNames(wanted: boolean): string[] | null {
  const [names, setNames] = useState<string[] | null>(null);
  useEffect(() => {
    if (!wanted) return;
    let live = true;
    pipelinesApi
      .list()
      .then((ps) => live && setNames(ps.map((p) => p.name)))
      .catch(() => live && setNames([]));
    return () => {
      live = false;
    };
  }, [wanted]);
  return names;
}

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
 * The top of the Nodes tab, for designers: add a node, connect two, and put the nodes back where
 * the diagram lays them out. Every change goes through `apply`, as a change to the YAML (edit.ts);
 * so does anything done on the diagram itself.
 */
export function FlowAddTools({
  graph,
  text,
  pipelines,
  onSelect,
  apply,
}: {
  graph: FlowGraph;
  text: string;
  pipelines: string[] | null;
  onSelect: (s: FlowSelection | null) => void;
  apply: (c: Change, label: string) => boolean;
}) {
  const id = useId();
  const [type, setType] = useState<NodeTypeName>("run-pipeline");
  const [pipeline, setPipeline] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [choice, setChoice] = useState<WhenChoice>("success");
  const [condition, setCondition] = useState("");

  const ids = graph.nodes.map((n) => n.id);
  const fromId = ids.includes(from) ? from : (ids[0] ?? "");
  const toId = ids.includes(to) ? to : (ids.find((n) => n !== fromId) ?? "");
  const pipelineName = pipelines?.includes(pipeline) ? pipeline : (pipelines?.[0] ?? "");
  const byHand = Object.keys(graph.positions).length > 0;

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
          <p className="hint fd-tools-wide">Or drag from a node&apos;s out connector to another node: that connection is taken when it succeeds.</p>
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

/** One setting the Settings tab offers: a single value; lists and mappings stay in the YAML. */
type Field = {
  key: string;
  label: string;
  kind: SettingKind;
  hint?: string;
  /** For a word from a fixed list: [value, words], the first being what the server takes when it is left out. */
  choices?: readonly (readonly [string, string])[];
  /** Offered as a choice of the saved pipelines. */
  pipeline?: boolean;
  /** Text that runs long (a title, a message): given more room. */
  long?: boolean;
  /** The most characters the server takes, where it says. */
  most?: number;
};

const PIPELINE: Field = { key: "pipeline", label: "Pipeline", kind: "text", pipeline: true };
const VERSION: Field = { key: "version", label: "Version", kind: "number", hint: "Blank runs the latest saved version." };
const MESSAGE: Field = { key: "message", label: "Message", kind: "text", long: true, most: 2000 };

/** The settings of each node type the Settings tab offers, as the server reads them (docs/design/flows.md). */
const FIELDS: Record<string, Field[]> = {
  "run-pipeline": [PIPELINE, VERSION],
  "for-each": [
    PIPELINE,
    VERSION,
    { key: "as", label: "Parameter each item fills", kind: "text", hint: "One of the pipeline's parameters." },
    { key: "max_parallel", label: "Items at once", kind: "number", hint: "4 when blank, 50 at most." },
  ],
  notify: [
    {
      key: "severity",
      label: "Severity",
      kind: "word",
      choices: [
        ["info", "Info"],
        ["low", "Low"],
        ["medium", "Medium"],
        ["high", "High"],
      ],
    },
    { key: "title", label: "Title", kind: "text", long: true, most: 200 },
    MESSAGE,
  ],
  wait: [{ key: "for", label: "Wait for", kind: "text", hint: "For example 30s, 10m or 1h." }],
  stop: [
    {
      key: "status",
      label: "The run counts as",
      kind: "word",
      choices: [
        ["failed", "Failed"],
        ["succeeded", "Succeeded"],
      ],
    },
    MESSAGE,
  ],
  sql: [{ key: "source", label: "Source", kind: "text", hint: "A PostgreSQL source with Allow SQL on." }],
};

/** What each type keeps in the YAML only. */
const IN_THE_YAML: Record<string, string> = {
  "run-pipeline": "The pipeline's parameters (with) are in the YAML.",
  "for-each": "The items and the pipeline's other parameters (with) are in the YAML.",
  sql: "The statements are in the YAML.",
};

/** A field's value as the form starts it: as written, or the server's default for a word. */
const startOf = (f: Field, node: FlowGraphNode) => node.settings[f.key] ?? f.choices?.[0][0] ?? "";

function NodeSettings({
  node,
  text,
  pipelines,
  onSelect,
  apply,
}: {
  node: FlowGraphNode;
  text: string;
  pipelines: string[] | null;
  onSelect: (s: FlowSelection | null) => void;
  apply: (c: Change, label: string) => boolean;
}) {
  const id = useId();
  const fields = FIELDS[node.type] ?? [];
  const [values, setValues] = useState<Record<string, string>>({});
  const startKey = JSON.stringify([node.id, node.settings]);
  // The form starts from the node as the YAML has it now, and again whenever that changes.
  useEffect(() => {
    setValues(Object.fromEntries(fields.map((f) => [f.key, startOf(f, node)])));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [startKey]);
  const valueOf = (f: Field) => values[f.key] ?? startOf(f, node);
  const changed = fields.filter((f) => valueOf(f).trim() !== startOf(f, node).trim());

  const save = (e: FormEvent) => {
    e.preventDefault();
    if (changed.length === 0) return;
    apply(
      setNodeSettings(
        text,
        node.id,
        changed.map((f) => ({ key: f.key, value: valueOf(f), kind: f.kind })),
      ),
      `Changed the settings of node ${node.id}.`,
    );
  };

  return (
    <form id="fd-chosen" tabIndex={-1} className="fd-tools-row fd-chosen" aria-labelledby={`${id}-node`} onSubmit={save}>
      <h3 id={`${id}-node`} className="fd-tools-h">
        Node <span className="mono">{node.id}</span>
      </h3>
      <p className="small fd-tools-wide">
        {TYPE_WORDS[node.type] ?? node.type}: {node.summary}.
      </p>
      {fields.map((f) => {
        const fid = `${id}-${f.key}`;
        const v = valueOf(f);
        const set = (x: string) => setValues((old) => ({ ...old, [f.key]: x }));
        const offered = f.pipeline && pipelines && pipelines.length > 0 ? (pipelines.includes(v) || v === "" ? pipelines : [v, ...pipelines]) : null;
        return (
          <div key={f.key} className={f.long ? "field fd-field-long" : "field"}>
            <label htmlFor={fid}>{f.label}</label>
            {v.includes("\n") ? (
              <p id={fid} className="hint">
                Written over several lines: change it in the YAML.
              </p>
            ) : f.choices ? (
              <select id={fid} value={v} onChange={(e) => set(e.target.value)}>
                {/* A value the server would refuse is shown as written, so it can be seen and changed. */}
                {!f.choices.some(([c]) => c === v) && <option value={v}>{v}</option>}
                {f.choices.map(([c, words]) => (
                  <option key={c} value={c}>
                    {words}
                  </option>
                ))}
              </select>
            ) : offered ? (
              <select id={fid} value={v} onChange={(e) => set(e.target.value)}>
                {v === "" && <option value="">Choose a pipeline</option>}
                {offered.map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            ) : (
              <input
                id={fid}
                className={f.kind === "text" ? undefined : "mono"}
                inputMode={f.kind === "number" ? "numeric" : undefined}
                value={v}
                maxLength={f.most ?? 1000}
                aria-describedby={f.hint ? `${fid}-hint` : undefined}
                onChange={(e) => set(e.target.value)}
              />
            )}
            {f.hint && (
              <p id={`${fid}-hint`} className="hint">
                {f.hint}
              </p>
            )}
          </div>
        );
      })}
      {IN_THE_YAML[node.type] && <p className="small muted fd-tools-wide">{IN_THE_YAML[node.type]}</p>}
      <div className="buttons fd-tools-wide">
        {fields.length > 0 && (
          <button type="submit" aria-disabled={changed.length === 0}>
            Change
          </button>
        )}
        <button
          type="button"
          className="danger"
          onClick={() => {
            if (apply(removeNode(text, node.id), `Removed node ${node.id} and its connections.`)) {
              onSelect(null);
            }
          }}
        >
          Remove node
        </button>
      </div>
    </form>
  );
}

/**
 * The Settings tab: the node or connection chosen on the diagram (or in the Nodes tab), to change
 * or remove. Every change goes through `apply`, as a change to the YAML. After a removal the tab
 * goes; the panel moves the focus to the tab shown in its place, and the note under the diagram
 * says what happened.
 */
export function FlowSettings({
  graph,
  text,
  pipelines,
  selected,
  onSelect,
  apply,
}: {
  graph: FlowGraph;
  text: string;
  pipelines: string[] | null;
  selected: FlowSelection | null;
  onSelect: (s: FlowSelection | null) => void;
  apply: (c: Change, label: string) => boolean;
}) {
  const id = useId();
  const [editChoice, setEditChoice] = useState<WhenChoice>("success");
  const [editCondition, setEditCondition] = useState("");

  const chosenNode = selected && "node" in selected ? graph.nodes.find((n) => n.id === selected.node) : undefined;
  const chosenConnection =
    selected && "connection" in selected
      ? graph.connections.find((c) => c.index === selected.connection && c.from === selected.from && c.to === selected.to)
      : undefined;

  // The form starts from how the chosen connection is taken.
  useEffect(() => {
    if (!chosenConnection) return;
    const kind = kindOf(chosenConnection.when);
    setEditChoice(kind);
    setEditCondition(kind === "condition" ? chosenConnection.when : "");
  }, [chosenConnection?.index, chosenConnection?.when]);

  if (chosenNode) {
    return <NodeSettings key={chosenNode.id} node={chosenNode} text={text} pipelines={pipelines} onSelect={onSelect} apply={apply} />;
  }
  if (!chosenConnection) return <p className="muted">Choose a node or a connection on the diagram to change it here.</p>;
  return (
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
      <WhenFields choice={editChoice} condition={editCondition} onChoice={setEditChoice} onCondition={setEditCondition} idBase={`${id}-edit`} />
      <div className="buttons">
        <button type="submit">Change</button>
        <button
          type="button"
          className="danger"
          onClick={() => {
            if (apply(disconnect(text, chosenConnection.index), `Removed the connection from ${chosenConnection.from} to ${chosenConnection.to}.`)) {
              onSelect(null);
            }
          }}
        >
          Remove connection
        </button>
      </div>
    </form>
  );
}

/**
 * The Nodes tab's table, as a pipeline's: every node, what it does and what it runs after, and how
 * it went in the latest run. With `onChoose`, a node's name chooses it, to show its settings.
 */
export function FlowNodesTable({
  graph,
  run,
  onChoose,
}: {
  graph: FlowGraph;
  run: FlowRunDetail | null;
  onChoose?: (node: string) => void;
}) {
  return (
    <div className="table-scroll" role="region" aria-label="Nodes table" tabIndex={0}>
      <table className="small">
        <thead>
          <tr>
            <th scope="col">Node</th>
            <th scope="col">What it does</th>
            <th scope="col">Runs after</th>
            {run && (
              <>
                <th scope="col">Run {run.run.id}</th>
                <th scope="col">Started</th>
                <th scope="col">Took</th>
                <th scope="col">What happened</th>
              </>
            )}
          </tr>
        </thead>
        <tbody>
          {graph.nodes.map((n) => {
            const after = graph.connections.filter((c) => c.to === n.id);
            const ran = run?.nodes.find((r) => r.node === n.id);
            const said = ran ? [ran.message, reportedWords(ran)].filter(Boolean).join(". ") : "";
            return (
              <tr key={n.id}>
                <th scope="row">
                  {onChoose ? (
                    <button type="button" className="pl-link mono" onClick={() => onChoose(n.id)} aria-label={`${n.id}: show its settings`}>
                      {n.id}
                    </button>
                  ) : (
                    <span className="mono">{n.id}</span>
                  )}
                </th>
                <td>
                  {TYPE_WORDS[n.type] ?? n.type}
                  <div className="muted">{n.summary}</div>
                </td>
                <td>
                  {after.length === 0 ? (
                    <span className="muted">Starts with the run</span>
                  ) : (
                    after.map((c) => (
                      <div key={c.index} title={c.when}>
                        <span className="mono">{c.from}</span>, {c.kind === "condition" ? `when ${whenWords(c)}` : whenWords(c)}
                      </div>
                    ))
                  )}
                </td>
                {run && (
                  <>
                    <td>{ran ? <FlowStatus status={ran.status} /> : <span className="muted">Not in that run</span>}</td>
                    <td>{ran?.startedAt ? formatTime(ran.startedAt) : "–"}</td>
                    <td>{ran ? took(ran.startedAt, ran.finishedAt) : "–"}</td>
                    <td>
                      {said || <span className="muted">–</span>}
                      {ran?.pipelineRunId != null && (
                        <div>
                          <a href={href.pipelineRun(ran.pipelineRunId)}>Pipeline run {ran.pipelineRunId}</a>
                        </div>
                      )}
                    </td>
                  </>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
