import {
  IconBell,
  IconBox,
  IconClockSearch,
  IconHandStop,
  IconHourglass,
  IconPlayerPlay,
  IconPlugConnected,
  IconRepeat,
  IconSql,
  type TablerIcon,
} from "@tabler/icons-react";

/**
 * Each flow node type's icon, in one place, so a node looks the same on the diagram and in a run's
 * timeline: a stop is a raised hand in both, never the tick a finished step might suggest.
 */
export const FLOW_NODE_ICONS: Record<string, TablerIcon> = {
  "run-pipeline": IconPlayerPlay,
  "for-each": IconRepeat,
  notify: IconBell,
  wait: IconHourglass,
  stop: IconHandStop,
  sql: IconSql,
  "check-source": IconPlugConnected,
  "wait-for": IconClockSearch,
};

/** A flow node type's icon (a plain box for a type it does not know). Decorative: the name beside it says what it is. */
export function FlowNodeIcon({ type, size = 16, className }: { type: string | null | undefined; size?: number; className?: string }) {
  const Svg = (type && FLOW_NODE_ICONS[type]) || IconBox;
  return <Svg size={size} stroke={1.75} className={className} aria-hidden="true" focusable="false" data-node-type={type ?? undefined} />;
}
