# Kilnmark documentation

All project documentation lives in this folder.

## Product

- [Architecture and build spec](product/architecture-spec.md): what Kilnmark is, how it is built, and why
- [Plan and scope](product/plan-and-scope.md): v0.1 scope, sprints, risks and open decisions
- [Positioning and design partners](product/positioning-and-design-partners.md): who it is for and how to recruit early users
- [Compliance](product/compliance.md): running Kilnmark in banks: DORA, NYDFS Part 500, US third-party risk, NIST 800-53, and what is missing
- [SSDF self-assessment](product/ssdf-self-assessment.md): how Kilnmark is built against NIST's Secure Software Development Framework, task by task, with the evidence and the gaps
- [Backlog](product/backlog.md): what is decided and not yet done, in order

The live, editable versions of these three documents are in Claude; the copies here were exported
on 24 October 2026. Diagrams embedded in the originals appear as placeholders here.

## User guides

- [Asking Kilnmark](guide/assistant.md): the Ask panel, what it answers from, sources and links, and privacy
- [Setting data quality rules](guide/quality-rules.md): one-click rules from the profile, writing rules, thresholds, previews, alerts and recipes
- [Writing data from a pipeline](guide/writing-data.md): writing to tables (create, append, replace, upsert) and to CSV and Parquet files, the rights a write needs, and what replace keeps
- [Branding and appearance](guide/branding.md): themes, accent colours and density; the branding folder for an organisation's name, logo and connector logos
- [Building and running a pipeline](guide/pipelines.md): the Pipelines page, YAML basics, checking as you type, saving versions, running, and reading a run
- [Components reference](guide/components-reference.md): every pipeline node, flow node, connector, quality rule and expression function, with every setting, its type and default, and an example; the settings tables are made from the server's own catalogue ([pipeline nodes](guide/reference-pipeline-nodes.md), [flow nodes](guide/reference-flow-nodes.md), [connectors](guide/reference-connectors.md), [quality rules](guide/reference-quality-rules.md))
- [Pipeline nodes and expressions](guide/pipeline-nodes.md): filter, compute, select, lookup, aggregate, union, dedupe, route and limit, and the expression language for conditions and computed columns
- [The Library: samples and templates](guide/library.md): the samples, kept apart from your own pipelines and flows; the starter pack; templates to start your own from
- [Building and running a flow](guide/flows.md): flows that run pipelines in an order, connections and conditions, the diagram, running a flow and reading a run
- [Running pipelines and flows on a schedule](guide/schedules.md): cron times in a time zone, who a scheduled run acts as, what happens to a missed or busy time, and pausing a schedule
- [Running from an external scheduler](guide/external-schedulers.md): Control-M, AutoSys, cron, systemd timers and Windows Task Scheduler; the run script, its exit codes, request keys, and waiting for a run through the API
- [Installing database drivers](guide/drivers.md): the drivers Kilnmark ships, and registering one whose licence keeps it out of the release
- [Backing up and restoring Kilnmark](guide/backup-and-restore.md): what to back up, the backup and restore scripts, what to check after a restore, and testing a restore
- [The audit log](guide/audit-log.md): what is recorded, why it cannot be changed, sending it to a SIEM, and reading it
- [Signing in, roles and tokens](guide/signing-in.md): API tokens and signing in with your organisation, the four roles and the unmask grant, token lifetime, the access review and two people for sensitive values
- [Database tools](guide/database-tools.md): Explore a database source's schemas, tables, columns, keys, indexes and rows
- [Notifications and email alerts](guide/notifications.md): what can notify you, in the app and by email; choosing what you get; setting up email and team addresses

## Spikes

- [0001 Arrow row sets](spikes/0001-arrow-row-sets.md): Arrow between nodes, measured against row objects

## Design

- [UI mockups](design/mockups.md): asset page, steward inbox, relationship explorer, pipeline designer
- [Writing a connector](design/connectors.md): the connector interface, the reader and writer nodes, registering and testing
- [Pipelines](design/pipelines.md): graphs of nodes, the YAML format, expressions, the node catalogue, running and recording
- [Flows](design/flows.md): pipelines run in an order joined by connections (on success, on failure, always, or on a quality condition), conditions, how a flow runs and is recorded
- [Schedules](design/schedules.md): pipelines and flows that run at set times; the table, cron, firing at most once, missed and busy fires, daylight saving, who a run acts as
- [Parallel runs](design/parallel-runs.md): how much runs at once, worker slots, limits per source and per flow, and the for-each node
- [Database tools](design/database-tools.md): Explore a source's tables, generate their SQL, connection settings on tabs, and changing a database with controls
- [Flow controls](design/flow-controls.md): nodes that check or wait for what a pipeline needs, and timeout, retries and priority on a node
- [The assistant](design/assistant.md): a chat window that answers from knowledge domains, starting with the documentation
- [Notifications](design/notifications.md): one event type, dedupe, masking per recipient, the email worker and the in-app feed's contract
- [Supply chain](design/supply-chain.md): the SBOMs every release carries, vulnerability scanning in CI, and how releases are signed
- [Lessons from earlier work](design/prior-work.md): ideas taken from an earlier Trusted Data Operations example, and the release each lands in

## Engineering

- [Development guide](development.md): set up, build, run and test
- [Decision records](adr/README.md): decisions that are hard to reverse
- [Code provenance](legal/provenance.md): every file adapted from third-party code
- [Third-party UI software](legal/third-party-ui.md): the web UI's packages, icons and their licences
