package io.kilnmark.kiln.steps.expr;

/**
 * How many characters of text one function call or operator may produce for one batch of rows. A
 * cap per value is not enough: a thousand-character result is harmless once, but not 8,192 times
 * over. One instance serves one call in one expression and is reset at the start of each batch.
 */
final class TextBudget {

    /** Characters of results allowed per call per batch. */
    static final long PER_BATCH = 64_000_000L;

    private long left = PER_BATCH;

    void newBatch() {
        left = PER_BATCH;
    }

    /** Counts {@code chars} more characters of results, failing once the batch's budget is spent. */
    void spend(long chars, int pos) throws ExprException {
        left -= chars;
        if (left < 0) {
            throw new ExprException("the text this produces for one batch of rows would be more than 64,000,000 "
                + "characters", pos, "make the text shorter, or work on fewer or shorter values");
        }
    }
}
