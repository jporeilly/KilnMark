# 0055 Downloading the files a run wrote

Status: Accepted (9 November 2026)

## Context

A pipeline that writes CSV or Parquet to a folder source puts the file in that source's folder on
the Kilnmark server. On an installed release that is Kilnmark's own files volume inside its
container (`/srv/files`); on Windows it is inside the Podman machine. A person who ran the pipeline
cannot reach the file. They need to download it from the run, the same way on Windows and Linux.

A download hands out data: every value of every column, unmasked, since a file cannot be masked.
So it must follow the rule for seeing values (ADR 0027, 0038), must reach no file but the one the run
wrote, and must be in the audit log (ADR 0029).

## Decision

1. **The client names a run and a file of it, never a path.** `GET
   /api/v1/pipeline-runs/{id}/files` lists the run's writes to sources of files by `index`, their
   place in the run's own record (`result.written`). `GET .../files/{index}` sends one. No path,
   folder or name is read from the request.

2. **The run records the file it wrote.** The file writer reports, once the file is in place, its
   real path, size and time on its `SCHEMA` event; the run keeps them in its record
   (`file: {location, bytes, modified}`, never shown by the API). This is the most reliable
   source: the run's events are kept only in memory, the catalogue's lineage names assets, not
   files, and resolving the write's path again later finds whatever is there now.

3. **The file is found as the write found its place, and only if it is still the run's.** The
   source's folder is opened as for any read, checked against the server's allowed roots; the
   recorded target is checked again as a write's target is (`.csv` or `.parquet`, no `..`, no
   absolute path, no hidden name); the file must then be a regular file reached through no link at
   all, spelt as on disk, at the recorded place, with the recorded size and time, and is checked
   once more as it is opened. A file deleted is refused as deleted (410); one written again,
   replaced, or reached another way (a moved folder, a link) as changed or unreachable (409). Runs
   from before this recorded nothing: their file must have changed within the run's own time, in a
   folder the server may write (so never the read-only share), and is sent with a note that it may
   have changed. Only folders on this server are served; a file in a bucket is read from there.

4. **Who may have it is decided by `Sights`, failing closed.** `Sights.wholeFile` is the quality
   views' rule (`Sights.of`, asked to unmask, since a file holds every value): values only for roles
   that may see data (stewards and above), and a file holding a column marked sensitive, a mark the
   run carried included, only for a designer with the unmask grant, approved when approval is on.
   Admins see it masked everywhere, so they cannot download it. A file is treated as sensitive when
   its columns are not in the catalogue (the run did not catalogue it), and when an asset the run
   read for it has had a column marked since the run started, which the run could not carry.
   Anyone who may see the run sees the list, with the reason a file cannot be downloaded.

5. **Every download and every refusal is an audit event**: `file.downloaded` and
   `file.download_refused`, on `pipeline-run/<id>`, with the node, source, path in the source and
   size, and why when refused; never the file's contents. Refusals, which any viewer can cause, are
   kept at most 30 a minute for each caller, as `api.refused` is; the JSON line has every one.

6. **GET with the caller's own sign-in, not a signed link.** The download is a plain GET, signed in
   as every other request is: by an API token's header, or by the provider session's cookie. GET
   changes nothing (it only records the audit event), so the session rules (`SessionRequests`) let
   a browser follow a plain link with the cookie, and the browser streams the file to disk. A page
   signed in with a token cannot put the token on a link, so it fetches the file with the header
   and saves it.

   A short-lived signed link was considered and not chosen. It would put a credential for data in a
   URL, where browser history, proxies and logs keep it; it would need its own store or signing key
   shared by every api process; and between making the link and following it, a revoked token or a
   mark set meanwhile would not be seen. With the caller's own sign-in, every check runs at the
   moment the file is sent.

7. **Streamed, never held.** The file is sent 64 KiB at a time, exactly its checked size, as an
   attachment named by its own base name kept to safe characters, typed from its extension
   (`text/csv`, `application/vnd.apache.parquet`, otherwise `application/octet-stream`), with
   `X-Content-Type-Options: nosniff`, `Cache-Control: no-store` and
   `Cross-Origin-Resource-Policy: same-origin`.

## Consequences

- People get the files their runs wrote without reaching into the container or the Podman machine.
- A file with sensitive or unknown columns is for designers with the unmask grant only; others
  see why. A steward who needs such a file asks one.
- A run's file can be downloaded only while it is still the run's: a later run that replaces it
  makes the earlier run's download refuse, pointing at the later run.
- Someone with write access to the source's folder on the server itself could swap a checked
  folder for a link between the check and the open; the final check as the file is opened (no
  link, same size, time and file) narrows that, and the folders an administrator allows should be
  writable only by Kilnmark and people trusted with what it writes, as for writes.
- A deployment that splits api and worker processes must give the api processes the same files
  volume, or downloads say the file cannot be reached.
- A page signed in with an API token holds the file in the browser's memory before saving it; for
  very large files, the guide shows the API with the token.
