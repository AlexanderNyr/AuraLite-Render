package dev.auralite.render.uniform;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bindings and function table for one evaluation.
 *
 * <p>One env instance is reused across a frame: the bindings are overwritten in
 * place, which is what keeps the LEGACY custom-uniform path allocation-free.
 * The BIOME lane builds a fresh env per field cell, and that is still cheap
 * because it happens at pack load, not per frame.
 */
public final class ExprEnv {
    private final Map<String, Value> bindings = new HashMap<String, Value>();
    private final FunctionTable functions;
    private String expressionText = "";

    public ExprEnv(FunctionTable functions) {
        this.functions = functions == null ? FunctionTable.standard() : functions;
    }

    public ExprEnv bind(String name, Value v) { bindings.put(name, v); return this; }
    public ExprEnv bindFloat(String name, double v) { return bind(name, Value.ofFloat(v)); }
    public ExprEnv bindInt(String name, int v) { return bind(name, Value.ofInt(v)); }
    public ExprEnv bindBool(String name, boolean v) { return bind(name, Value.bool(v)); }
    public ExprEnv unbind(String name) { bindings.remove(name); return this; }

    public Value lookup(String name) { return bindings.get(name); }
    public boolean has(String name) { return bindings.containsKey(name); }
    public int bindingCount() { return bindings.size(); }
    public Iterable<String> bindingNames() { return bindings.keySet(); }

    public ExprEnv expressionText(String t) { this.expressionText = t == null ? "" : t; return this; }
    public String expressionText() { return expressionText; }

    public FunctionTable functions() { return functions; }

    public Value callFunction(String name, List<Expr> args) {
        return functions.invoke(name, args, this);
    }

    public void clear() { bindings.clear(); }
}
