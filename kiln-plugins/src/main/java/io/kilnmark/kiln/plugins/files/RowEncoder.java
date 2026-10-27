package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.util.List;

import org.apache.arrow.vector.VectorSchemaRoot;

/** Turns Arrow batches into a file's bytes: CSV or Parquet. The caller owns the stream and the batches. */
interface RowEncoder {

    /** Encodes one batch. */
    void write(VectorSchemaRoot batch) throws IOException;

    /** Writes what is still buffered and the file's end (Parquet's footer); the stream stays open. */
    void finish() throws IOException;

    /** The type each column is written as, for the written asset's schema. */
    List<String> types();
}
