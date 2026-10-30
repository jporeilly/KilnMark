package io.kilnmark.kiln.connect;

import java.util.List;
import java.util.Objects;

/**
 * A condition a read node asks its source to apply while reading (docs/design/pipelines.md, "The
 * read node's columns and where"), in no database's syntax: each connector that can renders it as
 * its own SQL, with every value bound, never written into the text.
 *
 * <p>A read filter only ever narrows a read to rows the pipeline may want: the read node applies
 * its whole condition again to what arrives, so a database that compares text without regard to
 * case or trailing spaces can only send rows the node then drops, never miss one. Values are
 * {@link Long}, {@link java.math.BigDecimal}, {@link Double}, {@link Boolean}, {@link String} or
 * {@link java.time.LocalDate}.
 */
public sealed interface ReadFilter {

    /** Every condition holds. */
    record And(List<ReadFilter> all) implements ReadFilter {
        public And {
            all = List.copyOf(all);
        }
    }

    /** At least one condition holds. */
    record Or(List<ReadFilter> any) implements ReadFilter {
        public Or {
            any = List.copyOf(any);
        }
    }

    /** The condition does not hold (and is not unknown). */
    record Not(ReadFilter condition) implements ReadFilter {
        public Not {
            Objects.requireNonNull(condition, "condition");
        }
    }

    /** {@code column op value}: op is one of {@code = <> < <= > >=}. */
    record Compare(String column, String op, Object value) implements ReadFilter {
        public Compare {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(value, "value");
            if (!List.of("=", "<>", "<", "<=", ">", ">=").contains(op)) {
                throw new IllegalArgumentException("op: " + op);
            }
        }
    }

    /** {@code column [not] in (values)}. */
    record In(String column, List<Object> values, boolean negated) implements ReadFilter {
        public In {
            Objects.requireNonNull(column, "column");
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("values: at least one");
            }
        }
    }

    /** {@code column [not] between low and high}. */
    record Between(String column, Object low, Object high, boolean negated) implements ReadFilter {
        public Between {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(low, "low");
            Objects.requireNonNull(high, "high");
        }
    }

    /** {@code column is [not] null}. */
    record IsNull(String column, boolean negated) implements ReadFilter {
        public IsNull {
            Objects.requireNonNull(column, "column");
        }
    }

    /** {@code column like pattern}: {@code %} any text, {@code _} one character; no escape character. */
    record Like(String column, String pattern) implements ReadFilter {
        public Like {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(pattern, "pattern");
        }
    }
}
