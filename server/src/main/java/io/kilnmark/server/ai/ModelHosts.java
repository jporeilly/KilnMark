package io.kilnmark.server.ai;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * What a model's address may be (ADR 0016). Two operator rules, checked when a profile is saved
 * and again, with a fresh name lookup, before every call:
 * <ul>
 *   <li>While {@code kilnmark.ai.hosted-models=forbidden}, a profile counts as local only if its
 *       host is an IP address inside one of the networks in {@code kilnmark.ai.local-hosts}
 *       (default: loopback and private networks), or a host name listed there explicitly (default:
 *       {@code localhost}, and in a container also {@code host.containers.internal} and
 *       {@code host.docker.internal}). Names are not resolved to decide this, so a name that
 *       resolves to a private address does not count unless the operator listed it. The two
 *       container host names are the exception: DNS decides where they go, so they count only
 *       while every address they resolve to is loopback, private or Podman's host address
 *       {@value #PODMAN_HOST_ADDRESS}. The administrator's "local" tick alone is not enough. A
 *       reverse proxy on a local address that forwards to a hosted service cannot be detected.</li>
 *   <li>Link-local addresses (169.254.0.0/16, fe80::/10) and cloud metadata names are refused,
 *       unless {@code kilnmark.ai.allow-metadata-addresses=true}, so a profile cannot be pointed at
 *       a cloud provider's metadata service. One exception: rootless Podman 5.3 and later (pasta)
 *       maps {@code host.containers.internal} to {@value #PODMAN_HOST_ADDRESS}, so that address is
 *       accepted for the two container host names only, never typed as an address or reached
 *       through any other name.</li>
 * </ul>
 */
@ApplicationScoped
public class ModelHosts {

    /** Looks up a host name; injectable so tests need no DNS. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;

        Resolver SYSTEM = InetAddress::getAllByName;
    }

    /** Loopback and private networks, and {@code localhost}. */
    static final List<String> DEFAULT_LOCAL = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
        "192.168.0.0/16", "fc00::/7", "localhost");
    /**
     * The names container engines give the machine a container runs on: Podman's, and Docker's
     * (also set by Kilnmark's compose.yaml). Local by default only inside a container.
     */
    static final Set<String> CONTAINER_HOST_NAMES = Set.of("host.containers.internal", "host.docker.internal");
    /** Where rootless Podman 5.3+ (pasta, {@code --map-guest-addr}) puts the host. */
    static final String PODMAN_HOST_ADDRESS = "169.254.1.2";
    /** How long a name lookup may take before the address counts as unreachable. */
    static final Duration LOOKUP_LIMIT = Duration.ofSeconds(2);

    private static final List<Cidr> METADATA_NETWORKS = List.of(Cidr.parse("169.254.0.0/16"),
        Cidr.parse("fe80::/10"), Cidr.parse("100.100.100.200/32"), Cidr.parse("fd00:ec2::254/128"));
    private static final List<Cidr> PRIVATE_NETWORKS = List.of(Cidr.parse("127.0.0.0/8"), Cidr.parse("::1/128"),
        Cidr.parse("10.0.0.0/8"), Cidr.parse("172.16.0.0/12"), Cidr.parse("192.168.0.0/16"), Cidr.parse("fc00::/7"));
    private static final Set<String> METADATA_NAMES = Set.of("metadata", "metadata.google.internal", "metadata.goog",
        "instance-data", "instance-data.ec2.internal", "metadata.azure.com");

    private final List<Cidr> localNetworks = new ArrayList<>();
    private final List<String> localNames = new ArrayList<>();
    private final boolean lock;
    private final boolean allowMetadata;
    private final boolean inContainer;
    private final Resolver resolver;

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile(
        "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");

    /**
     * @throws IllegalArgumentException naming an entry of {@code kilnmark.ai.local-hosts} that is
     *         neither a network, an IP address nor a host name (the server does not start)
     */
    @Inject
    public ModelHosts(KilnmarkConfig config) {
        this(config, Resolver.SYSTEM, runningInContainer());
    }

    ModelHosts(KilnmarkConfig config, Resolver resolver, boolean inContainer) {
        this.resolver = resolver;
        this.inContainer = inContainer;
        List<String> defaults = new ArrayList<>(DEFAULT_LOCAL);
        if (inContainer) {
            defaults.addAll(CONTAINER_HOST_NAMES.stream().sorted().toList());
        }
        // Unset or empty: the default networks.
        for (String entry : config.ai().localHosts().orElse(defaults)) {
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

    /**
     * Whether Kilnmark runs in a container. {@code KILNMARK_IN_CONTAINER} decides when set (the
     * image sets it to true); otherwise Docker's /.dockerenv, Podman's /run/.containerenv, or
     * {@code container} in the environment (systemd and Podman images).
     */
    static boolean runningInContainer() {
        String set = System.getenv("KILNMARK_IN_CONTAINER");
        if (set != null && !set.isBlank()) {
            return Boolean.parseBoolean(set.strip());
        }
        return Files.exists(Path.of("/run/.containerenv")) || Files.exists(Path.of("/.dockerenv"))
            || System.getenv("container") != null;
    }

    /** Whether Kilnmark runs in a container, where {@code localhost} is the container itself. */
    public boolean inContainer() {
        return inContainer;
    }

    /** The host of a base URL, lower case, without brackets or a trailing dot. */
    public static String host(String baseUrl) {
        String h = baseUrl == null ? null : URI.create(baseUrl).getHost();
        if (h == null) {
            return "";
        }
        h = h.toLowerCase(Locale.ROOT);
        h = h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
        return h.replaceAll("\\.+$", "");
    }

    /**
     * Whether a profile may count as local: always while the operator lock is off (the admin's
     * word), otherwise only when its host is one of the local hosts. A container host name is
     * looked up (at most {@link #LOOKUP_LIMIT}); one that resolves to a public address does not
     * count, one that does not resolve in time does (the call then fails as unreachable, rather
     * than being refused).
     */
    public boolean mayBeLocal(String baseUrl) {
        if (!lock) {
            return true;
        }
        String host = host(baseUrl);
        if (!listedLocal(host)) {
            return false;
        }
        if (!CONTAINER_HOST_NAMES.contains(host)) {
            return true;
        }
        Optional<InetAddress[]> addresses = resolveWithin(host, LOOKUP_LIMIT);
        return addresses.isEmpty() || mayBeLocal(host, addresses.get());
    }

    /** {@link #mayBeLocal(String)} with addresses already looked up. */
    boolean mayBeLocal(String host, InetAddress[] addresses) {
        if (!lock) {
            return true;
        }
        if (!listedLocal(host)) {
            return false;
        }
        return !CONTAINER_HOST_NAMES.contains(host) || hostSide(addresses);
    }

    /** Whether every address is loopback, private, or Podman's host address: this machine or its own network. */
    static boolean hostSide(InetAddress[] addresses) {
        if (addresses.length == 0) {
            return false;
        }
        for (InetAddress a : addresses) {
            if (!a.getHostAddress().equals(PODMAN_HOST_ADDRESS) && PRIVATE_NETWORKS.stream().noneMatch(n -> n.contains(a))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the operator lock ({@code kilnmark.ai.hosted-models=forbidden}) is on. */
    public boolean locked() {
        return lock;
    }

    /** Local by configuration alone, with no name lookup: a listed name, or an IP literal in a listed network. */
    boolean listedLocal(String host) {
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

    /**
     * The addresses of a host. IP addresses in any form a resolver might accept (dotted quad,
     * one 32-bit number, {@code a.b} or {@code a.b.c}, hexadecimal or octal parts, IPv4-mapped
     * IPv6) are parsed here, so a metadata address cannot slip through written differently.
     */
    InetAddress[] resolve(String host) throws UnknownHostException {
        Optional<InetAddress> v4 = numericIpv4(host);
        if (v4.isPresent()) {
            return new InetAddress[] {v4.get()};
        }
        if (host.contains(":")) {
            return new InetAddress[] {InetAddress.getByName(host)}; // a literal; mapped addresses come back as IPv4
        }
        return resolver.resolve(host);
    }

    /** {@link #resolve} within {@code limit}; empty when the name is unknown or the lookup is too slow. */
    Optional<InetAddress[]> resolveWithin(String host, Duration limit) {
        CompletableFuture<InetAddress[]> lookup = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().name("kilnmark-host-lookup").start(() -> {
            try {
                lookup.complete(resolve(host));
            } catch (Throwable e) {
                lookup.completeExceptionally(e);
            }
        });
        try {
            return Optional.of(lookup.get(Math.max(1, limit.toMillis()), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            t.interrupt();
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        }
    }

    /**
     * An IPv4 address written the BSD way: up to four parts separated by dots, each decimal,
     * {@code 0x} hexadecimal or {@code 0} octal, the last part filling the remaining bytes
     * ({@code 2852039166}, {@code 169.254.43518} and {@code 0xa9.0xfe.0xa9.0xfe} are all
     * 169.254.169.254). Empty for anything else.
     */
    static Optional<InetAddress> numericIpv4(String host) {
        if (!host.matches("(?i)(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+)){0,3}")) {
            return Optional.empty();
        }
        String[] parts = host.split("\\.");
        long[] n = new long[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].toLowerCase(Locale.ROOT);
                n[i] = p.startsWith("0x") ? Long.parseLong(p.substring(2), 16)
                    : p.length() > 1 && p.startsWith("0") ? Long.parseLong(p.substring(1), 8) : Long.parseLong(p);
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        long value = 0;
        for (int i = 0; i < n.length - 1; i++) {
            if (n[i] > 255) {
                return Optional.empty();
            }
            value = value << 8 | n[i];
        }
        int restBits = 8 * (4 - (n.length - 1));
        long last = n[n.length - 1];
        if (last >= 1L << restBits) {
            return Optional.empty();
        }
        value = value << restBits | last;
        byte[] b = {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        try {
            return Optional.of(InetAddress.getByAddress(b));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** Why a base URL may not be used (a metadata or link-local address), or empty. */
    public Optional<String> forbidden(String baseUrl) {
        if (allowMetadata) {
            return Optional.empty();
        }
        String host = host(baseUrl);
        if (METADATA_NAMES.contains(host)) {
            return Optional.of(METADATA_REASON);
        }
        try {
            return forbidden(host, resolve(host));
        } catch (UnknownHostException e) {
            return Optional.empty(); // cannot be reached at all; the call itself will say so
        }
    }

    private static final String METADATA_REASON = "This address is a link-local or cloud metadata address, which model "
        + "profiles may not use. Whoever runs the server can allow it with kilnmark.ai.allow-metadata-addresses=true.";

    /** {@link #forbidden(String)} with addresses already looked up. */
    Optional<String> forbidden(String host, InetAddress[] addresses) {
        if (allowMetadata) {
            return Optional.empty();
        }
        if (METADATA_NAMES.contains(host)) {
            return Optional.of(METADATA_REASON);
        }
        for (InetAddress a : addresses) {
            InetAddress v = mappedToIpv4(a);
            if (METADATA_NETWORKS.stream().noneMatch(n -> n.contains(v))) {
                continue;
            }
            // Rootless Podman's host address, reached by the name Podman gives it: the host, not a metadata service.
            if (v.getHostAddress().equals(PODMAN_HOST_ADDRESS) && CONTAINER_HOST_NAMES.contains(host)) {
                continue;
            }
            return Optional.of(METADATA_REASON);
        }
        return Optional.empty();
    }

    /** An IPv4-mapped IPv6 address as IPv4 (Java usually does this already); others unchanged. */
    private static InetAddress mappedToIpv4(InetAddress a) {
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
                try {
                    return Inet4Address.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
                } catch (UnknownHostException e) {
                    return a;
                }
            }
        }
        return a;
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
