# Branding and appearance

This guide is for the operator who runs a Kilnmark server, and for anyone who wants to change how
Kilnmark looks for them.

There are two parts:

- **Appearance**, chosen by each person for themselves: theme, accent colour and density. Open
  **Settings**, then **Appearance**. Every signed-in user can do this; the choice is kept in that
  browser only. The theme can also be switched from the top bar.
- **Organisation branding**, set by the operator for everyone: the product name and logo shown in
  the top bar and on the sign-in page, and logos or colours for connectors. These come from the
  **branding folder** on the server, described below.

## Appearance

| Setting | Choices |
| --- | --- |
| Theme | **System** (light or dark, following the device), **Light**, **Dark**, **High contrast** (black on white, or white on black when the device is dark) |
| Accent colour | Orange (the default), Blue, Green, Purple, Pink, Teal. Each has its own shade in every theme, checked for contrast |
| Density | **Comfortable** (the default) or **Compact**, which fits more rows on screen and keeps every control at least 24 pixels high |

Kilnmark also follows the device's **reduced motion** setting, and Windows **contrast themes**
(forced colours), where the system's colours replace Kilnmark's.

## Connector tiles

Kilnmark ships no vendor logos. Each kind of source has a **tile**:

- products show two or three letters on a tinted square, for example **Pg** for PostgreSQL and
  **SQL** for SQL Server. The tint is chosen from the connector's id, so it is always the same,
  and the letters meet WCAG AA contrast in every theme;
- generic connectors, such as a local or network folder, show an icon instead.

The tile appears in the **Add connection** gallery, the sources list, a source's page and the
browse tree. The connector's name is always written beside it.

If your organisation is licensed to use a product's logo, the branding folder can replace the
tile with it.

## The branding folder

The server reads the folder named by `kilnmark.branding.dir` (environment variable
`KILNMARK_BRANDING_DIR`), by default `/etc/kilnmark/branding`. If the folder or its manifest is
missing, Kilnmark uses its own name and the letter tiles. The server only reads the folder, so
mount it read-only.

With Compose, uncomment the branding line in `compose.yaml` and put your files in
`./branding`:

```yaml
    volumes:
      - ./branding:/etc/kilnmark/branding:ro,Z
```

The folder holds `manifest.json` and the images it names:

```
branding/
  manifest.json
  acme-logo.svg
  postgresql.svg
  sqlserver.png
```

### manifest.json

```json
{
  "product": {
    "name": "Acme Data Hub",
    "logo": "acme-logo.svg"
  },
  "connectors": {
    "postgresql": { "file": "postgresql.svg", "label": "PostgreSQL" },
    "sqlserver": { "file": "sqlserver.png", "color": "#ffffff" },
    "s3": { "color": "#232f3e" },
    "kafka": { "file": "kafka.svg" }
  }
}
```

Every field is optional.

| Field | Meaning |
| --- | --- |
| `product.name` | Replaces "Kilnmark" in the top bar and on the sign-in page. At most 60 characters |
| `product.logo` | An image shown before the name, at 28 pixels high |
| `connectors` | Keyed by connector id: `postgresql`, `sqlserver`, `files`, `s3`, or a planned connector's id (below) |
| `connectors.<id>.file` | An image that replaces the tile |
| `connectors.<id>.color` | A brand colour as `#rrggbb`. With a file, the background behind the image; without one, the tile's colour, with black or white letters, whichever contrasts more |
| `connectors.<id>.label` | The logo's text alternative, used where the tile is the only label. At most 60 characters |

Planned connector ids, for when they arrive: `mysql`, `oracle`, `jdbc`, `db2`, `sqlite`,
`snowflake`, `redshift`, `bigquery`, `databricks`, `hana`, `teradata`, `mongodb`, `elasticsearch`,
`cassandra`, `redis`, `neo4j`, `cosmos-dynamo`, `smb`, `sftp`, `azure-blob`, `gcs`, `http`,
`hdfs`, `kafka`, `mqtt`, `amqp`, `jms`, `cdc`, `cloud-streams`, `opcua`, `rest`, `graphql`,
`odata`, `imap`, `sharepoint`, `google-sheets`, `salesforce`, `servicenow`, `sap`, `iceberg`,
`delta`. They are also in the `id` field of `GET /api/v1/connectors/catalogue`.

### Images

- **Formats:** SVG, PNG or WebP. The content must match the extension.
- **Size:** at most 256 KB each (`kilnmark.branding.max-file-kb`), 8 MB together.
- **Names:** plain file names in the folder itself, made of letters, digits, `.`, `_` and `-`.
  No sub-folders and no `..`. Symbolic links are not followed.
- **Shape:** tiles are square; a square image with a little padding looks best. The product logo
  is shown 28 pixels high and up to 160 pixels wide.
- **SVG:** files with a `DOCTYPE`, entities or `<script>` are refused. Every SVG is shown as an
  image, never placed in the page, so nothing in it can run.

Kilnmark checks the manifest whenever it, or a file it names, changes, so there is no need to
restart. An entry that is invalid (a missing file, a wrong type, too large, a bad colour) is left
out and the server log says why, in a warning starting `Branding folder`. That connector keeps its
letter tile.

### Who can see it

`GET /api/v1/branding` and the images at `/api/v1/branding/files/<name>` need no sign-in: the
sign-in page shows the product name and logo before anyone has signed in, and browsers do not send
the API token when showing images. Only files listed in the manifest are served, so do not list
anything you would not show on the sign-in page.

Images are sent with their checked content type, `X-Content-Type-Options: nosniff` and
`Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`, so even an SVG
opened directly in a tab cannot run scripts or load anything.

### Licensing

Only use logos your organisation is licensed or permitted to use. Kilnmark does not check this;
it only displays what the branding folder holds.
