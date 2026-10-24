package io.kilnmark.server.sources;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites host names in source settings, from an administrator's map such as
 * {@code localhost=host.docker.internal}. Sources written down from a desktop's point of view
 * ({@code jdbc:postgresql://localhost:5433/lab}) then work from inside the container Kilnmark
 * runs in. Only whole host names are replaced, and only the host part of a URL (after
 * {@code ://}); bulk import applies it when asked to, never on its own.
 */
public final class HostRemap {

    /** The setting this map comes from, for messages. */
    public static final String SETTING = "kilnmark.sources.host-map";

    /** The host of a URL: after {@code ://} and any user name, up to a port, path, parameter or instance name. */
    private static final Pattern URL_HOST = Pattern.compile("://(?:[^/@\\s]*@)?(\\[[^\\]/]*\\]|[^:/;?#\\\\\\s@\\[]+)");
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

    /** {@code value} with the host of every URL in it remapped. */
    public String apply(String value) {
        if (value == null || map.isEmpty() || !value.contains("://")) {
            return value;
        }
        Matcher m = URL_HOST.matcher(value);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String host = m.group(1);
            String to = host(host);
            out.append(value, last, m.start(1)).append(to);
            last = m.end(1);
        }
        return out.append(value.substring(last)).toString();
    }

    /** Settings with every URL's host remapped; the same map when nothing changed. */
    public Map<String, String> apply(Map<String, String> settings) {
        Map<String, String> out = new HashMap<>(settings);
        out.replaceAll((k, v) -> apply(v));
        return out;
    }

    @Override
    public String toString() {
        return "HostRemap" + map;
    }
}
