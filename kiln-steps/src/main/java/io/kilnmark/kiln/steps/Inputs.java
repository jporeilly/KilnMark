package io.kilnmark.kiln.steps;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import io.kilnmark.kiln.RowSet;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

/**
 * Finds every row set connected to a port that takes many edges.
 *
 * <p>Temporary adapter, until {@code StepContext.inputs(String port)} (being added with the graph
 * runner) is in kiln-core: it uses that method when the running core has it, then a context that
 * implements {@link ManyInputs}, then the port's single row set. The method is looked up once, by
 * its fixed name, on our own interface; nothing a user writes is ever looked up. Once the method
 * is in kiln-core this class becomes a direct call.
 */
final class Inputs {

    private static final Method CORE_INPUTS = find();

    private Inputs() {
    }

    private static Method find() {
        try {
            Method m = StepContext.class.getMethod("inputs", String.class);
            return List.class.isAssignableFrom(m.getReturnType()) ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static List<RowSet> many(StepContext ctx, String port) throws StepException {
        if (CORE_INPUTS != null) {
            try {
                List<?> found = (List<?>) CORE_INPUTS.invoke(ctx, port);
                return found.stream().map(RowSet.class::cast).toList();
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof StepException se) {
                    throw se;
                }
                throw new StepException("Could not read the inputs of port " + port + ": " + e.getCause(), e.getCause());
            } catch (IllegalAccessException e) {
                throw new StepException("Could not read the inputs of port " + port, e);
            }
        }
        if (ctx instanceof ManyInputs many) {
            return many.inputs(port);
        }
        return ctx.inputPorts().contains(port) ? List.of(ctx.input(port)) : List.of();
    }
}
