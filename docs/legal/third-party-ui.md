# Third-party software in the web UI

The server's libraries are checked and listed by the build (`THIRD-PARTY-NOTICES.md`,
[ADR 0007](../adr/0007-dependency-policy.md)). The web UI's are npm packages, pinned in
`ui/package-lock.json`. `npm run build` writes the licence text of every runtime package the UI
bundle is built from (type-only `@types/*` packages left out) to `dist/ui/third-party-notices.txt`.
The server serves it at `/third-party-notices.txt`, and `./gradlew release` copies it to the top of
the release folder as `THIRD-PARTY-NOTICES-UI.txt`, next to the server's `THIRD-PARTY-NOTICES.md`.
The licences are MIT, plus ISC and BSD-3-Clause for the d3 packages that @xyflow/react brings, and
the SIL Open Font License 1.1 for the two typefaces, all permissive. (`config/allowed-licenses.json` is checked against the server's libraries only.)

Direct dependencies:

| Package | Version | Licence | Used for |
| --- | --- | --- | --- |
| react, react-dom | 19.3 | MIT | The UI |
| @xyflow/react | 12.12 | MIT | Schema diagrams |
| @dagrejs/dagre | 3.1 | MIT | Laying out schema diagrams |
| @tabler/icons-react (with @tabler/icons) | 3.48.0, pinned exactly | MIT, Copyright (c) 2020-2026 Paweł Kuna | Every icon: navigation, categories, connector families, file formats, the browse tree, the theme switch |
| @fontsource/inter | 5.2.8, pinned exactly | SIL Open Font License 1.1, Copyright 2016 The Inter Project Authors | Body text (weights 400, 500 and 600) |
| @fontsource/manrope | 5.2.8, pinned exactly | SIL Open Font License 1.1, Copyright 2019 The Manrope Project Authors | Headings and numbers (weights 700 and 800) |

## Fonts

- Inter and Manrope are bundled with the UI from the @fontsource packages above, so Kilnmark
  looks the same without internet access. Nothing is loaded from Google Fonts or any other site.
- Only the Latin subset of the weights in use is imported, as woff2 only (`ui/src/fonts.css`,
  one `@font-face` per weight naming the package's file); Vite copies those five files into
  `dist/ui/assets`. The full licence text of both fonts is
  in `third-party-notices.txt` with the other packages.
- Code and identifiers use the system's monospace font.

## Icons and logos

- Icons come only from Tabler Icons, imported one by one so only the icons used are bundled
  (about 60), and served by Kilnmark itself, so they work offline. The mapping from names to
  icons is in `ui/src/components/icons.tsx`.
- Kilnmark ships **no vendor logos or brand icons**. Products are shown as letter tiles drawn by
  Kilnmark (`ui/src/components/ConnectorTile.tsx`). Tabler's brand icons must not be used.
- Operators can add logos they are licensed to use through the branding folder
  ([branding guide](../guide/branding.md)); those files stay on the customer's server and are not
  part of Kilnmark.
