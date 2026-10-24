package io.kilnmark.server.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import io.kilnmark.server.ai.AiFeature.Purpose;

/**
 * A model an administrator added. The API key is not part of it: it stays encrypted in the
 * database, and only {@link ModelProfiles#apiKey} opens it, for the model client.
 *
 * @param local     the administrator asserts this model runs on a server inside their network
 * @param keyHint   the key's last four characters (only for keys of 16 or more), or null
 * @param version   goes up on every change; clients built from an older version are rebuilt
 */
public record ModelProfile(long id, String name, Provider provider, String baseUrl, String model, Purpose purpose,
                           Integer dimensions, Integer contextSize, boolean local, boolean hasKey, String keyHint,
                           int timeoutSeconds, int maxConcurrent, int version, String createdBy, Instant createdAt,
                           Instant updatedAt) {

    /** The address calls go to: the profile's own, or the provider's. */
    public String effectiveBaseUrl() {
        return baseUrl != null ? baseUrl : provider.defaultBaseUrl();
    }

    /** What an admin sends to add or change a profile. {@code apiKey}: null keeps it, empty removes it. */
    public record Input(String name, String provider, String baseUrl, String model, String purpose, Integer dimensions,
                        Integer contextSize, Boolean local, String apiKey, Integer timeoutSeconds,
                        Integer maxConcurrent) {
    }

    /** An input that passed every check, normalised. */
    public record Valid(String name, Provider provider, String baseUrl, String model, Purpose purpose,
                        Integer dimensions, Integer contextSize, boolean local, String apiKey, int timeoutSeconds,
                        int maxConcurrent) {
    }

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    /**
     * Checks an input. {@code keyStored} says whether the profile already has a key, for an update
     * that leaves the key out.
     *
     * @throws IllegalArgumentException with the first problem, in plain words
     */
    public static Valid validate(Input in, boolean keyStored) {
        if (in == null) {
            throw new IllegalArgumentException("Send the profile as JSON: name, provider, model and purpose at least");
        }
        String name = trim(in.name());
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "name: 1 to 63 lower-case letters, digits and hyphens, starting with a letter or digit");
        }
        Provider provider = Provider.parse(in.provider());
        Purpose purpose = Purpose.parse(in.purpose());
        String model = trim(in.model());
        if (model == null || model.length() > 200 || model.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("model: the model's name as the server knows it, for example "
                + "qwen2.5-1.5b-instruct or text-embedding-3-small");
        }
        String baseUrl = baseUrl(trim(in.baseUrl()), provider);
        if (purpose == Purpose.EMBEDDING && !provider.embeds()) {
            throw new IllegalArgumentException("purpose: " + provider.label() + " has no embedding models. "
                + "Choose another provider for embeddings, such as a local llama-server.");
        }
        Integer dims = in.dimensions();
        if (purpose == Purpose.EMBEDDING) {
            if (dims == null || dims < 1 || dims > 16000) {
                throw new IllegalArgumentException("dimensions: how many numbers the model returns per text, "
                    + "for example 384 or 768. Test the profile to find out.");
            }
        } else {
            dims = null;
        }
        Integer ctx = in.contextSize();
        if (ctx != null && (ctx < 256 || ctx > 10_000_000)) {
            throw new IllegalArgumentException("contextSize: the model's context window in tokens, 256 or more");
        }
        String key = in.apiKey();
        if (key != null && (key.length() > 4096 || key.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("apiKey: paste the key on its own, without line breaks");
        }
        boolean willHaveKey = key == null ? keyStored : !key.isBlank();
        if (provider.needsKey() && !willHaveKey) {
            throw new IllegalArgumentException("apiKey: " + provider.label() + " needs an API key");
        }
        int timeout = in.timeoutSeconds() == null ? 60 : in.timeoutSeconds();
        if (timeout < 1 || timeout > 600) {
            throw new IllegalArgumentException("timeoutSeconds: 1 to 600");
        }
        int concurrent = in.maxConcurrent() == null ? 2 : in.maxConcurrent();
        if (concurrent < 1 || concurrent > 64) {
            throw new IllegalArgumentException("maxConcurrent: 1 to 64 calls at the same time");
        }
        boolean local = Boolean.TRUE.equals(in.local());
        if (local && provider != Provider.OPENAI_COMPATIBLE) {
            throw new IllegalArgumentException("local: only an OpenAI-compatible server you run yourself can be "
                + "marked local; " + provider.label() + " is a hosted service");
        }
        return new Valid(name, provider, baseUrl, model, purpose, dims, ctx, local,
            key == null ? null : key.strip(), timeout, concurrent);
    }

    private static String baseUrl(String url, Provider provider) {
        if (url == null) {
            if (provider.defaultBaseUrl() == null) {
                throw new IllegalArgumentException("baseUrl: required for " + provider.label() + ", for example "
                    + (provider == Provider.AZURE_OPENAI ? "https://RESOURCE.openai.azure.com/openai/v1"
                        : "http://llama:8080/v1"));
            }
            return null;
        }
        URI u;
        try {
            u = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl: not a valid address");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || u.getHost() == null) {
            throw new IllegalArgumentException("baseUrl: an http:// or https:// address, for example http://llama:8080/v1");
        }
        if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl: no user name, password or query in the address; "
                + "put the key in apiKey");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** The last four characters of a long key; nothing for a short one, which they would mostly reveal. */
    public static String hint(String key) {
        return key == null || key.length() < 16 ? null : key.substring(key.length() - 4);
    }
}
