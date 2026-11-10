package io.kilnmark.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.quality.Sights;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.HttpMethod;

/**
 * Holds the access review's "What each role can do" ({@link RoleTable}) to the server's own role
 * checks, as written in {@code @RolesAllowed}: every endpoint a line names exists; a line checked
 * only there asks exactly its role; a line checked in the code too asks no more of the endpoint than
 * the line says; and every endpoint that takes more than the viewer role is named by some line, so
 * a new one cannot be left out. {@code RoleTableProbeTest} then tries each line as every role.
 */
class RoleTableTest {

    /** Every endpoint of the server, as {@code METHOD /path}, with the least role it asks (null: none, open to all). */
    static Map<String, AccessRole> endpoints() throws IOException, URISyntaxException, ClassNotFoundException {
        Map<String, AccessRole> out = new TreeMap<>();
        ClassLoader loader = RoleTableTest.class.getClassLoader();
        Enumeration<URL> roots = loader.getResources("io/kilnmark/server");
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            // the server's own classes, never the tests'
            if (!"file".equals(root.getProtocol()) || root.getPath().contains("/test/")) {
                continue;
            }
            Path base = Path.of(root.toURI());
            List<Path> classes;
            try (Stream<Path> files = Files.walk(base)) {
                classes = files.filter(p -> p.toString().endsWith(".class")).toList();
            }
            for (Path p : classes) {
                String name = "io.kilnmark.server." + base.relativize(p).toString().replace('\\', '.').replace('/', '.')
                    .replaceAll("\\.class$", "");
                if (name.contains("$")) {
                    continue;
                }
                Class<?> c = Class.forName(name, false, loader);
                jakarta.ws.rs.Path classPath = c.getAnnotation(jakarta.ws.rs.Path.class);
                if (classPath == null) {
                    continue;
                }
                for (Method m : c.getDeclaredMethods()) {
                    String verb = verb(m);
                    if (verb == null) {
                        continue;
                    }
                    jakarta.ws.rs.Path methodPath = m.getAnnotation(jakarta.ws.rs.Path.class);
                    String path = "/" + strip(classPath.value()) + (methodPath == null || strip(methodPath.value()).isEmpty()
                        ? "" : "/" + strip(methodPath.value()));
                    out.put(verb + " " + path, least(c, m));
                }
            }
        }
        return out;
    }

    private static String strip(String path) {
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String verb(Method m) {
        for (var a : m.getAnnotations()) {
            HttpMethod h = a.annotationType().getAnnotation(HttpMethod.class);
            if (h != null) {
                return h.value();
            }
        }
        return null;
    }

    /** The least role an endpoint asks: its method's {@code @RolesAllowed}, else its class's; null when open. */
    private static AccessRole least(Class<?> c, Method m) {
        RolesAllowed r = m.getAnnotation(RolesAllowed.class);
        if (r == null && m.getAnnotation(PermitAll.class) == null) {
            r = c.getAnnotation(RolesAllowed.class);
        }
        if (r == null) {
            return null;
        }
        AccessRole lowest = null;
        for (String v : r.value()) {
            AccessRole role = AccessRole.parse(v);
            lowest = lowest == null || role.compareTo(lowest) < 0 ? role : lowest;
        }
        return lowest;
    }

    @Test
    void everyLineNamesRealEndpointsAskingTheRoleItSays() throws Exception {
        Map<String, AccessRole> all = endpoints();
        assertTrue(all.size() > 100, "the server's endpoints were found: " + all.size());
        Set<String> ids = new HashSet<>();
        for (RoleTable.Can line : RoleTable.LINES) {
            assertTrue(ids.add(line.id()), "one line for " + line.id());
            assertFalse(line.grant() && !line.inCode(), line.id() + ": the grant is checked in the code");
            assertFalse(line.grant() && !line.least().mayHoldUnmask(), line.id() + ": only a designer holds the grant");
            for (String e : line.endpoints()) {
                assertTrue(all.containsKey(e), line.id() + " names " + e + ", which the server has not got");
                AccessRole asked = all.get(e);
                assertTrue(asked != null, line.id() + ": " + e + " asks no role at all");
                if (line.inCode()) {
                    assertTrue(line.least().atLeast(asked), line.id() + ": " + e + " asks " + asked.id()
                        + ", more than the line's " + line.least().id());
                } else {
                    assertEquals(line.least(), asked, line.id() + ": " + e + " asks " + asked.id());
                }
            }
        }
    }

    @Test
    void everyEndpointAboveViewerIsNamedByALine() throws Exception {
        Set<String> named = new HashSet<>();
        RoleTable.LINES.forEach(l -> named.addAll(l.endpoints()));
        List<String> missing = new ArrayList<>();
        endpoints().forEach((e, role) -> {
            if (role != null && role != AccessRole.VIEWER && !named.contains(e)) {
                missing.add(e + " (" + role.id() + ")");
            }
        });
        assertEquals(List.of(), missing, "endpoints no line of the access review's table names");
    }

    @Test
    void theLinesSayWhatTheGrantAndTheRolesAllow() {
        RoleTable.Can data = RoleTable.LINES.stream().filter(l -> l.id().equals("data")).findFirst().orElseThrow();
        assertEquals(AccessRole.parse(Sights.DATA_ROLE), data.least(), "data values, as Sights decides");
        RoleTable.Can unmask = RoleTable.LINES.stream().filter(l -> l.id().equals("unmask")).findFirst().orElseThrow();
        assertEquals(Map.of("viewer", "no", "steward", "no", "designer", "grant", "admin", "no"), unmask.byRole(),
            "admins see values masked; only a designer with the grant sees them");
        RoleTable.Can tokens = RoleTable.LINES.stream().filter(l -> l.id().equals("tokens")).findFirst().orElseThrow();
        assertEquals(Map.of("viewer", "no", "steward", "no", "designer", "no", "admin", "yes"), tokens.byRole());
        // the ladder: each role may do all a lower one may
        for (RoleTable.Can l : RoleTable.LINES) {
            for (AccessRole r : AccessRole.values()) {
                for (AccessRole higher : AccessRole.values()) {
                    if (higher.atLeast(r) && l.allows(r, false)) {
                        assertTrue(l.allows(higher, false), l.id() + ": " + higher.id() + " may do what " + r.id() + " may");
                    }
                }
            }
        }
    }
}
