import type { ReactNode } from "react";

/**
 * A table in the new style: uppercase 12 px headers, row dividers, no outer border. Wide tables
 * scroll sideways inside a labelled, focusable region (so keyboard users can scroll it), never the
 * page. Pass the <thead> and <tbody> as children; `caption` is read out (visually hidden unless
 * `showCaption`).
 */
export function DataTable({
  label,
  caption,
  showCaption = false,
  className,
  children,
}: {
  label: string;
  caption?: ReactNode;
  showCaption?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className="table-scroll" role="region" aria-label={label} tabIndex={0}>
      <table className={`km-table${className ? ` ${className}` : ""}`}>
        {caption && <caption className={showCaption ? undefined : "visually-hidden"}>{caption}</caption>}
        {children}
      </table>
    </div>
  );
}
