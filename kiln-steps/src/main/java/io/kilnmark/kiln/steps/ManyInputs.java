package io.kilnmark.kiln.steps;

import java.util.List;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.StepException;

/**
 * A step context that can give every row set connected to a port taking many edges (union's
 * {@code in}). Temporary: the graph runner work adds {@code List<RowSet> inputs(String port)} to
 * {@link io.kilnmark.kiln.StepContext} itself, and {@link Inputs} uses that when it is there.
 */
public interface ManyInputs {

    /** Every row set connected to {@code port}, in the order the edges were declared. */
    List<RowSet> inputs(String port) throws StepException;
}
