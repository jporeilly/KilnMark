package io.kilnmark.server.runs;

import java.time.Instant;
import java.util.List;

/**
 * One ended try of a pipeline or flow run, as the API shows it (ADR 0053): its number from 1, how it
 * ended ({@code succeeded}, {@code failed}, {@code cancelled}, or {@code lost} with its worker), when,
 * why in Kilnmark's words ({@code message}, null when the reader may not see it), what came next
 * ({@code next}: tried again, or why not), and who asked for it by hand ({@code askedBy}, for Run again
 * from the failure).
 */
public record RunTryView(int number, String status, Instant startedAt, Instant finishedAt, String message,
                         String next, String askedBy) {

    /** The tries as recorded; {@code messages} false leaves out each try's message (a pipeline's, for viewers). */
    public static List<RunTryView> of(List<RunTables.Try> tries, boolean messages) {
        return tries.stream().map(t -> new RunTryView(t.number() + 1, t.status(), t.startedAt(), t.finishedAt(),
            messages ? t.error() : null, t.next(), t.askedBy())).toList();
    }
}
