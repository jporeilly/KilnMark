package io.kilnmark.kiln.plugins.files;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import dev.hardwood.InputFile;

/**
 * Where a source's files live: a folder or a bucket. Paths are relative to it and use {@code /}.
 * One is opened for each catalogue or read and closed after; it is used by one thread at a time,
 * except that Parquet files may be read from several threads.
 */
public interface FileStore extends Closeable {

    /** One file: its path within the store, its size in bytes and when it last changed. */
    record Entry(String path, long size, Instant modified) {
    }

    /** Opens a store; called when a catalogue or read starts, on the thread that does it. */
    @FunctionalInterface
    interface Opener {
        FileStore open() throws IOException;
    }

    /** Every file to record, sorted by path. */
    List<Entry> list() throws IOException;

    /**
     * Every file to record, as {@link #list()}, but giving up with an {@link IOException} once
     * {@code deadline} has passed (null: no deadline). By default the deadline is not checked.
     */
    default List<Entry> list(java.time.Instant deadline) throws IOException {
        return list();
    }

    /**
     * One file by its path. By default found in {@link #list()}; stores override it with one lookup.
     *
     * @throws IOException when there is no such file
     */
    default Entry entry(String path) throws IOException {
        for (Entry e : list()) {
            if (e.path().equals(path)) {
                return e;
            }
        }
        throw new IOException("There is no file " + path + ": list the folder again");
    }

    /**
     * Checks the store can be read, as cheaply as it can: by default by listing it. Throws with a
     * message a person can act on.
     */
    default void probe() throws IOException {
        list();
    }

    /** Streams a whole file. Closing the stream before its end stops the transfer. */
    InputStream open(String path) throws IOException;

    /** At most the first {@code bytes} bytes of a file, without reading the rest. */
    byte[] head(String path, int bytes) throws IOException;

    /** A file for the Parquet reader, which reads the ranges it needs. */
    InputFile parquet(String path, long size) throws IOException;

    /** Where a path is, for people: an absolute path or an {@code s3://} URL. */
    String describe(String path);

    /**
     * Stops transfers still running, from any thread, and refuses new ones: a read that ends early
     * calls it before waiting for the Parquet reader's workers.
     */
    default void cancel() {
        // local files have nothing in flight worth stopping
    }

    @Override
    default void close() throws IOException {
        // most stores hold nothing open
    }
}
