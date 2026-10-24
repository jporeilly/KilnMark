package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.kilnmark.kiln.connect.SourceConfig;

/**
 * Files in a folder the server can read: a local disk or a mounted share. Settings {@code path}
 * (the folder), {@code include} (a glob, default every file) and {@code recursive} (default true).
 * Asset paths are relative to the folder; nothing outside it is listed or read.
 */
public final class FilesConnector extends FileSourceConnector {

    @Override
    public String kind() {
        return "files";
    }

    @Override
    public String displayName() {
        return "Files in a folder";
    }

    @Override
    public List<Setting> settings() {
        return List.of(
            new Setting("path", "Folder", true,
                "The full path of a folder this server can read, for example /srv/share", false),
            new Setting("include", "Include", false,
                "Which files to record, as a glob: *.csv matches file names, exports/** matches paths. "
                    + "Leave empty for every file", false),
            new Setting("recursive", "Include sub-folders", false, "true (the default) or false", false));
    }

    @Override
    public List<String> validate(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        String path = source.setting("path");
        if (path == null) {
            problems.add("path: the full path of the folder, for example /srv/share");
        } else {
            try {
                if (!Path.of(path).isAbsolute()) {
                    problems.add("path: a full path starting at the root, for example /srv/share, not '" + path + "'");
                }
            } catch (InvalidPathException e) {
                problems.add("path: not a valid path (" + e.getMessage() + ")");
            }
        }
        String include = source.setting("include");
        if (include != null) {
            try {
                FileSystems.getDefault().getPathMatcher("glob:" + include);
            } catch (IllegalArgumentException e) { // including PatternSyntaxException
                problems.add("include: not a valid glob, for example *.csv or **/*.{csv,parquet}");
            }
        }
        checkBoolean(source, "recursive", problems);
        return problems;
    }

    @Override
    protected FileStore open(SourceConfig source) throws IOException {
        String recursive = source.setting("recursive");
        return new LocalFileStore(Path.of(source.setting("path")), source.setting("include"),
            recursive == null || Boolean.parseBoolean(recursive));
    }

    @Override
    protected String container(SourceConfig source, String path) {
        return folder(path);
    }
}
