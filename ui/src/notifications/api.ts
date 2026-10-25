import { ApiError, call } from "../api";

/**
 * In-app notifications: what happened (a rule started failing, a scan finished, an email could not
 * be sent). The endpoints arrive with the notifications branch; until then they answer 404 and the
 * bell and the home card stay hidden (see store.ts).
 */
export type NotificationSeverity = "high" | "medium" | "low" | "info";
export type EmailStatus = "sent" | "queued" | "failed" | "skipped";
export type NotificationChannel = { type: string; status?: EmailStatus | string; at?: string };
export type TeamEmail = { name: string; status: string };

export type AppNotification = {
  id: string;
  kind: string;
  severity: NotificationSeverity;
  title: string;
  detail: string | null;
  /** An in-app link ("#/…"); anything else is shown as text. */
  link: string | null;
  createdAt: string;
  read: boolean;
  /** How it was delivered: in the app, by email (with its status). Missing on older servers. */
  channels?: NotificationChannel[];
  /** Admins only: the team addresses it was also emailed to. */
  teamEmails?: TeamEmail[];
};

export type NotificationPage = { items: AppNotification[]; unread: number };

export const notificationsApi = {
  list: (limit = 6) => call<NotificationPage>(`/api/v1/notifications?limit=${limit}`),
  unreadCount: () => call<{ unread: number }>("/api/v1/notifications/unread-count"),
  markRead: (ids: string[] | "all") =>
    call<null>("/api/v1/notifications/read", {
      method: "POST",
      body: JSON.stringify(ids === "all" ? { all: true } : { ids }),
    }),
};

export const isMissing = (e: unknown) => e instanceof ApiError && (e.status === 404 || e.status === 405);
