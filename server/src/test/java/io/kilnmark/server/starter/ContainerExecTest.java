package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/** A container command that never answers fails the test in its time, and other outcomes pass through. */
class ContainerExecTest {

    /** A container that is never started: each command does what the test gives it. */
    private static GenericContainer<?> answering(Answer answer) {
        return new Answering(answer);
    }

    private static final class Answering extends GenericContainer<Answering> {
        private final Answer answer;

        Answering(Answer answer) {
            super("kilnmark-never-started");
            this.answer = answer;
        }

        @Override
        public ExecResult execInContainer(String... command) throws IOException, InterruptedException {
            return answer.give();
        }
    }

    private interface Answer {
        ExecResult give() throws IOException, InterruptedException;
    }

    @Test
    void aCommandThatNeverAnswersFailsInItsTime() {
        CountDownLatch never = new CountDownLatch(1);
        long start = System.nanoTime();
        AssertionError e = assertThrows(AssertionError.class, () -> ContainerExec.exec(Duration.ofSeconds(1),
            answering(() -> {
                never.await();
                return null;
            }), "true"));
        long took = Duration.ofNanos(System.nanoTime() - start).toSeconds();
        assertTrue(took < 30, "took " + took + " s");
        assertTrue(e.getMessage().contains("gave no answer"), e.getMessage());
    }

    @Test
    void anErrorFromTheEngineIsThrownAsItIs() {
        IOException e = assertThrows(IOException.class, () -> ContainerExec.exec(answering(() -> {
            throw new IOException("engine gone");
        }), "true"));
        assertEquals("engine gone", e.getMessage());
    }

    @Test
    void anAnswerIsReturned() throws Exception {
        assertEquals(null, ContainerExec.exec(answering(() -> null), "true"));
    }
}
