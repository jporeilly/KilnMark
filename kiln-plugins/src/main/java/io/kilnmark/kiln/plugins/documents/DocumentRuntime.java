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
import java.util.function.BooleanSupplier;

import org.apache.poi.openxml4j.util.ZipSecureFile;

import io.kilnmark.kiln.connect.ConnectorContext;

/**
 * Where documents are read: on a few threads of their own, one document per thread, each with a
 * time limit. A document that is still being read when its time is up is abandoned: the caller
 * gets a plain-English reason at once, the read is asked to stop (its thread is interrupted and
 * its text collector checks a flag, ending the parse the next time the parser reports text or an
 * element; the parsers' own streams do not check it), and its thread keeps its slot until it
 * really ends, so stalled reads can never use more than {@link DocumentLimits#threads()}
 * processors. Reads people ask for (sources that keep no text) have one reader of their own. The
 * server's settings arrive once, through {@link #configure}.
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

    /** Reads that ran past their time and have not ended yet, by lane. */
    private static final AtomicInteger ABANDONED = new AtomicInteger();
    private static final AtomicInteger ABANDONED_ON_DEMAND = new AtomicInteger();

    /** Reads asked for by a person (keep_text off): one at a time, apart from scans. */
    private static final Semaphore ON_DEMAND = new Semaphore(1);

    /** How long to wait between checks while waiting for a free reader. */
    private static final long POLL_MILLIS = 250;

    /**
     * Runs {@code work} on a document thread within the time limit, for a scan.
     *
     * <p>Waiting for a free reader has no deadline: healthy documents queue behind each other
     * however many there are, and only the read itself is timed. The wait ends early, with a
     * passing failure, when {@code stop} turns true or when every reader is held by a read that
     * ran past its time and has not ended (a stalled parser), which would never free a slot.
     *
     * @param what the document, for messages
     * @param stop true once the scan is being stopped
     * @throws DocumentException with a plain-English reason when the work fails or takes too long
     * @throws InterruptedException when the calling thread is interrupted (the work is abandoned)
     */
    public static <T> T run(String what, BooleanSupplier stop, Work<T> work)
        throws DocumentException, InterruptedException {
        DocumentLimits l = limits;
        return run(slots, l.threads(), ABANDONED, l, stop, work);
    }

    /** {@link #run(String, BooleanSupplier, Work)}, stopping only when the thread is interrupted. */
    public static <T> T run(String what, Work<T> work) throws DocumentException, InterruptedException {
        return run(what, () -> Thread.currentThread().isInterrupted(), work);
    }

    /**
     * Runs a read a person asked for (a source that keeps no text): on its own single reader, so it
     * never takes a reader from a scan, within the same time limit.
     */
    public static <T> T runOnDemand(String what, Work<T> work) throws DocumentException, InterruptedException {
        return run(ON_DEMAND, 1, ABANDONED_ON_DEMAND, limits, () -> Thread.currentThread().isInterrupted(), work);
    }

    /** Reads abandoned past their time that have not ended yet (for tests and health checks). */
    public static int abandoned() {
        return ABANDONED.get();
    }

    /**
     * A reader held for work done on the caller's own thread (a sheet read row by row): it counts
     * against {@link DocumentLimits#threads()} like any read. Close it to free the reader.
     */
    public static AutoCloseable acquire(BooleanSupplier stop) throws DocumentException, InterruptedException {
        DocumentLimits l = limits;
        Semaphore s = slots;
        await(s, l.threads(), ABANDONED, stop);
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                s.release();
            }
        };
    }

    private static void await(Semaphore s, int total, AtomicInteger abandoned, BooleanSupplier stop)
        throws DocumentException, InterruptedException {
        while (!s.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (stop.getAsBoolean()) {
                throw new DocumentException("the scan was stopped before it could be read", true, null);
            }
            if (abandoned.get() >= total) {
                throw new DocumentException("every document reader on this server is held by a document that ran "
                    + "past its time limit and has not stopped (" + abandoned.get() + " of " + total + "); restart "
                    + "the server or raise kilnmark.documents.threads, then scan again", true, null);
            }
        }
    }

    private static <T> T run(Semaphore s, int total, AtomicInteger abandoned, DocumentLimits l, BooleanSupplier stop,
                             Work<T> work) throws DocumentException, InterruptedException {
        long seconds = l.timeout().toSeconds();
        await(s, total, abandoned, stop);
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<T> result = new CompletableFuture<>();
        // 0 running, 1 ended in time, 2 abandoned: whoever moves it first decides who counts what
        AtomicInteger state = new AtomicInteger();
        Thread t;
        try {
            t = Thread.ofPlatform().daemon().name("kiln-document-" + THREADS.incrementAndGet()).start(() -> {
                try {
                    result.complete(work.run(cancelled));
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                } finally {
                    if (!state.compareAndSet(0, 1)) {
                        abandoned.decrementAndGet();
                    }
                    s.release();
                }
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            s.release();
            throw new DocumentException("the server could not start a thread to read it (" + e + ")", true, e);
        }
        try {
            return result.get(l.timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (state.compareAndSet(0, 2)) {
                abandoned.incrementAndGet();
                cancelled.set(true);
                t.interrupt();
                throw new DocumentException("reading it took longer than the " + seconds + "-second limit, so "
                    + "Kilnmark stopped: it may be very large, or made to stall readers", true, e);
            }
            try {
                return result.get(); // it ended just as its time ran out
            } catch (ExecutionException ex) {
                throw explain(ex.getCause());
            }
        } catch (InterruptedException e) {
            if (state.compareAndSet(0, 2)) {
                abandoned.incrementAndGet();
            }
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

    /** Folders of temporary copies are named this, then the process ID that made them. */
    static final String TEMP_PREFIX = "kilnmark-documents-";

    /**
     * A folder only the server's user can read, for copies of remote documents while they are
     * read, and for the files Tika and POI spill to disk. Each copy is deleted when its read ends;
     * folders left by servers that are no longer running (a crash) are deleted the first time this
     * is called.
     */
    public static synchronized Path tempDir() throws IOException {
        if (temp == null || !Files.isDirectory(temp)) {
            Path base = Path.of(System.getProperty("java.io.tmpdir"));
            cleanStale(base);
            String prefix = TEMP_PREFIX + ProcessHandle.current().pid() + "-";
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                temp = Files.createTempDirectory(base, prefix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                temp = Files.createTempDirectory(base, prefix);
            }
            temp.toFile().deleteOnExit();
            // POI's own temporary files (large parts it spills) go here too
            org.apache.poi.util.TempFile.setTempFileCreationStrategy(
                new org.apache.poi.util.DefaultTempFileCreationStrategy(temp.toFile()));
        }
        return temp;
    }

    /** Deletes {@value #TEMP_PREFIX}* folders whose server process is gone. */
    static void cleanStale(Path base) {
        try (var dirs = Files.newDirectoryStream(base, TEMP_PREFIX + "*")) {
            for (Path d : dirs) {
                String rest = d.getFileName().toString().substring(TEMP_PREFIX.length());
                int dash = rest.indexOf('-');
                long pid;
                try {
                    pid = Long.parseLong(dash < 0 ? rest : rest.substring(0, dash));
                } catch (NumberFormatException e) {
                    continue; // not one of ours
                }
                if (pid == ProcessHandle.current().pid() || ProcessHandle.of(pid).map(ProcessHandle::isAlive)
                    .orElse(false)) {
                    continue;
                }
                deleteTree(d);
            }
        } catch (IOException | RuntimeException e) {
            // cleaning up is a courtesy; a folder left behind holds nothing anyone else can read
        }
    }

    private static void deleteTree(Path d) throws IOException {
        try (var all = Files.walk(d)) {
            for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
