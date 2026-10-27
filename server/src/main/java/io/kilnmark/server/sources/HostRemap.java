package io.kilnmark.server.sources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.kilnmark.kiln.connect.UrlHosts;

/**
 * Rewrites host names in source settings, from an administrator's map such as
 * {@code localhost=host.docker.internal}. Sources written down from a desktop's point of view
 * ({@code jdbc:postgresql://localhost:5432/sales}) then work from inside the container Kilnmark
 * runs in. Only whole host names are replaced, and only the host part of a URL (after
 * {@code ://}); bulk import applies it to address settings when asked to, never on its own.
 */
public final class HostRemap {

    /** The setting this map comes from, for messages. */
    public static final String SETTING = "kilnmark.sources.host-map";

    private static final Pattern HOST = Pattern.compile("\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9_]([A-Za-z0-9._-]{0,251}[A-Za-z0-9_])?|[0-9A-Fa-f:.]+");

    private final Map<String, String> map;

    private HostRemap(Map<String, String> map) {
        this.map = Map.copyOf(map);
    }

    /**
     * Reads {@code from=to} pairs.
     *
     * @throws IllegalArgumentException naming the pair that is not valid
     */
    public static HostRemap parse(List<String> pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : pairs == null ? List.<String>of() : pairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String from = eq < 0 ? "" : pair.substring(0, eq).trim();
            String to = eq < 0 ? "" : pair.substring(eq + 1).trim();
            if (!validHost(from) || !validHost(to)) {
                throw new IllegalArgumentException(SETTING + ": '" + pair.trim() + "' is not a pair of host names "
                    + "such as localhost=host.docker.internal");
            }
            map.put(from.toLowerCase(Locale.ROOT), to);
        }
        return new HostRemap(map);
    }

    /** Whether a string is a host name, an IPv4 address or an IPv6 address (bracketed or not). */
    public static boolean validHost(String host) {
        return host != null && !host.isEmpty() && host.length() <= 253 && HOST.matcher(host).matches();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** The host to use instead of {@code host}, or {@code host} itself when the map has none. */
    public String host(String host) {
        if (host == null) {
            return null;
        }
        String to = map.get(host.toLowerCase(Locale.ROOT));
        return to == null ? host : to;
    }

    /**
     * {@code value} with the host of every URL in it remapped: only host names, found as
     * {@link UrlHosts} finds them, so {@code jdbc:sqlserver://localhost;user=a@b} changes
     * {@code localhost} and nothing else. Bulk import applies this to a connector's address
     * settings only ({@link io.kilnmark.kiln.connect.Connector#addressKeys()}).
     */
    public String apply(String value) {
        if (value == null || map.isEmpty()) {
            return value;
        }
        return UrlHosts.replace(value, this::host);
    }

    @Override
    public String toString() {
        return "HostRemap" + map;
    }
}
