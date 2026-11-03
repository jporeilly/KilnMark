/**
 * The YAML a new flow starts from: one node running a pipeline and, when it fails, a notice and a
 * stop, so the run counts as failed; and a parameter. `pipeline` is one of the person's pipelines;
 * a placeholder in angle brackets otherwise.
 */
export function starterFlow(pipeline?: string): string {
  return `kilnmark: flow/v1
name: my-flow
description: Run a pipeline, and say so if it fails.

parameters:
  label: { type: text, default: nightly }

nodes:
  - id: first
    type: run-pipeline
    pipeline: ${pipeline ?? "<a pipeline>"}

  - id: tell
    type: notify
    severity: medium
    title: "The \${label} run failed"
    message: Look at the pipeline run for why.

  - id: halt
    type: stop
    message: The pipeline failed

connections:
  - { from: first, to: tell, when: failure }
  - { from: tell, to: halt }
`;
}
