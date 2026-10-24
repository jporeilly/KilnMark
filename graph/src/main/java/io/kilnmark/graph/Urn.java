package io.kilnmark.graph;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A Kilnmark asset identifier: {@code urn:kilnmark:<source>:<path>}.
 *
 * @param source the data source's short name, for example {@code pg}
 * @param path   the asset's path within that source, for example {@code crm.customers}
 */
public record Urn(String source, String path) {

    private static final String PREFIX = "urn:kilnmark:";
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    public Urn {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(path, "path");
        if (!SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("Invalid source name: " + source);
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
    }

    /** Parses a URN such as {@code urn:kilnmark:pg:crm.customers}. */
    public static Urn parse(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a Kilnmark URN: " + text);
        }
        String rest = text.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("URN has no path: " + text);
        }
        return new Urn(rest.substring(0, colon), rest.substring(colon + 1));
    }

    @Override
    public String toString() {
        return PREFIX + source + ":" + path;
    }
}
