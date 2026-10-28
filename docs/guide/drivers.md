# Installing database drivers

Kilnmark connects to most databases with drivers it ships. A few databases' drivers have licences
that do not allow that, so you install them yourself, once, for the whole server. Today that is
**Oracle**: its driver is Oracle's own, under the Oracle Free Use Terms and Conditions.

Until its driver is installed, such a connector shows **Needs its driver** when you add a
connection, and a connection test says that the driver is missing.

## Install a driver (admins)

Open **Settings**, **Drivers**. Each driver your server's connectors need is listed with the
connectors that use it, its licence and whether it is installed.

1. Read the licence (the link opens it).
2. Tick **I have read the licence and accept it on my organisation's behalf**.
3. Press **Accept and download**.

Kilnmark records who accepted the licence and when, downloads the exact version it was tested
with, and checks it before using it. The page then says **Installed**. Nothing needs restarting:
test your connection again.

## A server without internet access

If your server cannot reach Maven Central, your administrator may have turned downloads off
(`kilnmark.drivers.download=false`), or point Kilnmark at your organisation's Maven mirror
(`kilnmark.drivers.maven-url`). Without downloads, **Settings**, **Drivers** shows instead:

- the file to get, with its address, for example `ojdbc11-23.26.3.0.0.jar`;
- its checksum (SHA-256); and
- the drivers folder on the server.

Download the file on a machine that can, after accepting its licence, and copy it, unchanged, into
the drivers folder. With the release's `compose.yaml`, the folder is `/var/lib/kilnmark/drivers`
in the `keys` volume, for example:

```
podman compose cp ojdbc11-23.26.3.0.0.jar kilnmark:/var/lib/kilnmark/drivers/
```

(`docker compose cp` with Docker.) Running the server without containers, the folder is
`kilnmark.drivers.dir`, by default `~/.kilnmark/drivers` for the user running Kilnmark.

## After an upgrade

When an upgrade of Kilnmark moves to a newer version of a driver, the installed file is no longer
used: the driver shows as **Missing** again, and sources needing it cannot connect until an admin
accepts its licence again under **Settings**, **Drivers** (or copies the new file into the drivers
folder). The release notes say when this happens, and the server's log names the old file at
start. The old file can then be deleted.

## If it says Wrong file

Kilnmark only uses the exact file it was tested with. A file of the right name with other
contents (another version renamed, or a damaged download) is ignored and shows **Wrong file**.
Delete it and install the driver again.

## Databases without a connector of their own

For a database Kilnmark has no connector for, such as **Db2**, **Teradata**, **Snowflake**,
**Sybase** or **Informix**, you bring its JDBC driver from the database's vendor and connect
through **Generic JDBC**.

### Register the driver (admins)

Open **Settings**, **JDBC drivers**.

1. Put the driver's jar in the drivers folder (above), or use **Upload to the drivers folder**
   (up to 10 MB unless your administrator raised the server's upload limit; larger jars are copied
   in by hand). A file of the same name is never replaced.
2. Under **Register a driver**, choose the jar, then give the driver a **name** (what sources
   choose, such as `db2`), its **driver class** and how its **URLs start**. **Fill in for a known
   database** suggests both for common drivers; check them against the driver's documentation.
3. Tick that the jar comes from the database's vendor or another source you trust, and press
   **Register driver**.

Kilnmark loads the class to check it, and records the jar's checksum, who registered it and when.
From then on the driver is used only while the jar is unchanged. If someone replaces the file, the
driver shows **Jar changed** and its sources stop connecting, saying why, until the original file is
back or the registration is removed and the new file registered. Nothing is ever downloaded for
these drivers.

A registered driver runs inside the Kilnmark server, so register only drivers you trust. The page's
**History** keeps every registration and removal. A driver that sources still use cannot be
removed; point them at another driver first.

### Connect a source (admins)

In **Sources**, **Add a connection**, choose **Generic JDBC**:

- **JDBC driver**: one of the registered drivers.
- **JDBC URL**: as the driver's documentation gives it, starting as the driver's URLs do, for
  example `jdbc:db2://db.example.com:50000/SALES`. Never put the password in it.
- **User name** and **password**: the password is stored encrypted, as for every source.
- **Driver properties** (optional): further settings as `name=value`, separated by semicolons, such
  as `sslConnection=true; currentSchema=SALES`. Properties that read files on the server, load
  classes, change time limits or name another host are refused.
- **Schemas**: which to scan; leave empty for all. Databases that have only catalogues list those.
- **Read only**: on by default. Kilnmark then asks the driver for read-only connections, and
  pipelines cannot write to the source.

Only admins can add a Generic JDBC source or change these settings, because the URL goes to a
driver Kilnmark cannot vet. Stewards can still test, scan and preview it.

Kilnmark reads the tables' columns with their types: whole numbers, decimals (never rounded),
dates, times and timestamps keep their type; a type Kilnmark does not know is read as text, and its
column says so. Quality rules for these sources are checked on the rows read.
