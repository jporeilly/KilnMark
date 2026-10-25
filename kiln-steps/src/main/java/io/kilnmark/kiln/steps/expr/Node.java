package io.kilnmark.kiln.steps.expr;

import java.util.List;

/** The parsed form of an expression, before its types are checked. Positions count from 1. */
sealed interface Node {

    int pos();

    /** A literal value: {@code value} is a Boolean, Long, Double, BigDecimal, String, or for dates and timestamps a Long (days or microseconds since 1970). */
    record Literal(Type type, Object value, int pos) implements Node {
    }

    record Column(String name, int pos) implements Node {
    }

    /** {@code -x} or {@code not x}. */
    record Unary(String op, Node operand, int pos) implements Node {
    }

    /** Arithmetic, {@code ||}, comparisons, {@code and}, {@code or}. */
    record Binary(String op, Node left, Node right, int pos) implements Node {
    }

    record IsNull(Node operand, boolean negated, int pos) implements Node {
    }

    record In(Node operand, List<Node> items, boolean negated, int pos) implements Node {
    }

    record Between(Node operand, Node low, Node high, boolean negated, int pos) implements Node {
    }

    record Like(Node operand, Node pattern, boolean negated, int pos) implements Node {
    }

    /** {@code case [operand] when ... then ... [else ...] end}; {@code operand} and {@code otherwise} may be null. */
    record Case(Node operand, List<Node> whens, List<Node> thens, Node otherwise, int pos) implements Node {
    }

    record Cast(Node operand, Type target, int pos) implements Node {
    }

    /** A function call; {@code star} for {@code count(*)}. */
    record Call(String name, List<Node> args, boolean star, int pos) implements Node {
    }
}
