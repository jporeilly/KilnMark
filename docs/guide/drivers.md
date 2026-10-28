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
