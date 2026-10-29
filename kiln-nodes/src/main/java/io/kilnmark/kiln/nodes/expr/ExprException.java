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

    public ExprException(String problem, int position) {
        this(problem, position, null);
    }

    public ExprException(String problem, int position, String hint) {
        super(format(problem, position, hint));
        this.problem = problem;
        this.position = position;
        this.hint = hint;
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
