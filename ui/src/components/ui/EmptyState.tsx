import type { ReactNode } from "react";

/** A calm empty or not-yet state: an icon in a soft circle, a short title, a line and an action. */
export function EmptyState({
  icon,
  title,
  children,
  action,
  compact = false,
}: {
  icon?: ReactNode;
  title: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  compact?: boolean;
}) {
  return (
    <div className={`km-empty${compact ? " km-empty--compact" : ""}`}>
      {icon && (
        <span className="km-empty-icon" aria-hidden="true">
          {icon}
        </span>
      )}
      <p className="km-empty-title">{title}</p>
      {children && <div className="km-empty-text">{children}</div>}
      {action && <div className="km-empty-action">{action}</div>}
    </div>
  );
}
