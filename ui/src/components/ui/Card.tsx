import type { ElementType, ReactNode } from "react";

/**
 * A white card on the page ground: 16 px radius, soft shadow, no border (tokens.css). Give it a
 * title and it renders an h2 (or `level`) with the card labelled by it; `actions` sit at the end of
 * the title row (a "See all" link, a button). `tone="dark"` is the dark card used for the overall
 * score; `accentTop` adds the violet top edge used for suggestions.
 */
export function Card({
  title,
  titleId,
  level = 2,
  icon,
  actions,
  subtitle,
  as,
  className,
  tone,
  accentTop,
  children,
  ...rest
}: {
  title?: ReactNode;
  titleId?: string;
  level?: 2 | 3;
  icon?: ReactNode;
  actions?: ReactNode;
  subtitle?: ReactNode;
  as?: ElementType;
  className?: string;
  tone?: "dark";
  accentTop?: "violet" | "accent";
  children?: ReactNode;
  "aria-label"?: string;
  id?: string;
}) {
  const Tag = (as ?? (title ? "section" : "div")) as ElementType;
  const H = (level === 3 ? "h3" : "h2") as ElementType;
  const classes = [
    "km-card",
    tone === "dark" ? "km-card--dark" : "",
    accentTop ? `km-card--top-${accentTop}` : "",
    className ?? "",
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <Tag className={classes} aria-labelledby={title && titleId ? titleId : undefined} {...rest}>
      {(title || actions) && (
        <div className="km-card-head">
          {icon && <span className="km-card-icon">{icon}</span>}
          {title && (
            <H id={titleId} className="km-card-title">
              {title}
            </H>
          )}
          {actions && <div className="km-card-actions">{actions}</div>}
        </div>
      )}
      {subtitle && <p className="km-card-sub">{subtitle}</p>}
      {children}
    </Tag>
  );
}
