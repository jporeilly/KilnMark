package io.kilnmark.server.starter;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * A command run in a test's container that fails the test when it takes too long. Testcontainers
 * waits for an exec's output with no limit, so a stream the container engine loses (a restarted
 * or overloaded machine) would otherwise hold the whole build until CI gives up hours later.
 */
final class ContainerExec {

    /** Far longer than any command these tests run takes; a lost stream fails in this time. */
    static final Duration LIMIT = Duration.ofMinutes(3);

    private ContainerExec() {
    }

    /** {@code command} run in {@code container}, failing after {@link #LIMIT}. */
    static ExecResult exec(GenericContainer<?> container, String... command) throws Exception {
        return exec(LIMIT, container, command);
    }

    /** {@code command} run in {@code container}, failing after {@code limit}. */
    static ExecResult exec(Duration limit, GenericContainer<?> container, String... command) throws Exception {
        FutureTask<ExecResult> task = new FutureTask<>(() -> container.execInContainer(command));
        Thread runner = Thread.ofPlatform().daemon().name("container-exec").start(task);
        try {
            return task.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            runner.interrupt();
            throw new AssertionError("a command in the test container gave no answer in "
                + limit.toMinutes() + " minutes; the container engine has probably lost its output", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
