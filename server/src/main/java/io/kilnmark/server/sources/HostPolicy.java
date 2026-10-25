package io.kilnmark.server.sources;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * The hosts sources may connect to, from the administrator's {@code kilnmark.sources.allowed-hosts}:
 * exact host names or addresses, or {@code *.example.com} for any host in a domain. Empty (the
 * default) allows any host. Applied to every host a connector says it would reach
 * ({@link Connector#hosts}): JDBC URL hosts and the hosts their properties name, S3 endpoints (or
 * AWS's S3 host when there is none), whenever a source is added, edited, imported or checked, and
 * again immediately before every connection a scan, quality run, preview, listing, test or
 * on-demand read makes ({@link #refusal}), so a list narrowed since a source was saved holds at once.
 *
 * <p>Hosts are compared by name or address as written; what a name resolves to is not checked.
 */
public final class HostPolicy {

    /** The setting, for messages. */
    public static final String SETTING = "kilnmark.sources.allowed-hosts";

    private final List<String> allowed;

    public HostPolicy(List<String> allowed) {
        List<String> out = new ArrayList<>();
        for (String a : allowed == null ? List.<String>of() : allowed) {
            if (a != null && !a.isBlank()) {
                out.add(a.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.allowed = List.copyOf(out);
    }

    /** The administrator's list, from {@code kilnmark.sources.allowed-hosts}. */
    public static HostPolicy of(io.kilnmark.server.KilnmarkConfig config) {
        return new HostPolicy(config.sources().allowedHosts().orElse(List.of()));
    }

    /** Whether a host may be reached. */
    public boolean allows(String host) {
        if (allowed.isEmpty()) {
            return true;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        for (String a : allowed) {
            String bare = a.startsWith("[") && a.endsWith("]") ? a.substring(1, a.length() - 1) : a;
            if (bare.equals(h) || bare.startsWith("*.") && h.endsWith(bare.substring(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks every host the source would reach.
     *
     * @throws IllegalArgumentException naming the first host not allowed, for a 400 answer
     */
    public void check(Connector connector, SourceConfig source) {
        if (allowed.isEmpty()) {
            return;
        }
        for (String host : connector.hosts(source)) {
            if (!allows(host)) {
                String key = connector.addressKeys().isEmpty() ? "host" : connector.addressKeys().iterator().next();
                throw new IllegalArgumentException(key + ": this server only connects to the hosts its administrator "
                    + "allows, and " + host + " is not one of them. Ask an administrator to add it to " + SETTING);
            }
        }
    }

    /**
     * Why the saved source may not be reached now, in plain words for a job's detail or a check's
     * answer; null when every host it names is allowed. Called just before connecting.
     */
    public String refusal(Connector connector, SourceConfig source) {
        if (allowed.isEmpty()) {
            return null;
        }
        List<String> hosts;
        try {
            hosts = connector.hosts(source);
        } catch (RuntimeException e) {
            return "Kilnmark cannot tell which hosts source " + source.name() + " would reach (" + e.getMessage()
                + "), and this server only connects to the hosts its administrator allows. Edit the source.";
        }
        for (String host : hosts) {
            if (!allows(host)) {
                return "Source " + source.name() + " names " + host + ", which is not on the hosts this server's "
                    + "administrator allows sources to reach (" + SETTING + "), so Kilnmark did not connect. Ask an "
                    + "administrator to add it, or edit the source.";
            }
        }
        return null;
    }
}
