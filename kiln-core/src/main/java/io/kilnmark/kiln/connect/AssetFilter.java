package io.kilnmark.kiln.connect;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of a source's assets to record: the containers chosen (schemas, top-level folders) and
 * the {@value #INCLUDE} and {@value #EXCLUDE} patterns. One helper for every connector: connectors
 * use it while reading their catalogue, to skip work early, and the server applies it again to
 * whatever a catalogue returns, so a connector that ignores it still scans only what was chosen.
 *
 * <p>Patterns are globs ({@code Glob}), matched ignoring case without backtracking. Each kind of
 * source has a path separator ({@link Connector.ContainerKind#separator()}): {@code .} for
 * databases ({@code schema.table}), {@code /} for files. A pattern holding the separator is matched
 * against the asset's whole path; any other against its name only. So for a database {@code cust*}
 * matches the table {@code customers} in any schema and {@code crm.*} every table in {@code crm};
 * for files {@code *.csv} matches file names and {@code raw/**} paths. A setting holds up to
 * {@value #MAX_PATTERNS} patterns separated by commas or semicolons (commas inside braces belong
 * to the pattern). An asset is kept when it matches an include pattern (or there are none) and no
 * exclude pattern.
 */
public final class AssetFilter {

    /** Setting: patterns of assets to keep; empty keeps every asset. */
    public static final String INCLUDE = "include";
    /** Setting: patterns of assets to leave out. */
    public static final String EXCLUDE = "exclude";
    /** The most patterns one setting may hold. */
    public static final int MAX_PATTERNS = 100;

    /** Keeps everything. */
    public static final AssetFilter ALL = new AssetFilter(List.of(), List.of(), List.of(), "/");

    private final List<String> containers;
    private final List<Glob> include;
    private final List<Glob> exclude;
    private final String separator;

    private AssetFilter(List<String> containers, List<Glob> include, List<Glob> exclude, String separator) {
        this.containers = List.copyOf(containers);
        this.include = List.copyOf(include);
        this.exclude = List.copyOf(exclude);
        this.separator = separator;
    }

    /**
     * The filter a source's settings describe: containers from the connector's
     * {@link Connector#containerKind() container setting}, patterns from {@value #INCLUDE} and
     * {@value #EXCLUDE}, matched with the connector's path separator.
     *
     * @throws IllegalArgumentException when a pattern is not a valid glob
     */
    public static AssetFilter of(Connector connector, SourceConfig source) {
        Connector.ContainerKind kind = connector.containerKind();
        return of(source.list(kind.setting()), source.setting(INCLUDE), source.setting(EXCLUDE), kind.separator());
    }

    /** A filter from its parts, for paths separated by {@code /}; null or empty patterns keep everything. */
    public static AssetFilter of(List<String> containers, String include, String exclude) {
        return of(containers, include, exclude, "/");
    }

    /** A filter from its parts; {@code separator} divides an asset's path ({@code .} or {@code /}). */
    public static AssetFilter of(List<String> containers, String include, String exclude, String separator) {
        return new AssetFilter(containers == null ? List.of() : containers, compile(INCLUDE, include),
            compile(EXCLUDE, exclude), separator == null || separator.isEmpty() ? "/" : separator);
    }

    /** This filter with every container chosen: for listing what a source holds. */
    public AssetFilter allContainers() {
        return new AssetFilter(List.of(), include, exclude, separator);
    }

    /** The containers chosen; empty means every one. */
    public List<String> containers() {
        return containers;
    }

    /**
     * Whether a container is chosen: always when none are named; otherwise when one is named
     * exactly so, or, failing that, differs only in case.
     */
    public boolean container(String name) {
        if (containers.isEmpty()) {
            return true;
        }
        String n = name == null ? "" : name;
        return containers.contains(n) || containers.stream().anyMatch(c -> c.equalsIgnoreCase(n));
    }

    /** Whether the patterns keep an asset with this path and name. */
    public boolean patterns(String path, String name) {
        if (!include.isEmpty() && include.stream().noneMatch(g -> matches(g, path, name))) {
            return false;
        }
        return exclude.stream().noneMatch(g -> matches(g, path, name));
    }

    /** Whether the filter keeps an asset in this container, with this path and name. */
    public boolean keeps(String container, String path, String name) {
        return container(container) && patterns(path, name);
    }

    /** The assets this filter keeps, with each one's container as the connector reports it. */
    public List<AssetInfo> apply(Connector connector, SourceConfig source, List<AssetInfo> assets) {
        if (containers.isEmpty() && include.isEmpty() && exclude.isEmpty()) {
            return assets;
        }
        List<AssetInfo> out = new ArrayList<>(assets.size());
        for (AssetInfo a : assets) {
            if (keeps(connector.containerOf(source, a), a.path(), a.name() == null ? a.path() : a.name())) {
                out.add(a);
            }
        }
        return out;
    }

    private boolean matches(Glob g, String path, String name) {
        if (g.text().contains(separator)) {
            return g.matches(path == null ? "" : path);
        }
        return g.matches(name != null ? name : path == null ? "" : path);
    }

    /**
     * Problems with the pattern settings of a source, each starting with the setting's key; empty
     * when both are valid.
     */
    public static List<String> problems(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        for (String key : List.of(INCLUDE, EXCLUDE)) {
            try {
                compile(key, source.setting(key));
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        return problems;
    }

    /** The patterns in a setting, split at commas and semicolons outside braces. */
    public static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            }
            if ((c == ',' || c == ';') && depth == 0) {
                add(out, b);
            } else {
                b.append(c);
            }
        }
        add(out, b);
        return out;
    }

    private static void add(List<String> out, StringBuilder b) {
        String s = b.toString().trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
        b.setLength(0);
    }

    private static List<Glob> compile(String key, String value) {
        List<String> patterns = split(value);
        if (patterns.size() > MAX_PATTERNS) {
            throw new IllegalArgumentException(key + ": at most " + MAX_PATTERNS + " patterns; this has "
                + patterns.size());
        }
        List<Glob> out = new ArrayList<>();
        for (String glob : patterns) {
            out.add(Glob.compile(key, glob));
        }
        return out;
    }

    @Override
    public String toString() {
        return "AssetFilter[containers=" + containers + ", include=" + include.size() + ", exclude=" + exclude.size()
            + "]";
    }
}
