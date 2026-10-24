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
 *       host is in {@code kilnmark.ai.local-hosts}: by name, or with every address it resolves to
 *       inside one of the listed networks (default: loopback and private networks). The
 *       administrator's "local" tick alone is not enough.</li>
 *   <li>Link-local addresses (169.254.0.0/16, fe80::/10) and cloud metadata names are refused,
 *       unless {@code kilnmark.ai.allow-metadata-addresses=true}, so a profile cannot be pointed at
 *       a cloud provider's metadata service.</li>
 * </ul>
 */
@ApplicationScoped
public class ModelHosts {

    static final List<String> DEFAULT_LOCAL = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
        "192.168.0.0/16", "fc00::/7", "localhost");
    private static final List<Cidr> METADATA_NETWORKS = List.of(Cidr.parse("169.254.0.0/16"),
        Cidr.parse("fe80::/10"), Cidr.parse("100.100.100.200/32"), Cidr.parse("fd00:ec2::254/128"));
    private static final Set<String> METADATA_NAMES = Set.of("metadata", "metadata.google.internal", "metadata.goog",
        "instance-data", "instance-data.ec2.internal", "metadata.azure.com");

    private final List<Cidr> localNetworks = new ArrayList<>();
    private final List<String> localNames = new ArrayList<>();
    private final boolean lock;
    private final boolean allowMetadata;

    public ModelHosts(KilnmarkConfig config) {
        for (String entry : config.ai().localHosts().orElse(DEFAULT_LOCAL)) {
            String e = entry.strip().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) {
                continue;
            }
            if (e.contains("/") || Character.digit(e.charAt(0), 16) >= 0 && (e.contains(":") || e.matches("[0-9.]+"))) {
                localNetworks.add(Cidr.parse(e.contains("/") ? e : e + (e.contains(":") ? "/128" : "/32")));
            } else {
                localNames.add(e);
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

    boolean isLocalHost(String host) {
        if (host.isEmpty()) {
            return false;
        }
        if (localNames.contains(host)) {
            return true;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return false;
        }
        for (InetAddress a : addresses) {
            if (localNetworks.stream().noneMatch(n -> n.contains(a))) {
                return false;
            }
        }
        return addresses.length > 0;
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
