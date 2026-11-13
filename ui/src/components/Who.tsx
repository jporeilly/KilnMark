import { Fragment } from "react";

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

/**
 * A name in the pieces it may wrap between: after each _ . and @ inside it, so a long token name or
 * sign-in name (KILNMARK_ADMIN_TOKEN, sso:dana.reyes@example.com) breaks between its words and not
 * in the middle of one. Never after sso:, which would leave the prefix on a line of its own. Joined,
 * the pieces are the name.
 */
export function wrapPieces(name: string): string[] {
  return name.split(/(?<=[_.@])(?=[^_.@])/);
}

/**
 * Whether a recorded name is surely an API token's: the server's "token 1 (name)" form. A bare name
 * may be a token's or a sign-in name someone typed (an owner, say), so it is not called a token.
 */
export function isToken(recorded: string | null | undefined): boolean {
  return /^token \d+ \(.+\)$/.test((recorded ?? "").trim());
}

/** The short name, as whoParts gives it, with the full record as its tooltip when they differ. */
export function Who({ name, className }: { name: string | null | undefined; className?: string }) {
  const { shown, full } = whoParts(name);
  const pieces = wrapPieces(shown);
  return (
    <span className={className ? `km-who ${className}` : "km-who"} title={full && full !== shown ? full : undefined}>
      {pieces.map((p, i) => (
        <Fragment key={i}>
          {i > 0 && <wbr />}
          {p}
        </Fragment>
      ))}
    </span>
  );
}
