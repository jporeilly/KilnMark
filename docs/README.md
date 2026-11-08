# Kilnmark documentation

All project documentation lives in this folder, arranged by who reads it. The user guides in
`guide/` ship inside Kilnmark: the app's **Documentation** page shows them in the six groups below,
readable offline, and the assistant answers from them. Everything after the guides is for the
people who build Kilnmark, and stays in the repository.

How the app groups the guides, and where pages that moved went, is in `ui/src/docs/groups.ts`: a
new guide goes into one of its groups, and a page or section that moves gets an entry there so old
links still arrive.

## User guides, as the app shows them

### Get started

- [Signing in, roles and tokens](guide/signing-in.md): the two ways in, the four roles and the unmask grant, using an API token, and signing in with your organisation or the bundled Keycloak
- [The Library: samples and templates](guide/library.md): the samples, kept apart from your own pipelines and flows; the Starter Pack; templates to start your own from
- [Your first pipeline](guide/first-pipeline.md): from signing in to a finished run, a step at a time
- [Asking Kilnmark](guide/assistant.md): the Ask panel, what it answers from, sources and links, and privacy

### Build

- [Building and running a pipeline](guide/pipelines.md): the Pipelines page, the canvas, YAML basics, reading, parameters and server variables, saving versions, running, trying again, and reading a run
- [Building and running a flow](guide/flows.md): flows that run pipelines in an order, conditions, for-each, timeouts, retries, priorities, checks and waits, the diagram, and reading a run
- [Expressions](guide/expressions.md): the language of conditions and computed columns: values, operators, empty values, types, functions, aggregate and window functions, and limits
- [Writing data from a pipeline](guide/writing-data.md): writing to tables (create, append, replace, upsert) and to CSV and Parquet files, the rights a write needs, and what replace keeps
- [Database tools](guide/database-tools.md): Explore a database source's schemas, tables, columns, keys, indexes and rows, and generate their SQL
- [Running pipelines and flows on a schedule](guide/schedules.md): cron times in a time zone, who a scheduled run acts as, missed and busy times, and pausing a schedule
- [Notifications and email alerts](guide/notifications.md): what can notify you, in the app and by email; the owners of pipelines and flows; choosing what you get; setting up email and team addresses

### Data quality

- [Setting data quality rules](guide/quality-rules.md): one-click rules from the profile, writing rules, thresholds, previews, alerts and recipes
- [Quality gates](guide/quality-gates.md): checking rows as a pipeline moves them, what a gate does when rows fail, the quality of what a run writes, and predicted quality with its suggestions
- [Rows that fail](guide/rows-that-fail.md): rows a node cannot work out, the `errors` port, error rows and the error tolerance

### Install and deploy

The install guides are in `guide/install/`: choosing a deployment, one page per deployment pattern,
upgrading and uninstalling. The app lists every page there in this group. Until they are there,
see [INSTALL.md](../INSTALL.md), which ships with each release.

### Administer

- [People and access](guide/people-and-access.md): making and revoking API tokens, your identity provider's groups, the bundled Keycloak and adding people, token lifetime, the access review, two people for sensitive values
- [Database drivers](guide/drivers.md): the drivers Kilnmark ships, installing Oracle's from Settings › Drivers, and registering a vendor's driver for Generic JDBC
- [The audit log](guide/audit-log.md): what is recorded, why it cannot be changed, sending it to a SIEM, and reading it
- [Backing up and restoring Kilnmark](guide/backup-and-restore.md): what to back up, the backup and restore scripts, what to check after a restore, and testing a restore
- [Branding and appearance](guide/branding.md): themes, accent colours and density; the branding folder for an organisation's name, logo and connector logos
- [Running from an external scheduler](guide/external-schedulers.md): Control-M, AutoSys, cron, systemd timers and Windows Task Scheduler; the run script, its exit codes, request keys, and waiting for a run through the API

### Reference

- [Components reference](guide/components-reference.md): every pipeline node, flow node, connector, quality rule and expression function, and how to read an entry
- [Reference: pipeline nodes](guide/reference-pipeline-nodes.md): every node with its ports, settings, what to know and an example; how nodes connect; which row wins
- [Reference: flow nodes](guide/reference-flow-nodes.md): every flow node with its controls, the values it reports, its settings and an example
- [Reference: connectors](guide/reference-connectors.md): every connector with its abilities, its driver, its settings and a write node's settings for it, and the versions tested
- [Reference: quality rules](guide/reference-quality-rules.md): every rule type, on an asset and on a gate, with its settings and an example

The settings tables in the reference are made from the catalogue the server uses, and the build
checks them (`ComponentsReferenceTest`).

## In the repository only

### Product

- [Architecture and build spec](product/architecture-spec.md): what Kilnmark is, how it is built, and why
- [Compliance](product/compliance.md): running Kilnmark in banks: DORA, NYDFS Part 500, US third-party risk, NIST 800-53, and what is missing
- [SSDF self-assessment](product/ssdf-self-assessment.md): how Kilnmark is built against NIST's Secure Software Development Framework, task by task, with the evidence and the gaps

### Design

- [UI mockups](design/mockups.md): asset page, steward inbox, relationship explorer, pipeline designer
- [Writing a connector](design/connectors.md): the connector interface, the reader and writer nodes, registering and testing
- [Connector catalogue](design/connector-catalogue.md): connections grouped by category, what each connector can do, and the waves they arrive in
- [Drivers the customer installs](design/drivers.md): drivers Kilnmark may not ship, how one is declared and installed, and registered Generic JDBC drivers
- [Pipelines](design/pipelines.md): graphs of nodes, the YAML format, expressions, the node catalogue, running and recording
- [Flows](design/flows.md): pipelines run in an order joined by connections, the condition language, how a flow runs and is recorded
- [Schedules](design/schedules.md): pipelines and flows that run at set times; the table, cron, firing at most once, missed and busy fires, daylight saving, who a run acts as
- [Parallel runs](design/parallel-runs.md): how much runs at once, worker slots, limits per source and per flow, and the for-each node
- [Flow controls](design/flow-controls.md): nodes that check or wait for what a pipeline needs, and timeout, retries and priority on a node
- [Data quality and discovery](design/data-quality.md): profiling, quality measures, rules, gates and the metadata model
- [Database tools](design/database-tools.md): Explore a source's tables, generate their SQL, connection settings on tabs, and changing a database with controls
- [The assistant](design/assistant.md): a chat window that answers from knowledge domains, starting with the documentation
- [Notifications](design/notifications.md): one event type, dedupe, masking per recipient, the email worker and the in-app feed's contract
- [APIs and version control](design/apis-and-versioning.md): Settings, the interactive API docs, version history, and what is parked
- [Benchmarks](design/benchmarks.md) (planned): how volume and throughput are measured; the data, the workloads, the machines, and the nightly check
- [The Starter Pack](design/starter-pack.md): example data, samples and people for trials, in a database of its own; turning it on and off, the guard, and the tests
- [Supply chain](design/supply-chain.md): the SBOMs every release carries, vulnerability scanning in CI, and how releases are signed
- [Lessons from earlier work](design/prior-work.md): ideas taken from an earlier Trusted Data Operations example, and the release each lands in

### Engineering

- [Development guide](development.md): set up, build, run and test
- [Decision records](adr/README.md): decisions that are hard to reverse
- [Spikes](spikes/0001-arrow-row-sets.md): 0001, Arrow between nodes, measured against row objects

### Legal

- [Code provenance](legal/provenance.md): every file adapted from third-party code
- [Third-party UI software](legal/third-party-ui.md): the web UI's packages, icons and their licences

The backlog, the plan and scope, the positioning notes and the research into other tools are kept
outside this repository, with the owner's other business documents.
