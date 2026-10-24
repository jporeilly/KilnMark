package io.kilnmark.kiln.plugins.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.ConnectorContext;

class DocumentRuntimeTest {

    @AfterEach
    void reset() {
        DocumentRuntime.configure(ConnectorContext.EMPTY);
    }

    @Test
    void settingsComeFromTheServer() {
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.MAX_FILE_MB, "5",
            DocumentLimits.TIMEOUT_SECONDS, "7", DocumentLimits.MAX_CHARACTERS, "1000")));
        assertEquals(5L << 20, DocumentRuntime.limits().maxBytes());
        assertEquals(7, DocumentRuntime.limits().timeout().toSeconds());
        assertEquals(1000, DocumentRuntime.limits().maxCharacters());
    }

    @Test
    void aReadPastItsTimeIsAbandonedAndAskedToStop() throws Exception {
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.TIMEOUT_SECONDS, "1",
            DocumentLimits.THREADS, "1")));
        CountDownLatch stopped = new CountDownLatch(1);
        long started = System.nanoTime();
        DocumentException e = assertThrows(DocumentException.class, () -> DocumentRuntime.run("stall", cancelled -> {
            while (!cancelled.get()) {
                Thread.onSpinWait(); // a parser stuck in a loop, checking only the flag
            }
            stopped.countDown();
            return null;
        }));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
        assertTrue(e.getMessage().contains("1-second limit"), e.getMessage());
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "the abandoned read saw the flag and ended");
        // its slot is free again: the next read runs
        assertEquals("ok", DocumentRuntime.run("next", cancelled -> "ok"));
    }

    @Test
    void failuresBecomePlainEnglish() {
        assertTrue(DocumentRuntime.explain(new java.io.IOException("Zip bomb detected! The file would exceed"))
            .getMessage().contains("zip bomb"));
        assertTrue(DocumentRuntime.explain(new OutOfMemoryError()).getMessage().contains("memory"));
        assertTrue(DocumentRuntime.explain(new org.apache.tika.exception.EncryptedDocumentException("x"))
            .getMessage().contains("password"));
    }

    @Test
    void healthyDocumentsWaitForAReaderInsteadOfFailing() throws Exception {
        // the review's Busy probe: 6 documents of 2 s each, 2 readers, a 3-second limit per read
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.THREADS, "2",
            DocumentLimits.TIMEOUT_SECONDS, "3")));
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            java.util.List<java.util.concurrent.Future<String>> all = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                all.add(ex.submit(() -> DocumentRuntime.run("doc", c -> {
                    Thread.sleep(2000);
                    return "ok";
                })));
            }
            for (var f : all) {
                assertEquals("ok", f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void whenEveryReaderIsHeldByAStalledReadTheNextFailsAtOnce() throws Exception {
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.THREADS, "1",
            DocumentLimits.TIMEOUT_SECONDS, "1")));
        CountDownLatch release = new CountDownLatch(1);
        DocumentException late = assertThrows(DocumentException.class, () -> DocumentRuntime.run("stuck", c -> {
            while (release.getCount() > 0) {
                Thread.onSpinWait(); // a parser that ignores both the flag and interrupts
            }
            return null;
        }));
        assertTrue(late.passing(), "out of time says nothing about the document");
        assertEquals(1, DocumentRuntime.abandoned());
        long started = System.nanoTime();
        DocumentException busy = assertThrows(DocumentException.class, () -> DocumentRuntime.run("next", c -> "ok"));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "fails at once, not after a wait");
        assertTrue(busy.getMessage().contains("ran past its time limit"), busy.getMessage());
        assertTrue(busy.passing());
        release.countDown();
        for (int i = 0; i < 50 && DocumentRuntime.abandoned() > 0; i++) {
            Thread.sleep(100);
        }
        assertEquals(0, DocumentRuntime.abandoned());
        assertEquals("ok", DocumentRuntime.run("after", c -> "ok"));
    }

    @Test
    void aStoppedScanStopsWaiting() throws Exception {
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.THREADS, "1")));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                DocumentRuntime.run("holder", c -> {
                    inside.countDown();
                    release.await();
                    return null;
                });
            } catch (Exception e) {
                // ends with the test
            }
        });
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            stop.set(true);
        });
        DocumentException e = assertThrows(DocumentException.class, () -> DocumentRuntime.run("waiting", stop::get,
            c -> "never"));
        assertTrue(e.passing());
        assertTrue(e.getMessage().contains("stopped"), e.getMessage());
        release.countDown();
        holder.join();
    }
}
