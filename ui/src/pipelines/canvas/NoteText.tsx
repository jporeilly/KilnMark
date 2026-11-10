import { Fragment, type ReactNode } from "react";
import { marked, type Token, type Tokens } from "marked";

/**
 * A note's text, safely: a small part of Markdown (paragraphs, **bold**, *italic*, lists and links)
 * made into React elements, never HTML. Everything else is shown as the text it was written as:
 * raw HTML, images, headings' marks, code blocks and any other Markdown. A link becomes one only to
 * an http or https address, and opens in a new tab with `rel="noopener noreferrer"`; any other link
 * (javascript:, data:, a path) is shown as the text it was written as. React writes every piece of
 * text as text, so nothing a note holds can become markup or a script.
 */
export function NoteText({ text }: { text: string }) {
  let tokens: Token[];
  try {
    tokens = marked.lexer(text, { gfm: true });
  } catch {
    return <p>{text}</p>;
  }
  return <>{blocks(tokens)}</>;
}

/** Whether a link may be followed: an http(s) address, with nothing a browser could read as another scheme or host. */
export function safeHref(href: string): string | null {
  // Control characters, spaces and backslashes can make a browser read an address as something else.
  if (!/^https?:\/\//i.test(href) || /[\u0000- \u007f\\]/.test(href)) return null;
  try {
    const u = new URL(href);
    return u.protocol === "http:" || u.protocol === "https:" ? href : null;
  } catch {
    return null;
  }
}

const rawOf = (t: Token) => ("raw" in t && typeof t.raw === "string" ? t.raw : "");

function blocks(tokens: Token[]): ReactNode[] {
  return tokens.map((t, i) => {
    switch (t.type) {
      case "space":
        return null;
      case "paragraph":
        return <p key={i}>{inline((t as Tokens.Paragraph).tokens)}</p>;
      case "list": {
        const l = t as Tokens.List;
        const items = l.items.map((it, k) => <li key={k}>{listItem(it)}</li>);
        return l.ordered ? (
          <ol key={i} start={typeof l.start === "number" && l.start !== 1 ? l.start : undefined}>
            {items}
          </ol>
        ) : (
          <ul key={i}>{items}</ul>
        );
      }
      case "text": {
        const x = t as Tokens.Text;
        return <p key={i}>{x.tokens ? inline(x.tokens) : x.text}</p>;
      }
      default:
        // Headings, code, quotes, tables, HTML and the rest: the words as written.
        return (
          <p key={i} className="cv-note-plain">
            {rawOf(t).replace(/\n+$/, "")}
          </p>
        );
    }
  });
}

function listItem(item: Tokens.ListItem): ReactNode {
  return item.tokens.map((t, i) => {
    if (t.type === "text") {
      const x = t as Tokens.Text;
      return <Fragment key={i}>{x.tokens ? inline(x.tokens) : x.text}</Fragment>;
    }
    return <Fragment key={i}>{blocks([t])}</Fragment>;
  });
}

function inline(tokens: Token[] | undefined): ReactNode[] {
  return (tokens ?? []).map((t, i) => {
    switch (t.type) {
      case "text": {
        const x = t as Tokens.Text;
        return <Fragment key={i}>{x.tokens ? inline(x.tokens) : x.text}</Fragment>;
      }
      case "escape":
        return <Fragment key={i}>{(t as Tokens.Escape).text}</Fragment>;
      case "strong":
        return <strong key={i}>{inline((t as Tokens.Strong).tokens)}</strong>;
      case "em":
        return <em key={i}>{inline((t as Tokens.Em).tokens)}</em>;
      case "codespan":
        return (
          <code key={i} className="mono">
            {(t as Tokens.Codespan).text}
          </code>
        );
      case "br":
        return <br key={i} />;
      case "link": {
        const l = t as Tokens.Link;
        const href = safeHref(l.href);
        if (href == null) return <Fragment key={i}>{rawOf(t)}</Fragment>;
        return (
          <a key={i} href={href} target="_blank" rel="noopener noreferrer" className="cv-note-link">
            {inline(l.tokens)}
          </a>
        );
      }
      default:
        // Raw HTML, images, struck-out text and anything else: as written.
        return <Fragment key={i}>{rawOf(t)}</Fragment>;
    }
  });
}
