import { useEffect, useId, useState, type KeyboardEvent } from "react";
import { allows, ApiError, variablesApi } from "../../api";
import { useRole } from "../../components/role";
import type { DeclaredParameter } from "./model";
import { parameterSummary, variableSummary } from "./parameters";

type VariableState = Parameters<typeof variableSummary>[1];

/**
 * Server variables' values, kept a minute: a chip asks only for the ones the pipeline names, and a
 * value changed meanwhile shows within a minute.
 */
const variableCache = new Map<string, { at: number; value: Promise<VariableState> }>();
const VARIABLE_KEPT_MS = 60_000;

function readVariable(name: string): Promise<VariableState> {
  const kept = variableCache.get(name);
  let p = kept && Date.now() - kept.at < VARIABLE_KEPT_MS ? kept.value : undefined;
  if (!p) {
    p = variablesApi
      .get(name)
      .then((v): VariableState => ({ state: "known", value: v.value }))
      .catch((e: unknown): VariableState => {
        variableCache.delete(name); // set later, say: asked again next time
        if (e instanceof ApiError && e.status === 404) return { state: "unset" };
        if (e instanceof ApiError && (e.status === 401 || e.status === 403)) return { state: "hidden" };
        return { state: "failed" };
      });
    variableCache.set(name, { at: Date.now(), value: p });
  }
  return p;
}

/** For tests: forget the values read. */
export const forgetVariables = () => variableCache.clear();

/**
 * A server variable's value, for those who may see server variables (stewards and above, as
 * GET /api/v1/variables allows); the rest are told who sees it, and nothing is asked.
 */
function useVariable(name: string | null, mayRead: boolean): VariableState {
  const [state, setState] = useState<{ name: string; value: VariableState } | null>(null);
  useEffect(() => {
    if (!name || !mayRead) return;
    let live = true;
    void readVariable(name).then((value) => live && setState({ name, value }));
    return () => {
      live = false;
    };
  }, [name, mayRead]);
  if (!mayRead) return { state: "hidden" };
  return state && state.name === name ? state.value : { state: "loading" };
}

/** What a chip for `${name}` says: a declared parameter's type, default and description, or a server variable. */
export function useChipWords(name: string, declared: DeclaredParameter[]): string {
  const role = useRole();
  const variable = name.startsWith("vars.") ? name.slice("vars.".length) : null;
  const value = useVariable(variable, allows(role, "steward"));
  if (variable != null) return variableSummary(variable, value);
  const p = declared.find((d) => d.name === name);
  return p
    ? parameterSummary(p)
    : `${name}: not a parameter this pipeline declares. Add it under Parameters in the pipeline's settings (select no node).`;
}

/**
 * A `${name}` chip: a button that puts the name into an expression (`onPick`), or, without one,
 * shows what it is when pressed. Either way its details (type, default, description; for a server
 * variable, what it is and its value for those who may see it) show beside it on hover and on
 * keyboard focus, and are its accessible description, so a screen reader reads them too. Escape
 * hides them.
 */
export function ParameterChip({
  name,
  declared,
  onPick,
}: {
  name: string;
  declared: DeclaredParameter[];
  onPick?: (word: string) => void;
}) {
  const tipId = `cv-tip-${useId().replace(/:/g, "")}`;
  const [hover, setHover] = useState(false);
  const [focus, setFocus] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const [pinned, setPinned] = useState(false);
  const words = useChipWords(name, declared);
  const open = !dismissed && (hover || focus || pinned);
  const label = `\${${name}}`;
  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === "Escape" && open) {
      // Hides the details only: the canvas's own Escape (clear the selection) waits for the next one.
      e.stopPropagation();
      setDismissed(true);
      setPinned(false);
    }
  };
  return (
    <span
      className="cv-chip-wrap"
      onMouseEnter={() => {
        setHover(true);
        setDismissed(false);
      }}
      onMouseLeave={() => setHover(false)}
    >
      <button
        type="button"
        className="cv-chip cv-chip-param mono"
        aria-describedby={tipId}
        onClick={() => {
          if (onPick) {
            onPick(label);
            return;
          }
          setPinned((v) => !v);
          setDismissed(false);
        }}
        onFocus={() => {
          setFocus(true);
          setDismissed(false);
        }}
        onBlur={() => {
          setFocus(false);
          setPinned(false);
        }}
        onKeyDown={onKeyDown}
      >
        {label}
      </button>
      <span role="tooltip" id={tipId} className="cv-tip" hidden={!open}>
        {words}
      </span>
    </span>
  );
}

/**
 * The parameters and server variables a setting puts in, as chips that say what each is: shown
 * under a setting that names any, for everyone, whether or not they may change it.
 */
export function ParameterChips({ names, declared, label }: { names: string[]; declared: DeclaredParameter[]; label: string }) {
  if (names.length === 0) return null;
  return (
    <div className="cv-column-buttons cv-param-used" role="group" aria-label={label}>
      <span className="small muted">Values put in:</span>
      {names.map((n) => (
        <ParameterChip key={n} name={n} declared={declared} />
      ))}
    </div>
  );
}
