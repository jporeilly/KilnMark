# Kilnmark — Positioning & Design Partners

Oct 24, 2026 · @James O'Reilly

> **Superseded in part by [ADR 0021](../adr/0021-pipeline-first.md):** Kilnmark is a modern, quality-aware data integration engine, not a catalogue. The living version of this document is kept up to date outside the repository.

A first cut of who Kilnmark is for and why they'd pick it, plus a plan to recruit three design partners to test it against real data. Everything here is a hypothesis until partners confirm it.

## Positioning

> **Kilnmark is the on-prem trusted-data engine: it finds, profiles and scores every data asset you own, then acts on what it finds.** Catalogs describe data; Kilnmark also moves, masks, tiers and certifies it, with every action governed and traced.

**Target customer (first 12 months)**

- Mid-to-large organisations that must keep metadata on their own infrastructure: financial services, public sector, utilities, healthcare
- A mixed estate of databases, file shares and object storage, much of it on-prem
- Teams already using Kettle, PDI or Apache Hop, who will recognise the pipeline model instantly
- Customers of discontinued catalog and storage-optimisation products who need a supported path forward

**Not the target (yet):** cloud-only teams happy with SaaS, very large enterprises needing a global multi-tenant catalog, and BI-led buyers looking for dashboards.

## Why now

- **Orphaned customers.** Pentaho has dropped Data Catalog and Data Optimizer as its new owners refocus the portfolio, leaving those customers without a roadmap. Confirm the public announcement before citing this externally.
- **AI agents need trusted context.** The whole market now pitches the catalog as the context layer feeding AI agents at runtime ([dupple](https://dupple.com/learn/best-ai-data-catalog-tools)); on-prem buyers want that without shipping metadata to a SaaS.
- **Enterprise catalogs are slow and expensive.** One vendor comparison puts Collibra at around $170K a year with 6 to 12 months to implement, and Alation at around $198K with about 5 to 6 months ([Atlan, a competitor](https://atlan.com/know/how-to-choose-collibra-vs-alation/)). Treat these as indicative.
- **Open source is a platform to run.** DataHub and OpenMetadata are free to license but need several services operated in production ([modern-datatools](https://www.modern-datatools.com/tools/openmetadata)).

## The problem

Regulated, on-prem organisations need to know what data they hold, where the sensitive data is, whether it can be trusted, and to act on that: archive what is cold, quarantine what is exposed, certify what is good. Today that takes a catalog, a storage-optimisation tool, an ETL tool and a lot of glue. Kilnmark is one product for the whole loop.

## Differentiators

1. **It acts, not just describes.** Rules match assets on classification, trust, temperature or usage and fire actions: tag, tier, purge, quarantine, or run a pipeline. Every action is dry-run first, approved where destructive, and traced in lineage.
2. **On-prem by design.** Two services on one VM, offline install, no calls home. Scales out to Kubernetes later without a rewrite.
3. **Discovery and trust built in.** PII, keys and join paths, duplicates and drift feed an explainable trust score per asset, rather than being add-ons.
4. **Works with the pipelines you already have.** An importer converts supported Kettle/PDI transformations and jobs into Kilnmark pipelines, so former PDI customers keep their investment.
5. **Private AI.** Descriptions, classification and natural-language search run on local models by default; nothing leaves the network unless the customer chooses a hosted model. Agents connect through MCP.
6. **Open, so no lock-in.** Cypher and GraphQL over the metadata graph, OpenLineage in and out, plain CSV/JSON import and export.

## Competitive comparison

An honest view: where each alternative wins, and where Kilnmark should.

| Alternative | Where it wins | Where Kilnmark wins |
| --- | --- | --- |
| OpenMetadata / DataHub | Free, large communities, 80+ connectors, mature lineage | One product that also acts on data; far less to operate on-prem; trust scoring and discovery built in |
| Atlan | Polished, AI-first active metadata and agent context | Runs on the customer's own servers, including offline; no metadata leaves the building |
| Collibra / Alation | Deep governance workflows, analyst recognition, enterprise trust | Weeks not months to deploy; a fraction of the cost; actions, not just policy |
| Informatica CDGC | Broadest enterprise suite, CLAIRE AI | Not tied to a platform purchase; lean and on-prem |
| Komprise | Petabyte-scale unstructured tiering, transparent access | Covers databases as well as files; classification, glossary and trust in one model |
| Kettle / PDI | Familiar to many teams, hundreds of steps | Catalog, discovery, trust and governed rules in one product, with an importer for existing jobs |

**Biggest risk to this positioning:** buyers seeing "catalog plus actions" as two half-products. Design partners must confirm the combined loop is worth paying for.

## Messages by persona

| Persona | What they care about | What to say |
| --- | --- | --- |
| Head of data governance / CDO | Knowing where sensitive data is; proving control to auditors | "See every sensitive asset, its owner and its trust score, and show the auditor the trail of every action taken." |
| Data steward | Endless manual tagging and documenting | "Kilnmark proposes classifications, terms and descriptions; you approve in batches." |
| Infrastructure / storage lead | Growing storage cost, ransomware exposure, cold data | "Find what's cold, redundant or exposed, see the saving before anything moves, then tier it by rule." |
| Data engineer / ETL team | Lineage, impact analysis, keeping existing pipelines | "Bring your Kettle jobs across; Kilnmark runs them, traces them and tells you what breaks before you change a column." |
| Security / compliance | Data leaving the network, AI risk | "Runs entirely on your servers, AI included, with nothing calling home." |

## Design-partner programme

Three organisations, chosen to cover different estates, who run early builds on real (or realistic) data and shape the product.

**Ideal partner profile**

- On-prem or hybrid estate with at least one relational database and one file share or object store
- A named person who owns data governance or storage cost, with time to give
- A test environment Kilnmark can be installed into
- Ideally one each from: financial services, public sector, and a Kettle/PDI/Hop user

**What they get**

- Free use for the programme plus a discounted first year if they buy
- Direct line to the founder and real influence on the roadmap
- Help installing and a short report on what Kilnmark found in their estate

**What we ask**

- Install each release in a test environment
- A 45-minute call every two weeks, and honest feedback
- Permission to use anonymised findings, and a reference if they are happy

**Timeline**

| Stage | When |
| --- | --- |
| Recruit and qualify | From now until v0.1 |
| First install (v0.1: ingest, profile, search) | About 12 weeks after sprint 0 |
| Discovery and trust (v0.2) | Next release cycle |
| Rules and actions (v0.3) | The release that tests the core claim |
| Decide: buy, extend or part ways | After v0.3 |

## Finding and qualifying partners

**Where to look**

- Your own professional network on LinkedIn, especially data and governance leads you already know
- Kettle and PDI user communities and forums
- UK public-sector and financial-services data communities and meetups
- Organisations publicly known to run discontinued catalog or storage-optimisation products

> **Keep it clean.** Reach people through public or personal channels only, never a former employer's customer lists or CRM. Check the contract for a non-solicitation clause covering customers, which is separate from the IP clause and often runs 6 to 12 months after leaving.

**Qualifying questions**

1. Where does your data live today, and how much is on-prem?
2. Can you list where your sensitive data is, and how long would it take to prove it?
3. What do you use now for cataloguing, lineage and storage tiering, and what's missing?
4. Can metadata or AI processing leave your network?
5. Do you run Kettle or PDI jobs?
6. Would you install a pre-release in a test environment within the next three months?
7. Who would own this, and could they give an hour every two weeks?

## Outreach note

For LinkedIn or email, sent from personal accounts after the retirement date. Swap the bracketed parts per person.

> **Subject:** Looking for three design partners: on-prem data discovery that acts
>
> Hi \[Name\],
>
> After many years in data integration and governance, I'm building **Kilnmark**: an on-prem engine that finds and profiles every data asset, flags sensitive data, scores how far each asset can be trusted, and then acts on it by archiving cold data, quarantining exposed files or running converted Kettle jobs.
>
> I'm looking for three organisations to try early releases from \[month\] and help shape it. It runs entirely on your own servers, with nothing leaving your network. In return you'd get free use during the programme, a discounted first year, and a short report on what Kilnmark finds in your estate.
>
> &#91;Personal line: why you thought of them, e.g. a conversation you had, or a challenge they've mentioned publicly.\]
>
> Would you be open to a 20-minute call in the next few weeks?
>
> Thanks, Jp
