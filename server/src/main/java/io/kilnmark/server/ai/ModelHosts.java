package io.kilnmark.server.ai;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * What a model's address may be (ADR 0016). Two operator rules, checked when a profile is saved
 * and again, with a fresh name lookup, before every call:
 * <ul>
 *   <li>While {@code kilnmark.ai.hosted-models=forbidden}, a profile counts as local only if its
 *       host is an IP address inside one of the networks in {@code kilnmark.ai.local-hosts}
 *       (default: loopback and private networks), or a host name listed there explicitly (default:
 *       {@code localhost} and the container host names {@code host.containers.internal} and
 *       {@code host.docker.internal}). Names
 *       are never resolved for this, so a name that resolves to a private address does not count
 *       unless the operator listed it. The administrator's "local" tick alone is not enough. A
 *       reverse proxy on a local address that forwards to a hosted service cannot be detected.</li>
 *   <li>Link-local addresses (169.254.0.0/16, fe80::/10) and cloud metadata names are refused,
 *       unless {@code kilnmark.ai.allow-metadata-addresses=true}, so a profile cannot be pointed at
 *       a cloud provider's metadata service.</li>
 * </ul>
 */
@ApplicationScoped
public class ModelHosts {

    /**
     * Loopback and private networks, {@code localhost}, and the names container engines give the
     * machine a container runs on: {@code host.containers.internal} (Podman) and
     * {@code host.docker.internal} (Docker, and Kilnmark's compose.yaml). Like every name here,
     * they are matched as written, never resolved.
     */
    static final List<String> DEFAULT_LOCAL = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
        "192.168.0.0/16", "fc00::/7", "localhost", "host.containers.internal", "host.docker.internal");
    private static final List<Cidr> METADATA_NETWORKS = List.of(Cidr.parse("169.254.0.0/16"),
        Cidr.parse("fe80::/10"), Cidr.parse("100.100.100.200/32"), Cidr.parse("fd00:ec2::254/128"));
    private static final Set<String> METADATA_NAMES = Set.of("metadata", "metadata.google.internal", "metadata.goog",
        "instance-data", "instance-data.ec2.internal", "metadata.azure.com");

    private final List<Cidr> localNetworks = new ArrayList<>();
    private final List<String> localNames = new ArrayList<>();
    private final boolean lock;
    private final boolean allowMetadata;

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile(
        "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");

    /**
     * @throws IllegalArgumentException naming an entry of {@code kilnmark.ai.local-hosts} that is
     *         neither a network, an IP address nor a host name (the server does not start)
     */
    public ModelHosts(KilnmarkConfig config) {
        // Unset or empty: the default networks.
        for (String entry : config.ai().localHosts().orElse(DEFAULT_LOCAL)) {
            String e = entry.strip().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) {
                continue;
            }
            if (e.contains("/") || isIpLiteral(e)) {
                localNetworks.add(Cidr.parse(e.contains("/") ? e : e + (e.contains(":") ? "/128" : "/32")));
            } else if (NAME.matcher(e).matches()) {
                localNames.add(e);
            } else {
                throw new IllegalArgumentException("kilnmark.ai.local-hosts: '" + entry.strip() + "' is not a network "
                    + "(10.0.0.0/8), an IP address or a host name");
            }
        }
        this.lock = config.ai().hostedModels() == KilnmarkConfig.Ai.HostedModels.FORBIDDEN;
        this.allowMetadata = config.ai().allowMetadataAddresses();
    }

    /** The host of a base URL, lower case, without brackets. */
    public static String host(String baseUrl) {
        String h = baseUrl == null ? null : URI.create(baseUrl).getHost();
        if (h == null) {
            return "";
        }
        h = h.toLowerCase(Locale.ROOT);
        return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
    }

    /**
     * Whether a profile may count as local: always while the operator lock is off (the admin's
     * word), otherwise only when its host is one of the local hosts.
     */
    public boolean mayBeLocal(String baseUrl) {
        return !lock || isLocalHost(host(baseUrl));
    }

    /** Whether the operator lock ({@code kilnmark.ai.hosted-models=forbidden}) is on. */
    public boolean locked() {
        return lock;
    }

    /** Local by configuration alone, with no name lookup: a listed name, or an IP literal in a listed network. */
    boolean isLocalHost(String host) {
        if (host.isEmpty()) {
            return false;
        }
        if (localNames.contains(host)) {
            return true;
        }
        if (!isIpLiteral(host)) {
            return false;
        }
        try {
            InetAddress a = InetAddress.getByName(host); // a literal: parsed, not looked up
            return localNetworks.stream().anyMatch(n -> n.contains(a));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** An IPv4 dotted quad or an IPv6 address (no brackets). */
    static boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.contains(":") && host.matches("[0-9a-f:.%]+");
    }

    /**
     * Where a stored key may go: scheme, host and port, with the scheme's default port filled in.
     * A key is kept on edit only while this is unchanged.
     */
    public static String origin(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        URI u = URI.create(baseUrl);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        int port = u.getPort() >= 0 ? u.getPort() : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + host(baseUrl) + ":" + port;
    }

    /** Why a base URL may not be used (a metadata or link-local address), or empty. */
    public Optional<String> forbidden(String baseUrl) {
        if (allowMetadata) {
            return Optional.empty();
        }
        String host = host(baseUrl);
        String reason = "This address is a link-local or cloud metadata address, which model profiles may not use. "
            + "Whoever runs the server can allow it with kilnmark.ai.allow-metadata-addresses=true.";
        if (METADATA_NAMES.contains(host)) {
            return Optional.of(reason);
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (METADATA_NETWORKS.stream().anyMatch(n -> n.contains(a))) {
                    return Optional.of(reason);
                }
            }
        } catch (UnknownHostException e) {
            // cannot be reached at all; the call itself will say so
        }
        return Optional.empty();
    }

    /** A network in CIDR form, IPv4 or IPv6. */
    record Cidr(byte[] network, int bits) {
        static Cidr parse(String text) {
            int slash = text.indexOf('/');
            try {
                byte[] addr = InetAddress.getByName(text.substring(0, slash)).getAddress();
                int bits = Integer.parseInt(text.substring(slash + 1));
                if (bits < 0 || bits > addr.length * 8) {
                    throw new IllegalArgumentException("kilnmark.ai.local-hosts: bad prefix length in " + text);
                }
                return new Cidr(addr, bits);
            } catch (UnknownHostException | NumberFormatException | StringIndexOutOfBoundsException e) {
                throw new IllegalArgumentException("kilnmark.ai.local-hosts: not a network: " + text, e);
            }
        }

        boolean contains(InetAddress a) {
            byte[] b = a.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (b[i] != network[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xff << (8 - rest) & 0xff;
            return (b[full] & mask) == (network[full] & mask);
        }
    }
}
