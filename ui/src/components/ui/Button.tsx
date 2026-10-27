import type { AnchorHTMLAttributes, ButtonHTMLAttributes, ReactNode } from "react";

export type ButtonVariant = "primary" | "secondary" | "subtle" | "ghost" | "danger" | "ask";

const cls = (variant: ButtonVariant, size: "sm" | "md", extra?: string) =>
  ["km-button", variant === "secondary" ? "" : variant, size === "sm" ? "km-button--sm" : "", extra ?? ""]
    .filter(Boolean)
    .join(" ");

/**
 * Buttons: primary (the accent fill), secondary (the default), subtle (a tinted fill), ghost (text
 * only), danger, and ask (the violet "Ask Kilnmark" style). Plain <button> elements get the
 * secondary look from app.css too, so older markup matches.
 */
export function Button({
  variant = "secondary",
  size = "md",
  icon,
  className,
  children,
  type = "button",
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: "sm" | "md";
  icon?: ReactNode;
}) {
  return (
    <button type={type} className={cls(variant, size, className)} {...rest}>
      {icon}
      {children}
    </button>
  );
}

/** A link that looks like a button, for navigation ("Add a connection", "All runs"). */
export function ButtonLink({
  variant = "secondary",
  size = "md",
  icon,
  className,
  children,
  ...rest
}: AnchorHTMLAttributes<HTMLAnchorElement> & { variant?: ButtonVariant; size?: "sm" | "md"; icon?: ReactNode }) {
  return (
    <a className={cls(variant, size, className)} {...rest}>
      {icon}
      {children}
    </a>
  );
}
