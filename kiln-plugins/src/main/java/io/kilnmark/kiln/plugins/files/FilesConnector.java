package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;

/**
 * Files in a folder the server can read: a local disk or a mounted share. Settings {@code path}
 * (the folder), {@code recursive} (default true) and {@code encoding}; which files to record comes
 * from the settings every source has ({@code include}, {@code exclude}, {@code containers}). Asset
 * paths are relative to the folder; nothing outside it is listed or read.
 *
 * <p>Whoever adds a source chooses the folder, but the server's administrator chooses which
 * folders may be chosen: {@code kilnmark.files.roots}, a comma-separated list of folders. A
 * source's folder must be one of them or inside one, checked with links resolved when the source
 * is validated and again each time it is opened. With no roots (the default) the connector reads
 * nothing, so adding Kilnmark to a server never exposes that server's own files.
 */
public final class FilesConnector extends FileSourceConnector {

    /** The server setting listing the folders sources may read. */
    public static final String ROOTS = "kilnmark.files.roots";

    static final String NO_ROOTS = "this server reads no folders: an administrator lists the folders sources may "
        + "read in the server setting " + ROOTS + " (KILNMARK_FILES_ROOTS)";

    private final List<Path> roots;

    /** The connector as the service loader creates it: no folders allowed until the server says so. */
    public FilesConnector() {
        this(List.of());
    }

    private FilesConnector(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    @Override
    public Connector withContext(ConnectorContext context) {
        ParquetRuntime.configure(context);
        DocumentRuntime.configure(context);
        List<Path> out = new ArrayList<>();
        for (String r : context.list(ROOTS)) {
            Path p;
            try {
                p = Path.of(r);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException(ROOTS + ": '" + r + "' is not a valid path", e);
            }
            if (!p.isAbsolute()) {
                throw new IllegalArgumentException(ROOTS + ": '" + r + "' is not a full path; write it from the root, "
                    + "for example /srv/share");
            }
            out.add(p.normalize());
        }
        return new FilesConnector(out);
    }

    /** The folders sources may read, as configured. */
    public List<Path> roots() {
        return roots;
    }

    @Override
    public String family() {
        return "local or network folder";
    }

    @Override
    public String icon() {
        return "folder";
    }

    @Override
    public String example(String settingKey) {
        return switch (settingKey) {
            case "path" -> "/srv/files/exports";
            case "delimiter" -> ";";
            case "encoding" -> "windows-1252";
            default -> null;
        };
    }

    @Override
    public String kind() {
        return "files";
    }

    @Override
    public String displayName() {
        return "Files in a folder";
    }

    /**
     * Folders under the administrator's roots ({@code kilnmark.files.roots}): Kilnmark opens no
     * network connection of its own. A root that is a mounted network share (NFS, SMB, a UNC path)
     * crosses the network as the operating system mounted it, so its encryption is the
     * administrator's to arrange (ADR 0034).
     */
    @Override
    public String unencryptedReason(SourceConfig source) {
        return null;
    }

    @Override
    public List<Setting> settings() {
        return List.of(
            new Setting("path", "Folder", true,
                "The full path of a folder this server can read, inside one of the folders its administrator "
                    + "allows" + (roots.isEmpty() ? "" : " (" + String.join(", ", roots.stream().map(Path::toString)
                    .toList()) + ")"), false),
            Setting.bool("recursive", "Include sub-folders", true, "true (the default) or false"),
            ENCODING, DELIMITER, HEADER, KEEP_TEXT, READ_TEXT_ON_DEMAND);
    }

    @Override
    public List<String> validate(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        String path = source.setting("path");
        if (roots.isEmpty()) {
            problems.add("path: " + NO_ROOTS);
        } else if (path == null) {
            problems.add("path: the full path of the folder, for example " + roots.get(0));
        } else {
            try {
                Path p = Path.of(path);
                if (!p.isAbsolute()) {
                    problems.add("path: a full path starting at the root, for example " + roots.get(0) + ", not '"
                        + path + "'");
                } else if (allowedRoot(p.normalize()) == null) {
                    problems.add("path: " + path + " is not inside a folder this server allows (" + allowed() + ")");
                } else if (Files.exists(p)) {
                    Path real = p.toRealPath();
                    if (allowedReal(real) == null) {
                        problems.add("path: " + path + " leads outside the folders this server allows ("
                            + allowed() + "), through a link");
                    }
                }
            } catch (InvalidPathException | IOException e) {
                problems.add("path: not a valid path (" + e.getMessage() + ")");
            }
        }
        checkBoolean(source, "recursive", problems);
        checkEncoding(source, problems);
        checkText(source, problems);
        checkBoolean(source, "keep_text", problems);
        checkBoolean(source, "read_text_on_demand", problems);
        checkReserved(source, problems);
        return problems;
    }

    private String allowed() {
        return String.join(", ", roots.stream().map(Path::toString).toList());
    }

    /** The root a normalised absolute path is under, or null. */
    private Path allowedRoot(Path p) {
        for (Path r : roots) {
            if (p.startsWith(r)) {
                return r;
            }
        }
        return null;
    }

    /** The root a real path is under, comparing against each root with its links resolved. */
    private Path allowedReal(Path real) {
        for (Path r : roots) {
            try {
                if (real.startsWith(r.toRealPath())) {
                    return r;
                }
            } catch (IOException e) {
                // a root that does not exist (yet) allows nothing
            }
        }
        return null;
    }

    @Override
    protected FileStore open(SourceConfig source) throws IOException {
        if (roots.isEmpty()) {
            throw new IOException(NO_ROOTS);
        }
        String path = source.setting("path");
        if (path == null) {
            throw new IOException("The source has no folder: set path");
        }
        Path folder = Path.of(path);
        // include and exclude are applied to the listing by the connector, as for every kind of source
        LocalFileStore store = new LocalFileStore(folder, null,
            source.setting("recursive") == null || Boolean.parseBoolean(source.setting("recursive")));
        if (!folder.isAbsolute() || allowedReal(store.root()) == null) {
            throw new IOException("The folder " + path + (store.root().equals(folder) ? "" : " (" + store.root() + ")")
                + " is outside the folders this server allows sources to read (" + allowed()
                + "): choose a folder inside one of them, or ask an administrator to add it to " + ROOTS);
        }
        return store;
    }

    @Override
    protected String container(SourceConfig source, String path) {
        return folder(path);
    }
}
