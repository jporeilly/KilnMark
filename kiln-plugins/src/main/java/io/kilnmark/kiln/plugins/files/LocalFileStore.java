package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import dev.hardwood.InputFile;

/**
 * Files in a folder on a disk or share the server can read.
 *
 * <p>Nothing outside the folder is ever listed or read: paths with {@code ..} or that are absolute
 * are refused, and so are symbolic links that lead out of the folder. Links that stay inside it
 * are followed. Hidden files and folders (names starting with a dot) are skipped.
 */
public final class LocalFileStore implements FileStore {

    private final Path root;
    private final PathMatcher include;
    private final boolean includeWholePath;
    private final boolean recursive;

    /**
     * @param folder    the folder; must exist and be readable
     * @param include   a glob such as {@code *.csv} (matched against file names) or
     *                  {@code exports/**} (matched against paths, because it has a {@code /}); null for all
     * @param recursive whether to look in sub-folders
     */
    public LocalFileStore(Path folder, String include, boolean recursive) throws IOException {
        if (!folder.isAbsolute()) {
            throw new IOException("The folder " + folder + " is not an absolute path: give the full path, for example /srv/share");
        }
        try {
            this.root = folder.toRealPath();
        } catch (NoSuchFileException e) {
            throw new IOException("The folder " + folder + " does not exist on this server: check the path");
        }
        if (!Files.isDirectory(root)) {
            throw new IOException(folder + " is a file, not a folder: give the folder that holds the files");
        }
        if (!Files.isReadable(root)) {
            throw new IOException("This server cannot read the folder " + folder
                + ": give the user the server runs as read access to it");
        }
        this.include = include == null || include.isBlank() ? null
            : root.getFileSystem().getPathMatcher("glob:" + include.strip());
        this.includeWholePath = include != null && include.contains("/");
        this.recursive = recursive;
    }

    /** The folder, with links resolved. */
    public Path root() {
        return root;
    }

    @Override
    public List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        EnumSet<FileVisitOption> options = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
        Files.walkFileTree(root, options, recursive ? Integer.MAX_VALUE : 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (hidden(dir) || !inside(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || hidden(file) || !inside(file)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = relative(file);
                if (include != null && !include.matches(includeWholePath ? Path.of(rel) : file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                out.add(new Entry(rel, attrs.size(), attrs.lastModifiedTime().toInstant()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // unreadable entries, link loops and files deleted while listing are skipped, not fatal
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparing(Entry::path));
        return out;
    }

    private static boolean hidden(Path p) {
        Path name = p.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    /** Whether a path, once links are resolved, is still inside the folder. */
    private boolean inside(Path p) {
        try {
            return p.toRealPath().startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    /**
     * The file at {@code path}, after checking it is inside the folder.
     *
     * @throws IOException when the path leads outside the folder, or the file does not exist
     */
    public Path resolve(String path) throws IOException {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains("\0")) {
            throw new IOException("Refusing to read '" + path + "': give a path relative to " + root);
        }
        for (String part : path.split("[/\\\\]")) {
            if (part.equals("..")) {
                throw new IOException("Refusing to read '" + path + "': it leads outside the folder " + root);
            }
        }
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root)) {
            throw new IOException("Refusing to read '" + path + "': it is outside the folder " + root);
        }
        Path real;
        try {
            real = p.toRealPath();
        } catch (NoSuchFileException e) {
            throw new IOException("The file " + p + " no longer exists: scan the source again to update the catalogue");
        }
        if (!real.startsWith(root)) {
            throw new IOException("Refusing to read '" + path + "': it is a link to " + real
                + ", outside the folder " + root);
        }
        return real;
    }

    @Override
    public InputStream open(String path) throws IOException {
        return Files.newInputStream(resolve(path));
    }

    @Override
    public Path localPath(String path) throws IOException {
        return resolve(path);
    }

    @Override
    public byte[] head(String path, int bytes) throws IOException {
        try (InputStream in = open(path)) {
            return in.readNBytes(bytes);
        }
    }

    @Override
    public InputFile parquet(String path, long size) throws IOException {
        return new LocalInputFile(resolve(path));
    }

    @Override
    public String describe(String path) {
        return root.resolve(path).toString();
    }

    /**
     * A local file read with positional reads rather than Hardwood's memory mapping, so memory use
     * stays in proportion to what is read and nothing is left mapped after a read stops early.
     */
    private static final class LocalInputFile implements InputFile {

        private final Path file;
        private FileChannel channel;

        LocalInputFile(Path file) {
            this.file = file;
        }

        @Override
        public synchronized void open() throws IOException {
            if (channel == null) {
                channel = FileChannel.open(file, StandardOpenOption.READ);
            }
        }

        @Override
        public ByteBuffer readRange(long offset, int length) throws IOException {
            open();
            FileChannel channel = channel();
            ByteBuffer buf = ByteBuffer.allocate(length);
            while (buf.hasRemaining()) {
                int n = channel.read(buf, offset + buf.position());
                if (n < 0) {
                    throw new IOException("Unexpected end of " + file + " at byte " + (offset + buf.position()));
                }
            }
            return buf.flip();
        }

        @Override
        public long length() throws IOException {
            open();
            return channel().size();
        }

        private synchronized FileChannel channel() {
            return channel;
        }

        @Override
        public String name() {
            return file.getFileName().toString();
        }

        @Override
        public synchronized void close() throws IOException {
            if (channel != null) {
                channel.close();
                channel = null;
            }
        }
    }
}
