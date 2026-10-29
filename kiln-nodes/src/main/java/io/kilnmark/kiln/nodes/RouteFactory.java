package io.kilnmark.kiln.nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;
import io.kilnmark.kiln.nodes.expr.Batch;
import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;

/**
 * {@code route}: sends each row to the port of the first condition it meets, in the order the
 * routes are written; rows meeting none go to {@code out}. Each route's name is an output port.
 */
public final class RouteFactory implements NodeFactory {

    public static final String TYPE = "route";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.TRANSFORM;
    }

    @Override
    public String category() {
        return io.kilnmark.kiln.NodeCategories.FILTER_AND_ROUTE;
    }

    @Override
    public String label() {
        return "Route rows";
    }

    @Override
    public String help() {
        return "Sends each row to the first route whose condition it meets, in order. Rows that meet none go to "
            + "the out port.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return List.of("out");
    }

    @Override
    public List<String> outputPorts(Map<String, Object> config) {
        Config c = new Config(config);
        LinkedHashMap<String, Expression> routes = routes(c);
        List<String> ports = new ArrayList<>();
        if (routes != null) {
            ports.addAll(routes.keySet());
        }
        ports.add("out");
        return ports;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.required("routes", "Routes", SettingSpec.Kind.CONDITIONS,
            "Each route's port name and its condition, checked in order, for example large: amount > 1000."));
    }

    private static LinkedHashMap<String, Expression> routes(Config c) {
        LinkedHashMap<String, Expression> routes = c.expressions("routes", true);
        if (routes == null) {
            return null;
        }
        for (String port : routes.keySet()) {
            if (!port.matches("[a-z][a-z0-9_-]{0,62}")) {
                c.problem("routes." + port, "a port name must start with a small letter and use only small letters, "
                    + "digits, - and _");
                return null;
            }
            if (port.equals("out") || port.equals("in")) {
                c.problem("routes." + port, "'" + port + "' is taken; choose another port name");
                return null;
            }
        }
        return routes;
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        Config c = new Config(config);
        routes(c);
        return c.problems();
    }

    @Override
    public Node create(Map<String, Object> config) throws NodeException {
        Config c = new Config(config);
        LinkedHashMap<String, Expression> routes = routes(c);
        c.orThrow(TYPE);
        return new RouteNode(routes);
    }

    @Override
    public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws NodeException {
        Config c = new Config(config);
        LinkedHashMap<String, Expression> routes = routes(c);
        c.orThrow(TYPE);
        if (!port.equals("out") && !routes.containsKey(port)) {
            throw new NodeException("This route node has no output port '" + port + "'");
        }
        Schema in = inputs.get("in");
        if (in == null) {
            return null;
        }
        for (Map.Entry<String, Expression> e : routes.entrySet()) {
            try {
                FilterFactory.FilterNode.checkCondition(e.getValue().compile(in));
            } catch (ExprException ex) {
                throw new NodeException("route " + e.getKey() + ": " + ex.getMessage(), ex);
            }
        }
        return in;
    }

    /** The running node. */
    static final class RouteNode extends BaseNode {
        private final LinkedHashMap<String, Expression> routes;
        private final List<String> ports;
        private Schema boundTo;
        private List<Expression.Compiled> compiled;

        RouteNode(LinkedHashMap<String, Expression> routes) {
            super(TYPE);
            this.routes = routes;
            this.ports = new ArrayList<>(routes.keySet());
            this.ports.add("out");
        }

        @Override
        public boolean process(NodeContext ctx) throws NodeException {
            VectorSchemaRoot batch = take(ctx, "in");
            if (batch == null) {
                return false;
            }
            int[] target;
            try {
                if (!batch.getSchema().equals(boundTo)) {
                    compiled = bind(batch.getSchema());
                    boundTo = batch.getSchema();
                }
                int n = batch.getRowCount();
                target = new int[n];
                boolean[] left = new boolean[n];
                java.util.Arrays.fill(left, true);
                java.util.Arrays.fill(target, ports.size() - 1);
                Batch rows = Batch.of(batch, now);
                for (int r = 0; r < compiled.size(); r++) {
                    boolean[] hit;
                    try {
                        hit = compiled.get(r).test(rows, left);
                    } catch (ExprException e) {
                        throw fail("route " + ports.get(r), e);
                    }
                    for (int i = 0; i < n; i++) {
                        if (hit[i]) {
                            target[i] = r;
                            left[i] = false;
                        }
                    }
                }
            } catch (NodeException | RuntimeException e) {
                batch.close();
                throw e;
            }
            distribute(ctx, batch, target, ports);
            return true;
        }

        private List<Expression.Compiled> bind(Schema schema) throws NodeException {
            List<Expression.Compiled> out = new ArrayList<>();
            for (Map.Entry<String, Expression> e : routes.entrySet()) {
                try {
                    Expression.Compiled c = e.getValue().compile(schema);
                    FilterFactory.FilterNode.checkCondition(c);
                    out.add(c);
                } catch (ExprException ex) {
                    throw fail("route " + e.getKey(), ex);
                }
            }
            return out;
        }
    }
}
