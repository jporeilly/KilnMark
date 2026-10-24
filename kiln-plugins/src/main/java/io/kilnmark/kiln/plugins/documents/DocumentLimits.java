package io.kilnmark.kiln.plugins.documents;

import java.time.Duration;

import io.kilnmark.kiln.connect.ConnectorContext;

/**
 * How much of a document Kilnmark reads, and how long it may take. Documents are untrusted input:
 * these limits keep one bad or hostile file from using up the server's memory, disk or time.
 *
 * @param maxBytes          documents larger than this are recorded but not read
 * @param maxCharacters     text kept from one document; the rest is not read
 * @param timeout           time one document may take to read; after it, the read is abandoned
 * @param maxDepth          how deep documents inside documents (attachments, embedded files) are read
 * @param maxEmbedded       how many documents inside one document are read
 * @param threads           documents read at the same time across the server
 * @param chunkCharacters   the size a chunk of text aims for
 * @param chunkOverlap      characters a chunk repeats from the end of the one before
 * @param maxLegacyBytes    legacy Office files (.doc, .xls, .ppt, .msg) are read whole into memory,
 *                          so they have a lower limit
 */
public record DocumentLimits(long maxBytes, int maxCharacters, Duration timeout, int maxDepth, int maxEmbedded,
                             int threads, int chunkCharacters, int chunkOverlap, long maxLegacyBytes) {

    /** Server settings, under {@code kilnmark.documents.}. */
    public static final String MAX_FILE_MB = "kilnmark.documents.max-file-mb";
    public static final String MAX_CHARACTERS = "kilnmark.documents.max-characters";
    public static final String TIMEOUT_SECONDS = "kilnmark.documents.timeout-seconds";
    public static final String MAX_DEPTH = "kilnmark.documents.max-depth";
    public static final String THREADS = "kilnmark.documents.threads";
    public static final String CHUNK_CHARACTERS = "kilnmark.documents.chunk-characters";
    public static final String CHUNK_OVERLAP = "kilnmark.documents.chunk-overlap";
    public static final String MAX_LEGACY_MB = "kilnmark.documents.max-legacy-file-mb";

    /** The limits used when the server sets none. */
    public static final DocumentLimits DEFAULTS = new DocumentLimits(100L << 20, 1_000_000, Duration.ofSeconds(60), 3,
        100, 2, 1500, 150, 30L << 20);

    public DocumentLimits {
        if (maxBytes <= 0 || maxCharacters <= 0 || timeout.isNegative() || timeout.isZero() || maxDepth < 0
            || maxEmbedded < 0 || threads < 1 || chunkCharacters < 100 || chunkOverlap < 0
            || chunkOverlap >= chunkCharacters || maxLegacyBytes <= 0) {
            throw new IllegalArgumentException("Document limits out of range: " + maxBytes + " bytes, "
                + maxCharacters + " characters, " + timeout + ", depth " + maxDepth + ", " + threads + " threads, "
                + "chunks of " + chunkCharacters + " overlapping " + chunkOverlap);
        }
    }

    /** The limits the server's settings give, falling back to {@link #DEFAULTS}. */
    public static DocumentLimits of(ConnectorContext context) {
        DocumentLimits d = DEFAULTS;
        return new DocumentLimits(
            (long) context.integer(MAX_FILE_MB, (int) (d.maxBytes >> 20)) << 20,
            context.integer(MAX_CHARACTERS, d.maxCharacters),
            Duration.ofSeconds(context.integer(TIMEOUT_SECONDS, (int) d.timeout.toSeconds())),
            context.integer(MAX_DEPTH, d.maxDepth),
            d.maxEmbedded,
            context.integer(THREADS, d.threads),
            context.integer(CHUNK_CHARACTERS, d.chunkCharacters),
            context.integer(CHUNK_OVERLAP, d.chunkOverlap),
            (long) context.integer(MAX_LEGACY_MB, (int) (d.maxLegacyBytes >> 20)) << 20);
    }

    /** Megabytes, for messages. */
    static String mb(long bytes) {
        return (bytes >> 20) + " MB";
    }
}
