# 0025 Registered JDBC drivers: the admin vouches, Kilnmark checks the checksum

Status: Accepted (28 October 2026)

## Decision

The generic JDBC connector uses drivers the customer brings, which Kilnmark cannot pin in advance.
An **admin registers** each one: picks or uploads a jar in the drivers folder, names its driver
class, its URL prefix and the driver properties sources may set (an allow-list), and Kilnmark records the jar's SHA-256, who and when. The driver then loads
only while the jar has that checksum, isolated exactly like a pinned driver (a verified private
copy, its own class loader over the platform loader, never `DriverManager`). Removing a
registration is admin-only and refused while sources use it. Every registration and removal is
kept in an audit log. Nothing is downloaded for a registered driver. Only admins set up or change
generic JDBC sources. The pinned path ([ADR 0007](0007-dependency-policy.md),
[drivers.md](../design/drivers.md)) is unchanged.

## Why

Pinning needs a known jar. For drivers we cannot know, the next best guarantee is that the jar
used is the one a named admin chose, and that nobody can change it afterwards unnoticed. Running a
driver is running its code in the server, so the decision belongs to an admin, not to whoever adds
a source; and since a JDBC URL can switch on driver features Kilnmark cannot vet, so does the URL.

## Threat model (short)

A swapped jar fails the checksum. A malicious driver is not contained by a class loader: trust is
the admin's, recorded in the append-only audit log. A hostile URL or property is limited by the
allow-list (every setting in the URL, in any of the syntaxes drivers use, must be on it; a URL that
cannot be read is refused), by refusing secrets and server files whatever the list, by reading
host-naming settings into the allowed-hosts check, and by keeping generic sources admin-only. Nothing is fetched from the
network on the driver's behalf.

## Consequences

- `driver_registration` and `driver_registration_log` (V16); Settings › JDBC drivers.
- Writing through generic JDBC is `append` and `create` only: `replace` and `upsert` need SQL no
  driver standardises safely, and are refused with a message.
- Tests register H2 (MPL 2.0 / EPL 1.0, test-only); `checkNoCustomerDrivers` keeps it out of the
  release.
