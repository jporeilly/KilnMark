package io.kilnmark.server.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** What a script printed, read while it runs, so a script that hangs fails its test instead of the build. */
final class ScriptOutput {

    private ScriptOutput() {
    }

    /**
     * Everything {@code p} prints, once it has ended; it and every process it started are stopped
     * when it runs longer than {@code seconds}, and the test fails.
     */
    static String of(Process p, int seconds) throws InterruptedException {
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
            try {
                return p.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            throw new AssertionError("the script did not end within " + seconds + " seconds");
        }
        try {
            return new String(out.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError("could not read what the script printed", e);
        }
    }
}
