import type { ConnectorSetting } from "../api";

/**
 * Which control a connector setting gets. The server describes connector settings with a label
 * and a hint only (Views.Setting has no type), and some hints read "true (the default) or false",
 * so the form needs to know which are on/off settings, lists of choices, numbers or secrets. Known
 * keys are listed here; other settings whose hint offers just true and false are treated as
 * switches too. Values are still sent as the strings the server expects ("true", "false").
 */
export type SettingKind =
  | { kind: "switch"; defaultOn: boolean; help: string }
  | { kind: "select"; choices: { value: string; label: string }[]; defaultValue: string; help: string }
  | { kind: "number"; min?: number; max?: number; help: string }
  | { kind: "password"; help: string }
  | { kind: "text" };

/** Help for the on/off settings, said in terms of the switch rather than "true" and "false". */
const SWITCH_HELP: Record<string, string> = {
  recursive: "Also scan the folders inside this folder.",
  header: "On: the first row of a delimited file names the columns. Off: it is data, and the columns are named column_1, column_2 and so on.",
  keep_text:
    "On: documents' text is kept in Kilnmark for search and reading. Off: only their properties are kept (title, author, pages, language, words), and text kept before is deleted at the next scan.",
  read_text_on_demand:
    "Only when Keep document text is off. On: stewards can view a document's text, read from the source when they open it and never stored. Off: only its properties are shown.",
  path_style: "On for most S3-compatible storage (VersityGW, MinIO); off for AWS.",
};

const CHOICES: Record<string, { choices: { value: string; label: string }[]; defaultValue: string; help: string }> = {
  isolation: {
    choices: [
      { value: "read-uncommitted", label: "Read uncommitted (default)" },
      { value: "snapshot", label: "Snapshot" },
      { value: "read-committed", label: "Read committed" },
    ],
    defaultValue: "read-uncommitted",
    help: "Read uncommitted takes no shared locks, so reading never blocks the application's writes, but may see changes not yet committed. Snapshot reads a consistent view without locks if the database allows snapshot isolation. Read committed takes shared locks and can block writers.",
  },
};

/** "true (the default) …", "… false (the default) …": which way the setting defaults. */
function defaultFromHint(hint: string): boolean | null {
  if (/\btrue\s*\(the default\)/i.test(hint)) return true;
  if (/\bfalse\s*\(the default\)/i.test(hint)) return false;
  return null;
}

/** Turns "true …; false …" wording into "On …; Off …" for a switch whose help is not listed above. */
function switchHelp(hint: string): string {
  return hint
    .replace(/\btrue\s*\(the default\)\s*or\s*false\.?/i, "")
    .replace(/\btrue\s*\(the default\)/gi, "On (the default)")
    .replace(/\bfalse\s*\(the default\)/gi, "Off (the default)")
    .replace(/\btrue\b/g, "on")
    .replace(/\bfalse\b/g, "off")
    .trim();
}

const NUMBER_KEY = /(^|_)(port|timeout|seconds|limit|rows|size|retries)$/;
const SECRET_KEY = /(password|secret|token|private_key)$/;

export function settingKind(s: ConnectorSetting): SettingKind {
  if (CHOICES[s.key]) return { kind: "select", ...CHOICES[s.key] };
  const listed = Object.hasOwn(SWITCH_HELP, s.key);
  const offersBoolean = /\btrue\b/i.test(s.hint) && /\bfalse\b/i.test(s.hint) && !s.multiple;
  if (listed || offersBoolean) {
    return {
      kind: "switch",
      defaultOn: defaultFromHint(s.hint) ?? false,
      help: SWITCH_HELP[s.key] ?? switchHelp(s.hint),
    };
  }
  if (NUMBER_KEY.test(s.key)) return { kind: "number", min: 0, help: s.hint };
  if (SECRET_KEY.test(s.key)) return { kind: "password", help: s.hint };
  return { kind: "text" };
}

/** A saved value as people read it: On or Off for switches, the choice's label for lists. */
export function settingText(s: ConnectorSetting | undefined, value: string): string {
  if (!s) return value;
  const k = settingKind(s);
  if (k.kind === "switch") return value.toLowerCase() === "true" ? "On" : value.toLowerCase() === "false" ? "Off" : value;
  if (k.kind === "select") return k.choices.find((c) => c.value === value)?.label.replace(" (default)", "") ?? value;
  return value;
}
