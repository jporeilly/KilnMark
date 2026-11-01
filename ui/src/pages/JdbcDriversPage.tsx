import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  api,
  formatTime,
  type DriverFolderListing,
  type DriverLogEntry,
  type RegisteredDriverItem,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Button, Card, EmptyState, PageHeader, StatusChip, type Tone } from "../components/ui";

const STATE: Record<string, { tone: Tone; label: string }> = {
  ready: { tone: "good", label: "Ready" },
  missing: { tone: "warn", label: "Jar missing" },
  mismatch: { tone: "bad", label: "Jar changed" },
  unreadable: { tone: "bad", label: "Cannot be read" },
};

/** Common drivers' class names and URL starts, offered as suggestions only: the admin decides. */
const KNOWN: { label: string; driverClass: string; urlPrefix: string; properties: string }[] = [
  { label: "IBM Db2", driverClass: "com.ibm.db2.jcc.DB2Driver", urlPrefix: "jdbc:db2://", properties: "currentSchema, sslConnection" },
  { label: "Teradata", driverClass: "com.teradata.jdbc.TeraDriver", urlPrefix: "jdbc:teradata://", properties: "DATABASE, CHARSET, TMODE, ENCRYPTDATA" },
  { label: "Sybase (jConnect)", driverClass: "com.sybase.jdbc4.jdbc.SybDriver", urlPrefix: "jdbc:sybase:Tds:", properties: "CHARSET" },
  { label: "Informix", driverClass: "com.informix.jdbc.IfxDriver", urlPrefix: "jdbc:informix-sqli://", properties: "INFORMIXSERVER, DB_LOCALE" },
  { label: "H2", driverClass: "org.h2.Driver", urlPrefix: "jdbc:h2:", properties: "MODE, SCHEMA" },
];

const size = (bytes: number) =>
  bytes >= 1 << 20 ? `${(bytes / (1 << 20)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;

/**
 * Settings › JDBC drivers, for admins: the drivers registered for Generic JDBC sources, registering
 * a jar from the drivers folder (or uploaded to it), its checksum and who registered it, and the
 * audit trail. Next to Settings › Drivers, which installs the drivers Kilnmark pins itself.
 */
export function JdbcDriversPage() {
  useTitle("JDBC drivers");
  const [items, setItems] = useState<RegisteredDriverItem[] | null>(null);
  const [folder, setFolder] = useState<DriverFolderListing | null>(null);
  const [history, setHistory] = useState<DriverLogEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");

  const load = useCallback(() => {
    Promise.all([api.registeredDrivers(), api.driverFiles(), api.driverHistory()])
      .then(([r, f, h]) => {
        setItems(r);
        setFolder(f);
        setHistory(h);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  const remove = async (d: RegisteredDriverItem) => {
    if (!window.confirm(`Remove the registration of ${d.name}? Sources can no longer choose it. The jar stays in the drivers folder.`)) return;
    try {
      await api.removeDriver(d.name);
      setNotice(`The driver ${d.name} is no longer registered.`);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      <PageHeader
        title="JDBC drivers"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "JDBC drivers" }]}
        lead={
          <>
            For databases without a connector of their own, such as Db2, Teradata or Informix, you bring the database's
            JDBC driver and register it here; Generic JDBC sources then use it. For the drivers Kilnmark pins itself, see{" "}
            <a href={href.drivers()}>Drivers</a>.
          </>
        }
      />
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <Card title="Registered drivers" titleId="registered-h" className="km-section">
        {items === null && !error && <p role="status" className="muted">Loading…</p>}
        {items?.length === 0 && (
          <EmptyState title="No driver registered yet">Register one below to add Generic JDBC sources.</EmptyState>
        )}
        {items && items.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Registered drivers (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">State</th>
                  <th scope="col">Jar and class</th>
                  <th scope="col">URLs and properties</th>
                  <th scope="col">Registered</th>
                  <th scope="col">Used by</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map((d) => {
                  const state = STATE[d.state] ?? { tone: "neutral" as Tone, label: d.state };
                  return (
                    <tr key={d.name}>
                      <th scope="row" className="mono">{d.name}</th>
                      <td>
                        <StatusChip tone={state.tone} dot>{state.label}</StatusChip>
                        {d.message && <div className="small muted">{d.message}</div>}
                      </td>
                      <td className="small">
                        <span className="mono">{d.fileName}</span>
                        <div className="mono muted">{d.driverClass}</div>
                        <div className="muted">
                          SHA-256 <span className="mono" title={d.sha256}>{d.sha256.slice(0, 16)}…</span>
                        </div>
                      </td>
                      <td className="mono small">
                        {d.urlPrefix}
                        <div className="muted">
                          {d.allowedProperties.length ? `Properties: ${d.allowedProperties.join(", ")}` : "No properties"}
                        </div>
                      </td>
                      <td className="small">
                        {formatTime(d.registeredAt)}
                        <div className="muted">by {d.registeredBy}</div>
                      </td>
                      <td className="small">{d.usedBy.length ? d.usedBy.join(", ") : <span className="muted">No sources</span>}</td>
                      <td>
                        <Button size="sm" variant="danger" onClick={() => remove(d)} aria-label={`Remove ${d.name}`}
                          aria-describedby={d.usedBy.length ? `used-${d.name}` : undefined}>
                          Remove
                        </Button>
                        {d.usedBy.length > 0 && (
                          <span id={`used-${d.name}`} className="visually-hidden">
                            Sources still use it, so removing is refused until they use another driver.
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <RegisterDriver folder={folder} onChange={load} onDone={(text) => setNotice(text)} />

      <Card title="History" titleId="history-h" className="km-section"
        subtitle="Who registered and removed which drivers, and when. Kept for good.">
        {history.length === 0 ? (
          <p className="muted">Nothing registered yet.</p>
        ) : (
          <div className="table-scroll" role="region" aria-label="Driver history (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">When</th>
                  <th scope="col">What</th>
                  <th scope="col">Driver</th>
                  <th scope="col">By</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h, i) => (
                  <tr key={`${h.at}-${i}`}>
                    <td className="small">{formatTime(h.at)}</td>
                    <td>{h.action === "registered" ? "Registered" : "Removed"}</td>
                    <td className="small">
                      <span className="mono">{h.name}</span>: <span className="mono">{h.fileName}</span>
                      <div className="muted">
                        SHA-256 <span className="mono" title={h.sha256}>{h.sha256.slice(0, 16)}…</span>
                      </div>
                    </td>
                    <td className="small">{h.by}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

/** Choose or upload a jar, name the driver, its class and its URLs, and register it. */
function RegisterDriver({
  folder,
  onChange,
  onDone,
}: {
  folder: DriverFolderListing | null;
  onChange: () => void;
  onDone: (text: string) => void;
}) {
  const [fileName, setFileName] = useState("");
  const [name, setName] = useState("");
  const [driverClass, setDriverClass] = useState("");
  const [urlPrefix, setUrlPrefix] = useState("");
  const [allowed, setAllowed] = useState("");
  const [vouch, setVouch] = useState(false);
  const [busy, setBusy] = useState<"" | "upload" | "register">("");
  const [problem, setProblem] = useState<string | null>(null);
  const upload = useRef<HTMLInputElement>(null);
  const problemRef = useRef<HTMLParagraphElement>(null);

  const free = (folder?.files ?? []).filter((f) => !f.registeredAs && !f.pinned);
  const fail = (e: unknown) => {
    setProblem((e as Error).message);
    window.setTimeout(() => problemRef.current?.focus(), 0);
  };

  const sendFile = async () => {
    const file = upload.current?.files?.[0];
    if (!file) {
      fail(new Error("Choose a jar to upload first."));
      return;
    }
    if (folder && file.size > folder.maxUploadBytes) {
      fail(new Error(`${file.name} is ${size(file.size)}, more than this server accepts as an upload (${size(folder.maxUploadBytes)}). Copy it into the drivers folder ${folder.folder} on the server instead, then choose it above.`));
      return;
    }
    setBusy("upload");
    setProblem(null);
    try {
      const f = await api.uploadDriver(file.name, file);
      setFileName(f.fileName);
      onDone(`${f.fileName} is in the drivers folder. Now name the driver and register it.`);
      if (upload.current) upload.current.value = "";
      onChange();
    } catch (e) {
      fail(e);
    } finally {
      setBusy("");
    }
  };

  const suggest = (label: string) => {
    const k = KNOWN.find((x) => x.label === label);
    if (!k) return;
    setDriverClass(k.driverClass);
    setUrlPrefix(k.urlPrefix);
    setAllowed(k.properties);
    if (!name) setName(k.label.split(" ")[0].toLowerCase().replace(/[^a-z0-9]/g, ""));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!vouch) {
      fail(new Error("Confirm that the jar comes from the database's vendor or another source you trust."));
      return;
    }
    setBusy("register");
    setProblem(null);
    try {
      const r = await api.registerDriver({
        name: name.trim(),
        fileName,
        driverClass: driverClass.trim(),
        urlPrefix: urlPrefix.trim(),
        allowedProperties: allowed,
      });
      onDone(`Registered ${r.name}. Its checksum is recorded: if the jar changes, it is not used until registered again.`);
      setName("");
      setDriverClass("");
      setUrlPrefix("");
      setAllowed("");
      setFileName("");
      setVouch(false);
      onChange();
    } catch (err) {
      fail(err);
    } finally {
      setBusy("");
    }
  };

  return (
    <Card title="Register a driver" titleId="register-h" className="km-section km-form-card">
      <p className="km-callout km-callout--info">
        A registered driver runs inside the Kilnmark server, with the server's rights. Kilnmark loads it on its own and
        only while the jar is the one you registered, but cannot vet what it does. Register only drivers from the
        database's vendor, or another source you trust. Nothing is downloaded for you.
      </p>
      {problem && (
        <p role="alert" className="error" tabIndex={-1} ref={problemRef}>
          {problem}
        </p>
      )}
      <form onSubmit={submit} className="form">
        <fieldset>
          <legend>The jar</legend>
          <div className="field">
            <label htmlFor="reg-file">Jar in the drivers folder</label>
            <select id="reg-file" value={fileName} onChange={(e) => setFileName(e.target.value)} required
              aria-describedby="reg-file-hint">
              <option value="">Choose a jar…</option>
              {free.map((f) => (
                <option key={f.fileName} value={f.fileName}>
                  {f.fileName} ({size(f.size)})
                </option>
              ))}
            </select>
            <p id="reg-file-hint" className="hint">
              Jars not yet registered in <span className="mono">{folder?.folder ?? "the drivers folder"}</span>. Copy one
              there, or upload it below.
            </p>
          </div>
          <div className="field">
            <label htmlFor="reg-upload">Or upload a jar</label>
            <div className="field-row">
              <input id="reg-upload" type="file" accept=".jar,application/java-archive" ref={upload}
                aria-describedby="reg-upload-hint" />
              <Button onClick={sendFile} disabled={busy !== ""}>
                {busy === "upload" ? "Uploading…" : "Upload to the drivers folder"}
              </Button>
            </div>
            <p id="reg-upload-hint" className="hint">
              Up to {folder ? size(folder.maxUploadBytes) : "the server's upload limit"}, what this server accepts as an
              upload. Larger drivers are copied into the drivers folder by hand. A file of the same name is never replaced.
            </p>
          </div>
        </fieldset>
        <fieldset>
          <legend>The driver</legend>
          <div className="field">
            <label htmlFor="reg-known">Fill in for a known database</label>
            <select id="reg-known" defaultValue="" onChange={(e) => suggest(e.target.value)} aria-describedby="reg-known-hint">
              <option value="">Choose to fill in…</option>
              {KNOWN.map((k) => (
                <option key={k.label} value={k.label}>
                  {k.label}
                </option>
              ))}
            </select>
            <p id="reg-known-hint" className="hint">Suggestions only: check them against the driver's documentation.</p>
          </div>
          <div className="field-row">
            <div className="field">
              <label htmlFor="reg-name">Name</label>
              <input id="reg-name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={40}
                pattern="[a-z0-9][a-z0-9_\-]{0,39}" autoComplete="off" aria-describedby="reg-name-hint" />
              <p id="reg-name-hint" className="hint">What sources choose, such as db2: lower-case letters, digits, - and _.</p>
            </div>
            <div className="field">
              <label htmlFor="reg-prefix">URLs start with</label>
              <input id="reg-prefix" value={urlPrefix} onChange={(e) => setUrlPrefix(e.target.value)} required
                maxLength={100} autoComplete="off" spellCheck={false} className="mono" aria-describedby="reg-prefix-hint" />
              <p id="reg-prefix-hint" className="hint">For example jdbc:db2:// A source's URL must start with this.</p>
            </div>
          </div>
          <div className="field">
            <label htmlFor="reg-class">Driver class</label>
            <input id="reg-class" value={driverClass} onChange={(e) => setDriverClass(e.target.value)} required
              maxLength={300} autoComplete="off" spellCheck={false} className="mono" aria-describedby="reg-class-hint" />
            <p id="reg-class-hint" className="hint">
              The class that implements java.sql.Driver, from the driver's documentation. Kilnmark loads it to check.
            </p>
          </div>
          <div className="field">
            <label htmlFor="reg-allowed">Properties sources may set</label>
            <textarea id="reg-allowed" value={allowed} onChange={(e) => setAllowed(e.target.value)} rows={2}
              spellCheck={false} className="mono" aria-describedby="reg-allowed-hint" />
            <p id="reg-allowed-hint" className="hint">
              The driver's properties a source may give, in its URL or as driver properties, separated by commas, for
              example currentSchema, sslConnection. Any other is refused. Leave out anything that reads files, runs
              scripts or names another server; passwords, keys and tokens are never allowed.
            </p>
          </div>
        </fieldset>
        <label>
          <input type="checkbox" checked={vouch} onChange={(e) => setVouch(e.target.checked)} />
          {" "}This jar comes from the database's vendor or another source we trust
        </label>
        <div className="buttons">
          <Button type="submit" variant="primary" disabled={busy !== ""}>
            {busy === "register" ? "Checking the jar…" : "Register driver"}
          </Button>
        </div>
      </form>
    </Card>
  );
}
