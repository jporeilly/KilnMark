# 0035 S3 hosts for the allowed-hosts list, as the AWS SDK reaches them

Status: Accepted (3 November 2026)

## Context

`kilnmark.sources.allowed-hosts` limits the hosts sources may reach. Each connector says which hosts
a source names (`Connector.hosts`), and the server checks them before saving the source and before
every connection. For S3 sources that answer was worked out by hand: the endpoint's host, or
`s3.<region>.amazonaws.com` without one. The reviews of ADR 0034 found that the AWS SDK sends
requests elsewhere:

- with the bucket in the host name (`mybucket.storage.example.com`) unless `path_style` is on, so a
  bucket name a steward picks reached any one-label subdomain of an allowed host;
- on AWS, to FIPS and dual-stack hosts, other partitions (`amazonaws.com.cn`, `c2s.ic.gov`), access
  points (an access-point ARN as the bucket) and directory buckets (`s3express-...`).

The list checked one host while requests went to another.

## Decision

1. **The SDK says where requests go.** `S3Connector.hosts` builds the same client a read uses
   (`S3FileStore.builder`, one place for both), starts a listing of the bucket, and stops it in an
   interceptor just before it is sent, keeping the request's host. Nothing leaves the server, and the
   request is signed with throwaway credentials, so neither the source's nor the server's own are
   looked up. Whatever the SDK's endpoint rules choose, the list sees.
2. **Settings the SDK refuses before sending** (an invalid region, for example) give no host, and the
   source is refused rather than guessed at.
3. **Not chosen:** forcing path style whenever there is an endpoint (it breaks stores that need
   virtual-hosted addressing, and does nothing for AWS's own hosts); reporting `bucket.host` beside
   `host` by hand (it repeats the SDK's rules and falls behind them, as the earlier code did);
   refusing access-point ARNs (they are a normal AWS feature, and now reported as they are reached).

## Consequences

- An administrator's list must name the hosts requests really reach. A list that named exactly
  `storage.example.com` or `s3.eu-west-2.amazonaws.com` now refuses sources that reach
  `mybucket.storage.example.com` or `mybucket.s3.eu-west-2.amazonaws.com`: add those hosts, a wildcard
  such as `*.storage.example.com` or `*.amazonaws.com`, or turn `path_style` on for a store that
  allows it. The refusal names the host to add.
- us-east-1 without an endpoint reaches AWS's global host, `mybucket.s3.amazonaws.com`.
- Each check builds a client, which costs milliseconds; it happens only when an allow-list is set,
  once per check, never per row.
