import { useEffect, useState } from "react";

type Info = { product: string; version: string; roles: string[] };

/** App shell: skip link, main navigation and a status panel showing the server's roles. */
export function App() {
  const [info, setInfo] = useState<Info | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch("/api/v1/info")
      .then((response) => {
        if (!response.ok) throw new Error(`Server returned ${response.status}`);
        return response.json() as Promise<Info>;
      })
      .then(setInfo)
      .catch((e: Error) => setError(e.message));
  }, []);

  return (
    <>
      <a href="#main" className="skip">
        Skip to main content
      </a>
      <div style={{ display: "flex", flexWrap: "wrap", minHeight: "100vh" }}>
        <nav aria-label="Main" style={{ flex: "1 1 232px", background: "#15171c", padding: 20 }}>
          <p style={{ margin: 0, color: "#ffffff", fontWeight: 700, fontSize: 18 }}>Kilnmark</p>
        </nav>
        <main id="main" style={{ flex: "999 1 560px", minWidth: 0, padding: 32 }}>
          <h1 style={{ marginTop: 0 }}>Kilnmark</h1>
          <section aria-labelledby="status-h">
            <h2 id="status-h">Server status</h2>
            <p role="status">
              {error
                ? `Cannot reach the server: ${error}`
                : info
                  ? `${info.product} ${info.version}, roles: ${info.roles.join(", ")}`
                  : "Checking the server…"}
            </p>
          </section>
        </main>
      </div>
    </>
  );
}
