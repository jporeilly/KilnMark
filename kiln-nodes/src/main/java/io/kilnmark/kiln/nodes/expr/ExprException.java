package io.kilnmark.kiln.nodes.expr;

import java.io.Serial;

/**
 * An expression could not be read, checked or worked out. The message says what is wrong in plain
 * words, then where (the character position, counting from 1), then a hint when there is one:
 * {@code unknown column 'frist_name' at 7; did you mean first_name?}
 */
public final class ExprException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String problem;
    private final int position;
    private final String hint;
    private final String code;

    public ExprException(String problem, int position) {
        this(problem, position, null);
    }

    public ExprException(String problem, int position, String hint) {
        this(problem, position, hint, null);
    }

    private ExprException(String problem, int position, String hint, String code) {
        super(format(problem, position, hint));
        this.problem = problem;
        this.position = position;
        this.hint = hint;
        this.code = code;
    }

    /**
     * A failure of one row's value while rows are worked out (ADR 0054), with its code: a row that
     * fails this way can go to a node's errors port, with {@link #problem()} and {@link #hint()} as
     * its message. Both must be fixed wordings that never hold a value from the data.
     */
    static ExprException row(String code, String problem, int position) {
        return new ExprException(problem, position, null, code);
    }

    /** {@link #row(String, String, int)} with a hint. */
    static ExprException row(String code, String problem, int position, String hint) {
        return new ExprException(problem, position, hint, code);
    }

    /**
     * What kind of row failure this is ({@code cast_failed}, {@code division_by_zero},
     * {@code out_of_range}, {@code too_long}, {@code too_slow}, {@code invalid_argument}), or null
     * for a failure not known to be about one row's value, whose wording is then never used for an
     * error row.
     */
    public String code() {
        return code;
    }

    /** What is wrong, without the position or hint. */
    public String problem() {
        return problem;
    }

    /** The character position in the expression, counting from 1; 0 when it is about the whole expression. */
    public int position() {
        return position;
    }

    /** A suggestion, or null. */
    public String hint() {
        return hint;
    }

    private static String format(String problem, int position, String hint) {
        StringBuilder sb = new StringBuilder(problem);
        if (position > 0) {
            sb.append(" at ").append(position);
        }
        if (hint != null && !hint.isEmpty()) {
            sb.append("; ").append(hint);
        }
        return sb.toString();
    }
}
