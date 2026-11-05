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
 * ({@link Connector#hosts}): JDBC URL hosts and the hosts their properties name, and for S3 the host
 * the AWS SDK sends the bucket's requests to (ADR 0035), whenever a source is added, edited, imported or checked, and
 * again immediately before every connection a scan, quality run, preview, listing, test or
 * on-demand read makes ({@link #refusal}), so a list narrowed since a source was saved holds at once.
 *
 * <p>Hosts are compared by name or address as written; what a name resolves to is not checked.
 *
 * <p>With {@code kilnmark.sources.require-tls} on, the same checks also refuse a source whose
 * connections could go unencrypted, as its connector says ({@link Connector#unencryptedReason}),
 * so every place that checks hosts checks encryption too (ADR 0034).
 */
public final class HostPolicy {

    /** The setting, for messages. */
    public static final String SETTING = "kilnmark.sources.allowed-hosts";

    /** The setting that requires encrypted connections, for messages. */
    public static final String TLS_SETTING = "kilnmark.sources.require-tls";

    private final List<String> allowed;
    private final boolean requireTls;

    /** Hosts only, no encryption required: for tests. */
    public HostPolicy(List<String> allowed) {
        this(allowed, false);
    }

    public HostPolicy(List<String> allowed, boolean requireTls) {
        this.requireTls = requireTls;
        List<String> out = new ArrayList<>();
        for (String a : allowed == null ? List.<String>of() : allowed) {
            if (a != null && !a.isBlank()) {
                out.add(a.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.allowed = List.copyOf(out);
    }

    /** The administrator's settings: {@code kilnmark.sources.allowed-hosts} and {@code require-tls}. */
    public static HostPolicy of(io.kilnmark.server.KilnmarkConfig config) {
        return new HostPolicy(config.sources().allowedHosts().orElse(List.of()), config.sources().requireTls());
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
            if (bare.equals(h) || (bare.startsWith("*.") && h.endsWith(bare.substring(1)))) {
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
        if (requireTls) {
            String reason = connector.unencryptedReason(source);
            if (reason != null) {
                throw new IllegalArgumentException(reason + ". This server only connects to sources over encrypted "
                    + "connections (" + TLS_SETTING + ")");
            }
        }
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
     * Why the saved source may not be reached now because its connections could go unencrypted, or
     * null when encryption is not required or every connection is encrypted. Names a setting and its
     * value, never a host. Called just before connecting.
     */
    public String encryptionRefusal(Connector connector, SourceConfig source) {
        if (!requireTls) {
            return null;
        }
        String reason;
        try {
            reason = connector.unencryptedReason(source);
        } catch (RuntimeException e) {
            reason = "Kilnmark cannot tell whether its connections are encrypted";
        }
        return reason == null ? null : "Source " + source.name() + " could be reached over an unencrypted connection ("
            + reason + "), and this server only connects to sources over encrypted ones (" + TLS_SETTING + "), so "
            + "Kilnmark did not connect. Edit the source.";
    }

    /**
     * Why the saved source may not be reached now, in plain words for a job's detail or a check's
     * answer; null when every host it names is allowed. Called just before connecting.
     */
    public String refusal(Connector connector, SourceConfig source) {
        String unencrypted = encryptionRefusal(connector, source);
        if (unencrypted != null) {
            return unencrypted;
        }
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
