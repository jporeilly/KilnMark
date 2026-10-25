import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError, type Me } from "../api";
import { useTitle } from "../components/useTitle";
import { Brand, useProductName } from "../components/Brand";

/** Why the sign-in page is showing: after signing out (info) or after the token stopped working (error). */
export type SignInNotice = { kind: "info" | "error"; text: string };

/** Asks for an API token, checks it with the server, and keeps it for this browser tab. */
export function SignInPage({ notice, onSignedIn }: { notice: SignInNotice | null; onSignedIn: (me: Me) => void }) {
  useTitle("Sign in");
  const product = useProductName();
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const input = useRef<HTMLInputElement>(null);

  // Arriving here after signing out or being signed out: start screen readers at the heading.
  useEffect(() => {
    if (notice) heading.current?.focus();
  }, [notice]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const t = token.trim();
    if (!t) {
      setError("Enter your API token.");
      input.current?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const me = await api.signIn(t);
      setToken("");
      onSignedIn(me);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Signing in failed. Try again.");
      input.current?.focus();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="signin">
      <p className="brand signin-brand" aria-hidden="true">
        <Brand />
      </p>
      <h1 tabIndex={-1} ref={heading}>
        Sign in to {product}
      </h1>
      {notice && (
        <p role={notice.kind === "error" ? "alert" : "status"} className={notice.kind === "error" ? "error" : "notice"}>
          {notice.text}
        </p>
      )}
      <form className="panel form" onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor="token">API token</label>
          <input
            id="token"
            ref={input}
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            autoComplete="off"
            spellCheck={false}
            required
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? "token-error token-hint" : "token-hint"}
          />
          {error && (
            <p id="token-error" role="alert" className="error">
              {error}
            </p>
          )}
          <p id="token-hint" className="hint">
            A token starts with <span className="mono">km_</span>. Ask a Kilnmark admin for one. On a new install, the
            first admin token is in the file <span className="mono">admin-token</span> next to the server's secret key.
            The token is kept only until you close this browser tab.
          </p>
        </div>
        <button type="submit" className="primary" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
