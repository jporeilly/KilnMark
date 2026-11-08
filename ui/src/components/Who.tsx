/**
 * Who did something, as the server records them, in one display everywhere: an API token by its
 * name, a person who signs in through the identity provider by their sso: name, and anything else
 * as it is. The server writes a token both as its bare name and as "token 1 (name)", and a provider
 * sign-in with the provider's ID for them in brackets ("sso:dana (2f6c…)"): the screen shows the
 * short name, which wraps, and the full record on hover. The sso: prefix stays, so no token can pass
 * for a person (a token's name may not start with it).
 */
export function whoParts(recorded: string | null | undefined): { shown: string; full: string } {
  const full = (recorded ?? "").trim();
  if (!full) return { shown: "–", full: "" };
  const token = /^token \d+ \((.+)\)$/.exec(full);
  if (token) return { shown: token[1], full };
  const person = /^(sso:.+?) \([^()]+\)$/.exec(full);
  if (person) return { shown: person[1], full };
  return { shown: full, full };
}

/** The short name, as whoParts gives it, with the full record as its tooltip when they differ. */
export function Who({ name, className }: { name: string | null | undefined; className?: string }) {
  const { shown, full } = whoParts(name);
  return (
    <span className={className ? `km-who ${className}` : "km-who"} title={full && full !== shown ? full : undefined}>
      {shown}
    </span>
  );
}
