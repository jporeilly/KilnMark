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
}
