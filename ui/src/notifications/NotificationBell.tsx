import { useCallback, useEffect, useId, useRef, useState } from "react";
import { allows, type AccessRole } from "../api";
import { Icon } from "../components/icons";
import { EmptyState, useDismiss } from "../components/ui";
import { NotificationList } from "./NotificationList";
import { markNotificationsRead, refreshNotifications, useNotifications } from "./store";

export const NOTIFICATION_SETTINGS = "#/settings/notifications";
export const SENT_EMAILS = "#/settings/email/sent";
/** Fired on window to open the bell's list (the home card's View all). */
export const OPEN_NOTIFICATIONS = "kilnmark:open-notifications";

/**
 * The bell in the top bar: the unread count in its name ("Notifications, 3 unread") and on a
 * badge; it opens a small panel with the latest notifications and "Mark all read". Escape or a
 * click outside closes it and focus returns to the bell. Hidden when the server has none (404).
 */
export function NotificationBell({ role }: { role: AccessRole }) {
  const n = useNotifications();
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState("");
  const bell = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const id = useId();
  const close = useCallback((returnFocus: boolean) => {
    setOpen(false);
    if (returnFocus) bell.current?.focus();
  }, []);
  useDismiss(open, panel, bell, close);
  useEffect(() => {
    const onOpen = () => {
      void refreshNotifications();
      setOpen(true);
      window.setTimeout(() => panel.current?.querySelector<HTMLElement>("a, button")?.focus(), 0);
    };
    window.addEventListener(OPEN_NOTIFICATIONS, onOpen);
    return () => window.removeEventListener(OPEN_NOTIFICATIONS, onOpen);
  }, []);
  if (n.available !== true) return null;

  const label = n.unread > 0 ? `Notifications, ${n.unread} unread` : "Notifications, none unread";
  return (
    <div className="km-popover-wrap">
      <button
        ref={bell}
        type="button"
        className="km-icon-button km-bell"
        aria-label={label}
        aria-expanded={open}
        aria-controls={open ? `${id}-panel` : undefined}
        onClick={() => {
          if (!open) void refreshNotifications();
          setMessage("");
          setOpen(!open);
        }}
      >
        <Icon name="bell" size={20} />
        {n.unread > 0 && (
          <span className="km-badge-count num" aria-hidden="true">
            {n.unread > 99 ? "99+" : n.unread}
          </span>
        )}
      </button>
      {open && (
        <div ref={panel} id={`${id}-panel`} className="km-popover km-popover--notes" role="dialog" aria-labelledby={`${id}-h`}>
          <div className="km-popover-head">
            <h2 id={`${id}-h`} className="km-popover-title">
              Notifications
            </h2>
            {n.unread > 0 && (
              <button
                type="button"
                className="link-button"
                onClick={() =>
                  markNotificationsRead("all")
                    .then(() => setMessage("All notifications marked read."))
                    .catch((e: Error) => setMessage(e.message))
                }
              >
                Mark all read
              </button>
            )}
          </div>
          <p role="status" className="visually-hidden">
            {message}
          </p>
          {n.error && <p className="error">{n.error}</p>}
          <div className="km-popover-body">
          {n.items && n.items.length === 0 ? (
            <EmptyState compact icon={<Icon name="circle-check" size={22} />} title="You're all caught up">
              New notifications will show here.
            </EmptyState>
          ) : (
            n.items && (
              <NotificationList
                items={n.items}
                headingLevel={3}
                onOpen={(x) => {
                  close(false);
                  if (!x.read) void markNotificationsRead([x.id]).catch(() => undefined);
                }}
              />
            )
          )}
          </div>
          <div className="km-popover-foot">
            <a href={NOTIFICATION_SETTINGS} onClick={() => close(false)}>
              Notification settings
            </a>
            {allows(role, "admin") && (
              <a href={SENT_EMAILS} onClick={() => close(false)}>
                Sent emails
              </a>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
