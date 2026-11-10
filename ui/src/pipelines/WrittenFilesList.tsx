import { useCallback, useEffect, useState } from "react";
import { IconDownload } from "@tabler/icons-react";
import { pipelinesApi, plural, signedInWithToken, type PipelineWrittenFile } from "../api";
import { Button, StatusChip, type Tone } from "../components/ui";
import { formatBytes } from "./RunStatus";

const STATE: Record<PipelineWrittenFile["state"], { word: string; tone: Tone }> = {
  ready: { word: "As the run wrote it", tone: "good" },
  unrecorded: { word: "May have changed", tone: "warn" },
  changed: { word: "Changed since", tone: "warn" },
  deleted: { word: "Deleted", tone: "bad" },
  unavailable: { word: "Cannot be reached", tone: "neutral" },
};

/** The file's own name, for saving what a token sign-in fetched (the server names a link's download itself). */
const baseName = (path: string) => path.slice(path.lastIndexOf("/") + 1) || "download";

/** Saves a fetched file through a link the browser follows at once. */
function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  follow(url, name);
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Follows a link as a download: a provider sign-in's cookie goes with it, so the browser streams the file to disk. */
function follow(url: string, name: string) {
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
}

/**
 * The files a pipeline run wrote to sources of files, each with a Download button (ADR 0055). The
 * button is off, with the reason beside it, when the caller may not have the file (it holds
 * sensitive columns, or they see no values) or it is no longer the run's (deleted or changed). A
 * click asks the server again first, so a file deleted since the page loaded says so here.
 */
export function WrittenFilesList({ runId, finished }: { runId: number; finished: boolean }) {
  const [files, setFiles] = useState<PipelineWrittenFile[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const load = useCallback(() => {
    pipelinesApi
      .files(runId)
      .then((l) => setFiles(l.files))
      .catch((e: Error) => setError(e.message));
  }, [runId]);

  useEffect(() => {
    if (finished) load();
  }, [finished, load]);

  const download = async (f: PipelineWrittenFile) => {
    setBusy(f.index);
    setError(null);
    try {
      // The file may have been deleted, replaced or marked sensitive since the list was read.
      const now = await pipelinesApi.files(runId);
      setFiles(now.files);
      const still = now.files.find((x) => x.index === f.index);
      if (!still?.downloadable) {
        setError(`${f.path}: ${still?.reason ?? "it can no longer be downloaded."}`);
        return;
      }
      if (signedInWithToken()) {
        saveBlob(await pipelinesApi.fileBlob(runId, f.index), baseName(f.path));
      } else {
        follow(pipelinesApi.fileUrl(runId, f.index), baseName(f.path));
      }
    } catch (e) {
      setError(`${f.path}: ${(e as Error).message}`);
    } finally {
      setBusy(null);
    }
  };

  if (!finished || files == null || files.length === 0) {
    return error ? (
      <p role="alert" className="error">
        {error}
      </p>
    ) : null;
  }
  return (
    <>
      <h3>Files to download</h3>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <ul className="pl-files">
        {files.map((f) => {
          const why = `file-why-${runId}-${f.index}`;
          const st = STATE[f.state] ?? { word: f.state, tone: "neutral" as Tone };
          return (
            <li key={f.index} className="pl-file">
              <span className="pl-file-what">
                <span className="mono">{f.path}</span> in {f.source}, by node <span className="mono">{f.node}</span>
                <span className="small muted">
                  {" "}
                  {f.format.toUpperCase()}, {f.bytes != null ? formatBytes(f.bytes) : "size not recorded"}
                  {f.rows != null ? `, ${plural(f.rows, "row")}` : ""}
                </span>{" "}
                <StatusChip tone={st.tone}>{st.word}</StatusChip>
              </span>
              <Button
                size="sm"
                icon={<IconDownload size={16} aria-hidden="true" />}
                onClick={() => download(f)}
                disabled={!f.downloadable || busy != null}
                aria-describedby={f.reason || f.note ? why : undefined}
                aria-label={`Download ${f.path}`}
              >
                Download
              </Button>
              {(f.reason || f.note) && (
                <span id={why} className="small muted pl-file-why">
                  {f.reason ?? f.note}
                </span>
              )}
            </li>
          );
        })}
      </ul>
      <p className="small muted">
        Each file comes from the folder its source reads on the Kilnmark server, so this works the same wherever
        Kilnmark runs. Every download is in the audit log.
      </p>
    </>
  );
}
