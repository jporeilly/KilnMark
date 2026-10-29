package io.kilnmark.kiln.yaml;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-level keys with an older name that is still read: {@code steps} for {@code nodes} (pipelines
 * and flows) and {@code paths} for {@code connections} (flows). Files written before the rename keep
 * loading; what Kilnmark writes uses the new names.
 */
final class Renamed {

    /** Each old name, by the new name it stands for. */
    static final Map<String, String> OLD = Map.of("nodes", "steps", "connections", "paths");

    private Renamed() {
    }

    /**
     * The entry for {@code key}, or for its old name when only that is written. Both written is a
     * problem, reported at the old one, and the new one is read.
     */
    static YamlReader.Entry entry(YamlReader.Mapping top, String key, String what, List<Problem> problems) {
        YamlReader.Entry current = top.entries().get(key);
        String old = OLD.get(key);
        YamlReader.Entry before = old == null ? null : top.entries().get(old);
        if (before == null) {
            return current;
        }
        if (current != null) {
            problems.add(Problem.at(before.keyPosition(), null, "'" + old + "' is the old name of '" + key + "', and this "
                + what + " has both: keep " + key));
            return current;
        }
        return before;
    }

    /** Whether {@code key} is the old name of one of {@code keys}, so still read where those are. */
    static boolean isOld(String key, Set<String> keys) {
        return keys.stream().anyMatch(k -> key.equals(OLD.get(k)));
    }
}
