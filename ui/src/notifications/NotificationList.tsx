import { formatAgo, safeAppLink } from "../format";
import { Icon } from "../components/icons";
import { SeverityChip } from "../components/ui";
import type { AppNotification } from "./api";

/** How it was emailed, in words with an icon: Emailed, Email queued, Email failed. Nothing if not emailed. */
function Delivery({ n }: { n: AppNotification }) {
  const email = n.channels?.find((c) => c.type === "email");
  const team = n.teamEmails?.filter((t) => t.name).map((t) => t.name) ?? [];
  const parts = [];
  if (email?.status === "sent")
    parts.push(
      <span key="e" className="km-delivery">
        <Icon name="mail" size={14} /> Emailed
      </span>,
    );
  else if (email?.status === "queued")
    parts.push(
      <span key="e" className="km-delivery">
        <Icon name="mail-queued" size={14} /> Email queued
      </span>,
    );
  else if (email?.status === "failed")
    parts.push(
      <span key="e" className="km-delivery km-delivery--failed">
        <Icon name="mail-failed" size={14} /> Email failed
      </span>,
    );
  if (team.length > 0)
    parts.push(
      <span key="t" className="km-delivery">
        <Icon name="users" size={14} /> Also sent to {team.join(", ")}
      </span>,
    );
  return parts.length ? <span className="km-delivery-line">{parts}</span> : null;
}

/**
 * The latest notifications: a severity chip (in words), the title (heavier, with a small accent
 * dot, when unread; "Unread" is said for screen readers), the detail, when, how it was emailed,
 * and a link to what it is about. Following the link marks it read.
 */
export function NotificationList({
  items,
  onOpen,
  headingLevel = 3,
}: {
  items: AppNotification[];
  onOpen: (n: AppNotification) => void;
  headingLevel?: 3 | 4;
}) {
  const H = headingLevel === 3 ? "h3" : "h4";
  return (
    <ul className="km-notes">
      {items.map((n) => {
        const link = safeAppLink(n.link);
        return (
          <li key={n.id} className={n.read ? "km-note" : "km-note km-note--unread"}>
            <SeverityChip severity={n.severity} />
            <div className="km-note-body">
              <H className="km-note-title">
                {!n.read && (
                  <>
                    <span className="km-unread-dot" aria-hidden="true" />
                    <span className="visually-hidden">Unread: </span>
                  </>
                )}
                {link ? (
                  <a href={link} onClick={() => onOpen(n)}>
                    {n.title}
                  </a>
                ) : (
                  n.title
                )}
              </H>
              {n.detail && <p className="km-note-detail">{n.detail}</p>}
              <p className="km-note-meta">
                <time dateTime={n.createdAt}>{formatAgo(n.createdAt)}</time>
                <Delivery n={n} />
              </p>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
