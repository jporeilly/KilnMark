package io.kilnmark.server.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The text embedded for an asset: its kind, place and name, its description and its fields with
 * their types and descriptions. Never values: no samples, no profile statistics.
 */
public final class AssetText {

    private AssetText() {
    }

    /** A field as it goes into the text. */
    public record Field(String name, String type, String description) {
    }

    /**
     * Characters sent when the profile gives no context size: a 512-token context, the smallest in
     * common use (bge-small, for one). llama-server refuses a longer input rather than cutting it.
     */
    static final int DEFAULT_MAX_CHARS = 768;

    /** The text for one asset, cut to {@code maxChars}. */
    public static String of(String source, String sourceDescription, String kind, String container, String name,
                            String description, List<Field> fields, int maxChars) {
        StringBuilder b = new StringBuilder();
        b.append(kind).append(' ');
        if (container != null && !container.isBlank()) {
            b.append(container).append('.');
        }
        b.append(name).append(" (").append(words(name)).append(")\n");
        b.append("Source: ").append(source);
        if (sourceDescription != null && !sourceDescription.isBlank()) {
            b.append(", ").append(oneLine(sourceDescription));
        }
        b.append('\n');
        if (description != null && !description.isBlank()) {
            b.append("Description: ").append(oneLine(description)).append('\n');
        }
        if (!fields.isEmpty()) {
            b.append("Fields: ");
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                if (i > 0) {
                    b.append("; ");
                }
                b.append(f.name());
                if (f.type() != null && !f.type().isBlank()) {
                    b.append(" (").append(f.type()).append(')');
                }
                if (f.description() != null && !f.description().isBlank()) {
                    b.append(": ").append(oneLine(f.description()));
                }
            }
            b.append('\n');
        }
        String text = b.toString().strip();
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    /**
     * How much text a profile can take: one and a half characters per token of its context, at most
     * 30,000. Asset text is mostly identifiers and types, which tokenise at about 1.8 characters
     * per token (measured with bge-small's tokenizer), so this leaves room; llama-server answers an
     * input longer than its context with an error, not a cut.
     */
    public static int maxChars(ModelProfile p) {
        return p.contextSize() == null ? DEFAULT_MAX_CHARS : (int) Math.min(30_000L, p.contextSize() * 3L / 2);
    }

    /** customer_orders and customerOrders read as "customer orders", which embeds better. */
    static String words(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[_\\-.]+", " ").toLowerCase(java.util.Locale.ROOT)
            .strip();
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    /** What decides whether an asset must be embedded again: the text, the model and its dimensions. */
    static byte[] hash(String text, ModelProfile p) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update((p.provider().id() + "\n" + p.effectiveBaseUrl() + "\n" + p.model() + "\n" + p.dimensions() + "\n")
                .getBytes(StandardCharsets.UTF_8));
            return d.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
