package io.kilnmark.kiln.plugins.files;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.hardwood.InputFile;

/**
 * Where a source's files live: a folder or a bucket. Paths are relative to it and use {@code /}.
 * One is opened for each catalogue or read and closed after; it is used by one thread at a time,
 * except that Parquet files may be read from several threads.
 */
public interface FileStore extends Closeable {

    /**
     * One file: its path within the store, its size in bytes, when it last changed and, where the
     * store reports them, its owner and entity tag (S3's object owner and ETag); null when not known.
     */
    record Entry(String path, long size, Instant modified, String owner, String etag) {

        public Entry(String path, long size, Instant modified) {
            this(path, size, modified, null, null);
        }

        public Entry(String path, long size, Instant modified, String owner) {
            this(path, size, modified, owner, null);
        }
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

    /**
     * The file on this server's disk, when the store is a folder; null for remote stores, whose
     * files are copied before a document is read. Checked as {@link #open} checks paths.
     */
    default Path localPath(String path) throws IOException {
        return null;
    }

    /**
     * The file's tags (S3 object tags), or empty. Never fails: tags the credentials may not read are
     * simply not there.
     */
    default Map<String, String> tags(String path) {
        return Map.of();
    }

    /**
     * What identifies a file's content across stores, for sharing a local copy of it: two stores
     * give the same key only for the same file seen with the same access.
     */
    default String copyKey(String path) {
        return getClass().getName() + "\n" + describe(path);
    }

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
