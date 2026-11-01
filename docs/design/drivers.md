# Drivers the customer installs

Some databases' JDBC drivers cannot ship with Kilnmark because of their licences: Oracle's, under
the Oracle Free Use Terms and Conditions ([ADR 0007](../adr/0007-dependency-policy.md)). The
connector ships; its driver is installed by the customer. Generic JDBC, with drivers customers
bring for any database, uses the same folder: see [Registered drivers](#registered-drivers).

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

## Registered drivers

The generic JDBC connector (kind `jdbc`) reads databases Kilnmark has no connector of its own for:
Db2, Teradata, Snowflake, Sybase, Informix, an H2 server and the like. Their drivers cannot be pinned in
advance, since Kilnmark does not know which the customer will bring or which version. So instead
of Kilnmark pinning the jar, **an admin vouches for it** ([ADR 0025](../adr/0025-registered-jdbc-drivers.md)).

**Registering** (Settings › JDBC drivers, admins only; `GET`, `POST /api/v1/drivers/registered`,
`GET /api/v1/drivers/files`, `POST /api/v1/drivers/files/{fileName}`):

1. The jar goes in the drivers folder: copied there, or uploaded through the page
   (`application/octet-stream`, up to the server's request limit, `quarkus.http.limits.max-body-size`,
   10 MB by default; the page shows the limit, and a larger jar is copied into the folder by hand).
   An upload is written under a temporary name and linked into place, so it never replaces a file.
2. The admin names the driver (`db2`: lower-case letters, digits, `-`, `_`), picks the jar, gives
   its `java.sql.Driver` class, how its URLs start (`jdbc:db2://`), and **the driver properties
   sources may set** (`currentSchema, sslConnection`): an allow-list. Names that hold secrets (pass,
   pwd, secret, token, key, credential), the user name, and names that point at the server's files,
   folders, classes or key stores (ending in file, path, dir, directory, factory, class, classname;
   trust and key stores; `javax.*`; `tns_admin`) can never be allowed.
3. Kilnmark reads the jar, loads the class from a private copy in a class loader of its own (a jar
   without that driver is refused at once), and records the jar's **SHA-256** with who registered
   it and when (`driver_registration`, migration V18). The class is loaded without
   running its initialisers until it is known to implement `java.sql.Driver`. A pinned driver's jar
   cannot be registered, by its name or under another (its checksum is compared). Jars are read
   without following links, at most 200 MB.

**Using it** is the pinned path with the recorded checksum in place of a pinned one
(`DriverFolder.driver(RegisteredDriver)`): the jar is read and hashed, used only while its
SHA-256 is the registered one, copied to `drivers.loaded`, loaded in its own `URLClassLoader` whose
parent is the platform class loader, and used directly, never through `DriverManager`. A jar that
has changed or gone shows as **Jar changed** or **Jar missing**, and its sources stop connecting
with a message saying why, until the file is put back or the registration is removed and the new
file registered.

**Removing** a registration is admin-only (`DELETE /api/v1/drivers/registered/{name}`) and refused
while sources use it; the jar stays in the folder. Every registration and removal is kept for good
in `driver_registration_log` (`GET /api/v1/drivers/registered/history`), and logged. The log is
append-only: a trigger refuses `UPDATE`, `DELETE` and `TRUNCATE`.

**Nothing is downloaded** for a registered driver: there is no address to download from that
Kilnmark could vouch for.

### Threat model

- *A swapped jar.* Someone with write access to the drivers folder replaces a registered jar: the
  checksum no longer matches and it is not loaded; a copy already loaded is the verified private
  copy, which the folder's file cannot change.
- *A malicious or buggy driver.* A registered driver runs in the server's JVM with the server's
  rights. Its own class loader keeps it from Kilnmark's classes and other drivers, but a class
  loader is not a security boundary: registering is trusting the jar, which is why only admins
  register, the page asks them to confirm the jar's origin, and the audit trail names them.
- *A hostile URL.* A JDBC URL and driver properties go to the driver, and drivers have features
  that read files, run scripts or reach other hosts (H2's `INIT=RUNSCRIPT`, MySQL's
  `allowLoadLocalInfile`, Derby's `restoreFrom`, Db2's `traceFile`, failover partners). A deny-list
  cannot know every driver's, so the generic connector takes an **allow-list**: every setting in the
  URL, however the driver writes it (`;`, `?`, `&`, Db2's `:key=` after the database, Teradata's
  `/key=...,key=...`, `(key=value)` groups as in MySQL's `address=(...)` and Oracle's descriptors), and
  every property must be one the admin allowed at registration. No allow-list may hold a property
  that loads code, reads the server's files or signs in as the server (`RegisteredDriver.readsServerFiles`:
  files, paths, folders, factories, classes, key and trust stores, interceptors, verifiers, callbacks,
  providers, strategies, locations, MySQL's `autoDeserialize` and local-file loading,
  `integratedSecurity`, `authenticationScheme`). A URL with an `=` that belongs to no
  readable setting, or with quotes, braces, back-slashes, spaces or percent-encoding, is refused.
  Secrets and the user name are refused whatever the list, as settings are stored and shown as plain
  text, and messages never quote the URL. Only admins may set up a generic JDBC source, change its
  connector settings or check an unsaved one (`Connector.adminOnly()`); stewards still test, scan and
  preview saved ones.
- *A database inside the server.* A driver that opens its database in the server's own process
  (SQLite, DuckDB, H2 or HSQLDB files and memory, embedded Derby, Access files through UCanAccess,
  CSV files, Calcite, embedded, local or native Firebird, Oracle OCI, Drill with `zk=local`, Ignite
  started from `cfg:`, embedded Hive) lets the source's SQL read or write the server's files, or run code there,
  with no login of anyone's own (ADR 0027). Such URLs are refused for admins too, whatever the
  property allow-list: H2 only as `tcp://` or `ssl://` and never with `INIT`, in the URL or as a
  driver property in any case; HSQLDB only as `hsql://`, `hsqls://`, `http://` or `https://`; Derby
  only with `//host`; Hive only with a host. The names are looked for anywhere in the URL, so a
  wrapping driver is caught too, and refused with a message saying why.
- *Only URLs that name a network host.* Beyond those names, the check is an allow-list by shape, as
  a driver Kilnmark does not know could open anything in the process: the URL must be
  `jdbc:<subprotocols>://host[:port][,host[:port]...]` (or `@//host` as Oracle's thin URLs have
  it), with every host a host name or a bracketed IPv6 address and every port digits. A URL with no
  `//` (`jdbc:x:/path`, `jdbc:x:mem:db`), an empty authority (`jdbc:x:///path`) or one Kilnmark
  cannot read as hosts (`//C:/path`, `//~/db`, `//host:`) is refused, whether or not an allow-list
  of hosts is set. The one exception is jConnect's `jdbc:sybase:Tds:host:port`, read as naming its
  host. Drivers whose URLs name the host otherwise (Drill's `zk=host:2181`, for example) cannot be
  used through Generic JDBC.
- *Reaching the outside.* Nothing is downloaded. The hosts a source would reach are the URL's and
  those its settings name (failover partners, `*servername`, `alternate*` and the like, and any value
  that is itself a URL), all checked against `kilnmark.sources.allowed-hosts`; with an allow-list
  of hosts, a URL whose host or settings cannot be read is refused.

The test driver is H2 (MPL 2.0 or EPL 1.0, not GPL), a `testRuntimeOnly` dependency of
`kiln-plugins` that the tests copy into a drivers folder and register through this path; they reach
their in-memory databases through an H2 server on the test machine, as a customer's would be.
`checkNoCustomerDrivers` also fails a release holding H2's jar or classes.
