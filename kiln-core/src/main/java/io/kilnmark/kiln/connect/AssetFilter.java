package io.kilnmark.kiln.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which of a source's assets to record: the containers chosen (schemas, top-level folders) and
 * the {@value #INCLUDE} and {@value #EXCLUDE} patterns. One helper for every connector: connectors
 * use it while reading their catalogue, to skip work early, and the server applies it again to
 * whatever a catalogue returns, so a connector that ignores it still scans only what was chosen.
 *
 * <p>Patterns are globs, matched ignoring case: {@code *} is any run of characters except
 * {@code /}, {@code **} any run including {@code /} ({@code **}{@code /} also matches no folder at
 * all), {@code ?} one character except {@code /}, {@code [abc]} one of a set, and {@code {a,b}}
 * either. A pattern with a {@code /} is matched against the asset's whole path; any other against
 * its name and its whole path, so {@code cust*} matches the table {@code crm.customers}, and
 * {@code crm.*} every table in {@code crm}. A setting holds several patterns separated by commas or
 * semicolons (commas inside braces belong to the pattern). An asset is kept when it matches an
 * include pattern (or there are none) and no exclude pattern.
 */
public final class AssetFilter {

    /** Setting: patterns of assets to keep; empty keeps every asset. */
    public static final String INCLUDE = "include";
    /** Setting: patterns of assets to leave out. */
    public static final String EXCLUDE = "exclude";

    /** Keeps everything. */
    public static final AssetFilter ALL = new AssetFilter(List.of(), List.of(), List.of());

    private final List<String> containers;
    private final List<Glob> include;
    private final List<Glob> exclude;

    /** A compiled pattern; {@code path} when it holds a {@code /} and so is matched against whole paths only. */
    private record Glob(Pattern pattern, boolean path) {
    }

    private AssetFilter(List<String> containers, List<Glob> include, List<Glob> exclude) {
        this.containers = List.copyOf(containers);
        this.include = List.copyOf(include);
        this.exclude = List.copyOf(exclude);
    }

    /**
     * The filter a source's settings describe: containers from the connector's
     * {@link Connector#containerKind() container setting}, patterns from {@value #INCLUDE} and
     * {@value #EXCLUDE}.
     *
     * @throws IllegalArgumentException when a pattern is not a valid glob
     */
    public static AssetFilter of(Connector connector, SourceConfig source) {
        return of(source.list(connector.containerKind().setting()), source.setting(INCLUDE), source.setting(EXCLUDE));
    }

    /** A filter from its parts; null or empty patterns keep everything. */
    public static AssetFilter of(List<String> containers, String include, String exclude) {
        return new AssetFilter(containers == null ? List.of() : containers, compile(INCLUDE, include),
            compile(EXCLUDE, exclude));
    }

    /** This filter with every container chosen: for listing what a source holds. */
    public AssetFilter allContainers() {
        return new AssetFilter(List.of(), include, exclude);
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
        if (!include.isEmpty() && include.stream().noneMatch(p -> matches(p, path, name))) {
            return false;
        }
        return exclude.stream().noneMatch(p -> matches(p, path, name));
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

    private static boolean matches(Glob g, String path, String name) {
        String full = path == null ? "" : path;
        if (g.path()) {
            return g.pattern().matcher(full).matches();
        }
        return (name != null && g.pattern().matcher(name).matches()) || g.pattern().matcher(full).matches();
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
        List<Glob> out = new ArrayList<>();
        for (String glob : split(value)) {
            out.add(new Glob(glob(key, glob), glob.contains("/")));
        }
        return out;
    }

    /**
     * A glob as a regular expression matching whole strings, ignoring case.
     *
     * @throws IllegalArgumentException naming {@code key} when the glob is not valid
     */
    static Pattern glob(String key, String glob) {
        StringBuilder re = new StringBuilder();
        int braces = 0;
        boolean inClass = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                    re.append(']');
                } else if (c == '\\' || c == '[' || c == '&' || c == '^' && re.charAt(re.length() - 1) != '[') {
                    re.append('\\').append(c);
                } else if (c == '!' && re.charAt(re.length() - 1) == '[') {
                    re.append('^');
                } else {
                    re.append(c);
                }
                continue;
            }
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                            re.append("(?:.*/)?");
                        } else {
                            re.append(".*");
                        }
                    } else {
                        re.append("[^/]*");
                    }
                }
                case '?' -> re.append("[^/]");
                case '[' -> {
                    if (glob.indexOf(']', i + 2) < 0) {
                        throw new IllegalArgumentException(key + ": '" + glob + "' opens a [ set without closing it");
                    }
                    inClass = true;
                    re.append('[');
                }
                case '{' -> {
                    braces++;
                    re.append("(?:");
                }
                case '}' -> {
                    if (braces == 0) {
                        throw new IllegalArgumentException(key + ": '" + glob + "' closes a } it never opened");
                    }
                    braces--;
                    re.append(')');
                }
                case ',' -> re.append(braces > 0 ? "|" : ",");
                default -> {
                    if ("\\.^$|+()".indexOf(c) >= 0) {
                        re.append('\\');
                    }
                    re.append(c);
                }
            }
        }
        if (braces > 0 || inClass) {
            throw new IllegalArgumentException(key + ": '" + glob + "' is not closed; check its { } and [ ]");
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    @Override
    public String toString() {
        return "AssetFilter[containers=" + containers + ", include=" + include.size() + ", exclude=" + exclude.size()
            + "]";
    }
}
