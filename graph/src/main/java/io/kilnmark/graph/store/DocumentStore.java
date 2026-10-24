package io.kilnmark.graph.store;

import java.util.List;
import java.util.Optional;

import io.kilnmark.graph.store.DocumentRecords.Chunk;
import io.kilnmark.graph.store.DocumentRecords.Document;
import io.kilnmark.graph.store.DocumentRecords.Part;

/** Where Kilnmark keeps documents' profiles and text, and which assets sit inside which. */
public interface DocumentStore {

    /** Records that {@code urn} sits inside {@code parentUrn}; both must exist. */
    void setParent(String urn, String parentUrn);

    /** The asset {@code urn} sits inside, if any. */
    Optional<Part> parent(String urn);

    /** The assets inside {@code urn}, in name order: a workbook's sheets, a message's attachments. */
    List<Part> parts(String urn);

    /** Saves what reading a document found (replacing what was there) and marks the asset scanned. */
    void saveDocument(Document document, long runId);

    Optional<Document> document(String urn);

    /** Replaces a document's chunks with {@code chunks} (none: removes its text), in one transaction. */
    void replaceChunks(String urn, List<Chunk> chunks);

    /** Chunks in order, from {@code offset}. */
    List<Chunk> chunks(String urn, int offset, int limit);

    /** Chunks in order whose text overlaps characters {@code from} to {@code to} of the document. */
    List<Chunk> chunksBetween(String urn, int from, int to);

    /**
     * A document could not be read again for a passing reason: keeps what an earlier scan stored
     * (profile, chunks, and the assets inside it, which run {@code runId} counts as seen), and
     * records {@code note} in place of any earlier note of the same kind.
     *
     * @return false when nothing was stored before (the caller records the failure instead)
     */
    boolean keepStored(String urn, long runId, String note);

    /** Counts the assets of {@code kind} inside {@code urn} as seen by run {@code runId}, so they are kept. */
    void keepParts(String urn, long runId, String kind);

    /** Deletes the text of a document and of the assets inside it (keep_text turned off). */
    void dropText(String urn);

    /** Adds a note to a stored document. */
    void addNote(String urn, String note);
}
