# Drivers the customer installs

Some databases' JDBC drivers cannot ship with Kilnmark because of their licences: Oracle's, under
the Oracle Free Use Terms and Conditions ([ADR 0007](../adr/0007-dependency-policy.md)). The
connector ships; its driver is installed by the customer. Generic JDBC, with drivers customers
bring for any database, will use the same folder.

## Declaring a driver

A connector that needs one returns it from `Connector.driver()` (in `kiln-core`):

```java
public static final Driver DRIVER = new Driver("com.oracle.database.jdbc:ojdbc11", "23.26.3.0.0",
    "764cc3f88454d1be117e155d01ed617467b984c9c68f13090dd6083799d2bb8c",
    "Oracle Free Use Terms and Conditions (FUTC)", "https://www.oracle.com/downloads/licenses/oracle-free-license.html",
    false, "oracle.jdbc.OracleDriver");
```

Coordinates, a version pinned at least two weeks old, the SHA-256 of Maven Central's jar (check
it against the `.sha256` Central publishes), the licence and its link, whether Kilnmark may ship
it (`false` here), and the `java.sql.Driver` class. The connector gets the installed drivers in
`withContext` (`ConnectorContext.drivers()`) and connects through `JdbcConnector.connectUrl`,
which uses the driver directly, never `DriverManager`. It uses only the standard JDBC interfaces:
no class of the driver is on Kilnmark's class path, at compile time or run time.

## The drivers folder

`DriverFolder` (in `kiln-core`) is the folder `kilnmark.drivers.dir` names: by default
`~/.kilnmark/drivers`, and `/var/lib/kilnmark/drivers` in the server's image, inside its data
volume (`keys` in both compose files), so a driver survives upgrades. One file per driver, named
as Maven Central names it (`ojdbc11-23.26.3.0.0.jar`). The server creates the folder at start
(`DriverFolder.prepare()`).

- Only the file a connector's driver names is read, and only if its SHA-256 is the pinned one.
  Any other file in the folder is ignored; a file of the right name with another checksum is
  refused, and the message says so.
- The verified bytes are copied to a private folder beside the drivers folder (`drivers.loaded`,
  owner-only permissions, emptied at every start) and loaded from that copy, so the file cannot be
  swapped between the check and the load. Nothing in the system's temporary folder is used, so no
  temporary-file cleaner can remove a loaded driver's jar.
- `drivers.loaded/empty` is an empty folder of the same kind, which the Oracle connector gives
  its driver as `oracle.net.tns_admin`: the driver would otherwise read `ojdbc.properties` (and
  `tnsnames.ora`) from a `TNS_ADMIN` the environment chooses.
- Each driver gets a `URLClassLoader` of its own whose parent is the platform class loader: it sees
  the JDK, never Kilnmark's classes or other drivers.
- A driver is looked up each time a connection is made, so one installed while the server runs is
  used at once; it is loaded once per checksum. Once loaded, it is used again without reading or
  hashing its file while the file's size and modification time are unchanged; a change in either
  reads and checks the file again. Changing the file never changes what is loaded: that is the
  verified private copy.

States, as `GET /api/v1/connectors` gives them in each connector's `driver`: `bundled` (it ships),
`ready`, `missing`, `mismatch` (another file of that name) and `unreadable`.

## Installing one

Settings › Drivers (admins only; `GET /api/v1/drivers`, `POST /api/v1/drivers/{id}/accept`):

1. The page lists each driver the server's connectors need, with the connectors, its licence and
   link, and its state.
2. **Online** (`kilnmark.drivers.download=true`, the default): the admin ticks that they accept
   the licence on the organisation's behalf and presses **Accept and download**. The acceptance is
   recorded (`driver_licence`: driver, version, who, when; kept from the first), and the server
   downloads that exact version from `kilnmark.drivers.maven-url` (Maven Central, or the
   organisation's mirror; never an address typed into the page), writes it beside the target under
   a temporary name, checks the checksum and size (at most 200 MB), and only then moves it into
   place. Anything else is deleted, and the page says what went wrong and where the file could be
   put instead.
3. **Offline** (`kilnmark.drivers.download=false`): the page names the file, its Maven Central
   address, its checksum and the folder to put it in. Copying it there is enough; no restart.

`POST /api/v1/drivers/{id}/accept` answers 409 when this server does not download drivers and 502
when the download did not install the driver (`DriverService.DownloadFailed.Reason`), each with
what to do.

**Upgrades that change the pinned version.** The file name holds the version, so after an upgrade
that pins a newer driver the installed file is no longer the one wanted: the driver shows as
missing, its connectors cannot connect, and an admin accepts the (new version's) licence again,
or puts the new file in the folder. The server logs a warning at start naming the file left
behind and the connectors affected; release notes say when a driver's version changes.

In the gallery, a connector whose driver is not installed says **Needs its driver**, with a link
to Settings › Drivers; its connection panel says the same. Connecting before the driver is there
fails with a message saying who installs it, without the server's paths.

## Keeping it out of the release

The driver is a test-only dependency (`testRuntimeOnly` in `kiln-plugins`): the integration tests
copy it from the test class path into a drivers folder, so they connect as a customer's server
does, and they check the pinned checksum. `./gradlew release` runs `checkNoCustomerDrivers`, which
fails if a jar named like one, or holding its classes, is in the release folder.
