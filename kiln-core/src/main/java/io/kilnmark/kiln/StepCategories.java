package io.kilnmark.kiln;

import java.util.List;

/**
 * The folders the canvas palette groups step types into ({@link StepFactory#category()}). A step
 * type names one of these; the palette shows them in this order, and any other name after them.
 */
public final class StepCategories {

    public static final String READ = "Read";
    public static final String WRITE = "Write";
    public static final String FILTER_AND_ROUTE = "Filter and route";
    public static final String TRANSFORM = "Transform";
    public static final String COMBINE = "Combine";
    public static final String AGGREGATE = "Aggregate";
    public static final String QUALITY = "Quality";
    public static final String AI = "AI";
    public static final String OTHER = "Other";

    /** Every category, in the palette's order. */
    public static final List<String> ALL = List.of(READ, WRITE, FILTER_AND_ROUTE, TRANSFORM, COMBINE, AGGREGATE,
        QUALITY, AI, OTHER);

    private StepCategories() {
    }

    /** The category a step type has when it does not name one: by its kind. */
    public static String defaultFor(StepKind kind) {
        if (kind == null) {
            return OTHER;
        }
        return switch (kind) {
            case SOURCE -> READ;
            case SINK -> WRITE;
            case GATE -> QUALITY;
            case TRANSFORM -> OTHER;
        };
    }
}
