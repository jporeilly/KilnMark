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
}
