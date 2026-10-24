package io.kilnmark.kiln.plugins.documents;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.openxml4j.util.ZipSecureFile;

import io.kilnmark.kiln.connect.ConnectorContext;

/**
 * Where documents are read: on a few threads of their own, one document per thread, each with a
 * time limit. A document that is still being read when its time is up is abandoned: the caller
 * gets a plain-English reason at once, the read is asked to stop (its text collector and streams
 * check a flag), and its thread keeps its slot until it really ends, so stalled reads can never
 * use more than {@link DocumentLimits#threads()} processors. The server's settings arrive once,
 * through {@link #configure}.
 */
public final class DocumentRuntime {

    /** A piece of work on one document; it should give up once {@code cancelled} turns true. */
    @FunctionalInterface
    public interface Work<T> {
        T run(AtomicBoolean cancelled) throws Exception;
    }

    private static final AtomicInteger THREADS = new AtomicInteger();
    private static volatile DocumentLimits limits = DocumentLimits.DEFAULTS;
    private static volatile Semaphore slots = new Semaphore(DocumentLimits.DEFAULTS.threads());
    private static volatile Path temp;

    static {
        // Tika's OOXML parser turns POI's zip-bomb check off when its class loads (Tika relies on
        // checks Kilnmark does not use), so load it first, then set the check back on: Office files
        // are ZIP archives, and parts that expand more than 100 times (a zip bomb) or past 1 GiB are
        // refused, whatever the archive claims.
        try {
            Class.forName("org.apache.tika.parser.microsoft.ooxml.OOXMLParser", true,
                DocumentRuntime.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(1L << 30);
    }

    private DocumentRuntime() {
    }

    /** Applies the server's document settings; called when the connectors are loaded. */
    public static synchronized void configure(ConnectorContext context) {
        DocumentLimits l = DocumentLimits.of(context);
        if (!l.equals(limits)) {
            if (l.threads() != limits.threads()) {
                slots = new Semaphore(l.threads());
            }
            limits = l;
        }
    }

    /** The limits in force. */
    public static DocumentLimits limits() {
        return limits;
    }

    /**
     * Runs {@code work} on a document thread within the time limit.
     *
     * @param what the document, for thread names
     * @throws DocumentException with a plain-English reason when the work fails or takes too long
     * @throws InterruptedException when the calling thread is interrupted (the work is abandoned)
     */
    public static <T> T run(String what, Work<T> work) throws DocumentException, InterruptedException {
        DocumentLimits l = limits;
        Semaphore s = slots;
        long seconds = l.timeout().toSeconds();
        if (!s.tryAcquire(l.timeout().toNanos(), TimeUnit.NANOSECONDS)) {
            throw new DocumentException("every document reader on this server stayed busy for " + seconds
                + " seconds (earlier documents may still be running past their time limit): scan the source again later");
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread t;
        try {
            t = Thread.ofPlatform().daemon().name("kiln-document-" + THREADS.incrementAndGet()).start(() -> {
                try {
                    result.complete(work.run(cancelled));
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                } finally {
                    s.release();
                }
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            s.release();
            throw new DocumentException("the server could not start a thread to read it (" + e + ")", e);
        }
        try {
            return result.get(l.timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            cancelled.set(true);
            t.interrupt();
            throw new DocumentException("reading it took longer than the " + seconds + "-second limit, so Kilnmark "
                + "stopped: it may be very large, or made to stall readers", e);
        } catch (InterruptedException e) {
            cancelled.set(true);
            t.interrupt();
            throw e;
        } catch (ExecutionException e) {
            throw explain(e.getCause());
        }
    }

    /** A plain-English reason for a failure while reading a document. */
    public static DocumentException explain(Throwable e) {
        if (e instanceof DocumentException d) {
            return d;
        }
        String type = e.getClass().getName();
        String message = rootMessage(e);
        String lower = message.toLowerCase(Locale.ROOT);
        String reason;
        if (e instanceof OutOfMemoryError) {
            reason = "reading it needed more memory than the server could give it";
        } else if (e instanceof StackOverflowError) {
            reason = "its structure is nested too deeply to read";
        } else if (e instanceof LinkageError) {
            reason = "a library needed to read it could not be loaded (" + e + ")";
        } else if (encrypted(e)) {
            reason = "it is protected with a password or encrypted; Kilnmark does not read protected documents";
        } else if (lower.contains("zip bomb")) {
            reason = "it would expand to far more data than its size suggests (a zip bomb, or a damaged file), so "
                + "Kilnmark did not read it";
        } else if (lower.contains("tried to allocate an array") || lower.contains("maximum length for this record type")) {
            reason = "a part of it is larger than Kilnmark reads at once; the file may be damaged or made to "
                + "exhaust memory";
        } else if (type.contains("NotOfficeXmlFile") || type.contains("NotOLE2File")
            || type.contains("OLE2NotOfficeXmlFile") || type.contains("OfficeXmlFileException")
            || lower.contains("not a valid") || lower.contains("header doesn't contain")
            || lower.contains("invalid header")) {
            reason = "it is not a valid file of the kind its name says; it may be damaged or named wrongly (" + message
                + ")";
        } else {
            reason = "it could not be read (" + message + ")";
        }
        return new DocumentException(reason, e);
    }

    private static boolean encrypted(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (n.contains("Encrypted") || n.equals("InvalidPasswordException")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** The innermost message, kept short; the class name when there is none. */
    static String rootMessage(Throwable e) {
        Throwable t = e;
        String found = null;
        for (int i = 0; t != null && i < 10; i++, t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                found = t.getMessage();
            }
        }
        if (found == null) {
            found = e.getClass().getSimpleName();
        }
        found = found.replaceAll("\\s+", " ").strip();
        return found.length() > 300 ? found.substring(0, 300) + "..." : found;
    }

    /**
     * A folder only the server's user can read, for copies of remote documents while they are
     * read. Each copy is deleted when its read ends.
     */
    public static synchronized Path tempDir() throws IOException {
        if (temp == null || !Files.isDirectory(temp)) {
            Path base = Path.of(System.getProperty("java.io.tmpdir"));
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                temp = Files.createTempDirectory(base, "kilnmark-documents-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                temp = Files.createTempDirectory(base, "kilnmark-documents-");
            }
            temp.toFile().deleteOnExit();
        }
        return temp;
    }
}
