package io.kilnmark.server.starter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Starter Pack's five example people in the bundled Keycloak (ADR 0051), added and removed
 * through the realm's service account {@code kilnmark-starter}, which manages people and groups in
 * that realm and nothing else (containers/keycloak/setup.sh). Never Keycloak's admin.
 *
 * <p>Each person Kilnmark adds carries the attribute {@value #MARK}; only people carrying it are
 * ever changed or deleted, so a real person who happens to have one of the addresses is left as
 * they are. The realm is the one sign-in uses (the issuer's {@code /realms/<name>}), reached at the
 * issuer's own address, which must be a host the server may reach ({@code kilnmark.sources.allowed-hosts});
 * redirects are never followed. Errors say what went wrong and what to do, never an address or a secret.
 */
public final class KeycloakPeople {

    /** The attribute that marks a person as one of the Starter Pack's. */
    public static final String MARK = "kilnmark_starter_pack";

    /** One example person: their address (also their user name), name and the role their group gives. */
    public record Person(String email, String firstName, String lastName, String role, boolean unmask) {
    }

    /** The five, one per role, as setup.sh's demonstration list: kilnmark.com receives no mail from here. */
    public static final List<Person> PEOPLE = List.of(
        new Person("vera.lane@kilnmark.com", "Vera", "Lane", "viewer", false),
        new Person("sam.okafor@kilnmark.com", "Sam", "Okafor", "steward", false),
        new Person("dana.reyes@kilnmark.com", "Dana", "Reyes", "designer", false),
        new Person("priya.shah@kilnmark.com", "Priya", "Shah", "designer", true),
        new Person("alex.morgan@kilnmark.com", "Alex", "Morgan", "admin", false));

    /**
     * Whether a sign-in name is a kilnmark.com example account, for the access review: as the audit
     * log records it, {@code sso:<user name>} and the provider's ID for them in brackets.
     */
    public static boolean isExample(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT).strip();
        if (n.startsWith("sso:")) {
            n = n.substring(4);
        }
        int space = n.indexOf(' ');
        if (space >= 0) {
            n = n.substring(0, space); // the provider's ID, " (...)"
        }
        return n.endsWith("@kilnmark.com");
    }

    /** What happened to one person: added, updated, removed, absent, or left (not the pack's), with why. */
    public record Outcome(String email, String role, String outcome, String reason) {
    }

    /** Something Keycloak refused or did not answer, in words fit for an admin. */
    public static final class Failed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failed(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http;
    private final URI origin;
    private final String realm;
    private final String clientId;
    private final String secret;
    private final Map<String, String> groups;
    private final Duration timeout = Duration.ofSeconds(20);

    /**
     * @param issuer   the sign-in issuer, {@code http(s)://host[:port]/realms/<realm>}
     * @param groups   the group for each role (viewer, steward, designer, admin) and for {@code unmask}
     */
    public KeycloakPeople(URI issuer, String clientId, String secret, Map<String, String> groups) {
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
        this.origin = URI.create(issuer.getScheme() + ":" + "/" + "/" + issuer.getRawAuthority());
        this.realm = realmOf(issuer);
        this.clientId = clientId;
        this.secret = secret;
        this.groups = Map.copyOf(groups);
    }

    /** The realm an issuer names: its path is {@code /realms/<name>}; else null. */
    public static String realmOf(URI issuer) {
        String path = issuer.getPath() == null ? "" : issuer.getPath().replaceAll("/+$", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/realms/([A-Za-z0-9._-]+)").matcher(path);
        return m.find() && m.end() == path.length() ? m.group(1) : null;
    }

    /** A new password for the example people: 24 letters, digits, - and _, from a secure source. */
    public static String newPassword() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Adds the five, or brings back the ones that are the pack's, with {@code password} and their groups. */
    public List<Outcome> add(String password) {
        String token = token();
        Map<String, String> groupIds = new LinkedHashMap<>();
        List<Outcome> out = new ArrayList<>();
        for (Person p : PEOPLE) {
            JsonNode user = find(token, p.email());
            String id;
            String outcome;
            if (user == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("username", p.email());
                body.put("email", p.email());
                body.put("emailVerified", true);
                body.put("firstName", p.firstName());
                body.put("lastName", p.lastName());
                body.put("enabled", true);
                body.put("attributes", Map.of(MARK, List.of("true")));
                HttpResponse<String> r = send(token, "POST", "/users", body);
                if (r.statusCode() == 409) {
                    out.add(new Outcome(p.email(), p.role(), "left", "someone else has this address in Keycloak: left "
                        + "as they are"));
                    continue;
                }
                expect(r, 201, "add " + p.email());
                user = find(token, p.email());
                if (user == null) {
                    throw new Failed("Keycloak did not show " + p.email() + " after adding it. Try again.");
                }
                outcome = "added";
            } else if (!marked(user)) {
                out.add(new Outcome(p.email(), p.role(), "left", "a person with this address is in Keycloak but was "
                    + "not added by the Starter Pack: left as they are"));
                continue;
            } else {
                outcome = "updated";
            }
            id = user.path("id").asText();
            expect(send(token, "PUT", "/users/" + id + "/reset-password",
                Map.of("type", "password", "temporary", false, "value", password)), 204, "set the password of " + p.email());
            for (String g : groupsOf(p)) {
                String gid = groupIds.computeIfAbsent(g, name -> groupId(token, name));
                expect(send(token, "PUT", "/users/" + id + "/groups/" + gid, null), 204, "put " + p.email() + " in " + g);
            }
            out.add(new Outcome(p.email(), p.role(), outcome, null));
        }
        return out;
    }

    /** Deletes the five, each only if it carries the pack's mark. */
    public List<Outcome> remove() {
        String token = token();
        List<Outcome> out = new ArrayList<>();
        for (Person p : PEOPLE) {
            JsonNode user = find(token, p.email());
            if (user == null) {
                out.add(new Outcome(p.email(), p.role(), "absent", null));
            } else if (!marked(user)) {
                out.add(new Outcome(p.email(), p.role(), "left", "not added by the Starter Pack: left as they are"));
            } else {
                expect(send(token, "DELETE", "/users/" + user.path("id").asText(), null), 204, "remove " + p.email());
                out.add(new Outcome(p.email(), p.role(), "removed", null));
            }
        }
        return out;
    }

    /** The pack's people now in Keycloak, by address. */
    public List<String> present() {
        String token = token();
        List<String> out = new ArrayList<>();
        for (Person p : PEOPLE) {
            JsonNode user = find(token, p.email());
            if (user != null && marked(user)) {
                out.add(p.email());
            }
        }
        return out;
    }

    private List<String> groupsOf(Person p) {
        List<String> out = new ArrayList<>();
        out.add(group(p.role()));
        if (p.unmask()) {
            out.add(group("unmask"));
        }
        return out;
    }

    private String group(String key) {
        String g = groups.get(key);
        if (g == null || g.isBlank()) {
            throw new Failed("No group gives the " + key + " role: set KILNMARK_OIDC_" + key.toUpperCase(Locale.ROOT)
                + "_GROUPS in config/.env");
        }
        return g;
    }

    static boolean marked(JsonNode user) {
        JsonNode v = user.path("attributes").path(MARK);
        return v.isArray() && v.size() > 0 && "true".equals(v.get(0).asText());
    }

    private JsonNode find(String token, String username) {
        HttpResponse<String> r = send(token, "GET", "/users?exact=true&briefRepresentation=false&username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8), null);
        expect(r, 200, "look up " + username);
        JsonNode list = parse(r.body());
        for (JsonNode u : list) {
            if (username.equalsIgnoreCase(u.path("username").asText())) {
                return u;
            }
        }
        return null;
    }

    private String groupId(String token, String name) {
        HttpResponse<String> r = send(token, "GET", "/groups?exact=true&briefRepresentation=true&search="
            + URLEncoder.encode(name, StandardCharsets.UTF_8), null);
        expect(r, 200, "look up the group " + name);
        for (JsonNode g : parse(r.body())) {
            if (name.equals(g.path("name").asText())) {
                return g.path("id").asText();
            }
        }
        throw new Failed("There is no group " + name + " in Keycloak's realm " + realm + ": run the control "
            + "script's up again, which makes the groups");
    }

    /** A token for the service account, by its client credentials (sent in the body, never the address). */
    String token() {
        String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(origin.resolve("/realms/" + realm + "/protocol/openid-connect/token"))
            .timeout(timeout).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> r = exchange(req);
        if (r.statusCode() == 400 || r.statusCode() == 401) {
            throw new Failed("Keycloak refused the Starter Pack's service account (" + clientId + "): run the control "
                + "script's up again with KILNMARK_STARTER=allowed in config/.env, which makes it and sets its secret");
        }
        expect(r, 200, "sign in as the Starter Pack's service account");
        String token = parse(r.body()).path("access_token").asText("");
        if (token.isEmpty()) {
            throw new Failed("Keycloak gave the Starter Pack's service account no token. Try again.");
        }
        return token;
    }

    private HttpResponse<String> send(String token, String method, String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(origin.resolve("/admin/realms/" + realm + path)).timeout(timeout)
            .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        try {
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
        } catch (IOException e) {
            throw new Failed("Could not write the request to Keycloak.");
        }
        return exchange(b.build());
    }

    private HttpResponse<String> exchange(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Failed("Keycloak did not answer. Check that it runs (the control script's status), then try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("Stopped while waiting for Keycloak.");
        }
    }

    private void expect(HttpResponse<String> r, int status, String what) {
        if (r.statusCode() == status) {
            return;
        }
        if (r.statusCode() == 403) {
            throw new Failed("Keycloak did not let the Starter Pack's service account " + what + ": it may manage "
                + "people and groups only. Run the control script's up again, which gives it those roles");
        }
        throw new Failed("Keycloak could not " + what + " (HTTP " + r.statusCode() + "). Try again.");
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body == null ? "" : body);
        } catch (IOException e) {
            throw new Failed("Keycloak answered with something that is not JSON.");
        }
    }
}
