/**
 * The YAML a new pipeline starts from: a read from the lab database and a write to the lab file
 * share (lab/sources.csv), with comments on what to change. All lab data is synthetic.
 */
export function starterYaml(name: string): string {
  return `kilnmark: pipeline/v1
name: ${name}
description: A sample of the lab's customers, written to the lab file share as CSV.
steps:
  # Read a table from a saved source. The source holds the credentials.
  - id: customers
    type: read
    source: lab-db
    asset: crm.customers
    row_limit: 100            # 0 reads every row

  # Add steps here (filter, compute, select, lookup, aggregate, ...): each names the step
  # feeding it with input: <id>, or input: <id>.<port> for another port such as rejected.

  # Write the rows. The source must allow writing: an admin turns on Allow writing for it.
  - id: sample
    type: write
    input: customers
    source: lab-share
    path: exports/customer-sample.csv
    format: csv
    mode: replace             # create | append | replace | upsert (with key)
`;
}

/** The YAML with its top-level name changed, if it has one. */
export function renamed(yaml: string, name: string): string {
  return yaml.replace(/^name:.*$/m, `name: ${name}`);
}
