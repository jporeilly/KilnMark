# 0008 Licence model: Business Source License 1.1

Status: Accepted (24 October 2026). The parameters below are provisional until the first public
release.

## Decision

Kilnmark is **source-available under the Business Source License 1.1** (see
[LICENSE](../../LICENSE)), sold as an annual production subscription that includes support and
updates.

| Parameter | Value (provisional) |
| --- | --- |
| Free production use (Additional Use Grant) | Up to 3 data sources per organization, not offered to others as a hosted, managed or embedded service |
| Change date | Four years after each version is first published |
| Change licence | Apache License 2.0 |

Anyone may read, build, modify and use Kilnmark for evaluation, development and testing. Beyond
the free tier, production use needs a commercial licence. Each version becomes Apache 2.0 on its
change date.

## Why

- **Trust is the product.** On-prem buyers in regulated sectors want to inspect what touches
  their data. Readable code passes security reviews that a closed binary struggles with.
- **Continuity.** A one-person vendor is a risk buyers will raise. Readable code, and every
  version becoming Apache 2.0, answers it better than source-code escrow.
- **Protection.** It stops a larger company reselling Kilnmark as its own, which Apache 2.0
  would allow, and Apache-licensed catalogs are already free.
- **Familiar.** Kettle itself moved to the Business Source License from 10.3, so target buyers
  know the model.
- **Keeps the IP valuable** for licence income or a later sale.

## Alternatives considered

- **Proprietary:** simplest, but a hard sell for a solo vendor on-prem, and it gives up the trust
  argument.
- **Open core** (Apache core, paid extras): good for adoption, but anything in the core can never
  be taken back, and every feature becomes a decision about what to give away.
- **Apache 2.0 with paid support:** right only if the aim were legacy and consulting rather than
  licence income.

## Consequences

- **Third-party code keeps its licence.** Files derived from Apache-licensed Kettle 10.2 keep
  their Apache headers and are listed in [NOTICE](../../NOTICE) and the
  [provenance log](../legal/provenance.md). Dependencies stay on permissive licences
  ([ADR 0007](0007-dependency-policy.md)).
- **Contributions need an agreement.** Anyone else's contribution needs a signed contributor
  licence agreement first, so the work can always be relicensed (see
  [CONTRIBUTING.md](../../CONTRIBUTING.md)).
- **Nothing is published yet.** The repository is private, so no version has a change date
  running. Confirm the free-tier limit and the change date, and have the parameters checked by a
  lawyer, before the first public release.
- **BSL is not an open-source licence.** Some organizations' policies treat it differently from
  Apache or MIT; the free tier and the change date are the answer to that.
