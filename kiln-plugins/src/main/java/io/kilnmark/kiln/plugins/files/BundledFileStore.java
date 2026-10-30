package io.kilnmark.kiln.plugins.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.hardwood.InputFile;

/**
 * Files that ship inside Kilnmark, read from its own jar: Kilnmark's sample data. The files are
 * listed in {@code <root>files.list}, one path per line (a jar cannot be listed reliably), and
 * nothing outside that list is read. Read-only, and small enough to read whole.
 */
final class BundledFileStore implements FileStore {

    /** When the bundled files last changed: never, as far as a scan is concerned. */
    private static final Instant BUILT = Instant.EPOCH;

    private final String root;
    private final List<String> paths;

    BundledFileStore(String root) throws IOException {
        this.root = root;
        byte[] list = bytes(root + "files.list");
        this.paths = list == null ? List.of() : Arrays.stream(new String(list, StandardCharsets.UTF_8).split("\n"))
            .map(String::strip).filter(p -> !p.isEmpty() && !p.contains("..") && !p.startsWith("/"))
            .sorted().toList();
        if (paths.isEmpty()) {
            throw new IOException("This build of Kilnmark holds no sample data");
        }
    }

    @Override
    public List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String p : paths) {
            out.add(new Entry(p, read(p).length, BUILT));
        }
        return out;
    }

    @Override
    public InputStream open(String path) throws IOException {
        return new ByteArrayInputStream(read(path));
    }

    @Override
    public byte[] head(String path, int bytes) throws IOException {
        byte[] all = read(path);
        return all.length <= bytes ? all : Arrays.copyOf(all, bytes);
    }

    @Override
    public InputFile parquet(String path, long size) throws IOException {
        throw new IOException("The sample data holds no Parquet files");
    }

    @Override
    public String describe(String path) {
        return "Kilnmark's sample data: " + path;
    }

    @Override
    public void close() {
        // Nothing is held open.
    }

    private byte[] read(String path) throws IOException {
        if (!paths.contains(path)) {
            throw new IOException("There is no file " + path + " in Kilnmark's sample data");
        }
        byte[] b = bytes(root + path);
        if (b == null) {
            throw new IOException("Kilnmark's sample data is missing " + path + " from this build");
        }
        return b;
    }

    private static byte[] bytes(String resource) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = BundledFileStore.class.getClassLoader();
        }
        InputStream found = cl.getResourceAsStream(resource);
        if (found == null) {
            found = BundledFileStore.class.getClassLoader().getResourceAsStream(resource);
        }
        if (found == null) {
            return null;
        }
        try (InputStream in = found) {
            return in.readAllBytes();
        }
    }
}
