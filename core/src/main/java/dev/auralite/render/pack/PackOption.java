package dev.auralite.render.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One user-facing option.
 *
 * <p>LEGACY packs declare these with {@code #define} blocks plus
 * {@code screen[Tab]=} lines in {@code shaders.properties}; NATIVE packs
 * declare them in an {@code [options]} section with a type. Both land here, so
 * the option table, the profile presets and the GUI are one code path.
 */
public final class PackOption {
    public enum Kind {
        /** Preprocessor define: the value becomes {@code #define NAME value}. */
        DEFINE,
        /** A constant uploaded as a uniform rather than baked into the shader. */
        CONST,
        /** Continuous slider; every value between the endpoints exists. */
        SLIDER,
        /** Enumerated selector. */
        SELECTOR,
        /** On/off. */
        BOOL
    }

    public static final class Choice {
        public final String value;
        public final String label;
        public Choice(String value, String label) {
            this.value = value;
            this.label = label == null || label.isEmpty() ? value : label;
        }
        @Override public String toString() { return value + "=" + label; }
    }

    public final String name;
    public final Kind kind;
    public String label;
    public String screenTab = "";
    public final List<Choice> choices = new ArrayList<Choice>();
    public String defaultValue = "";
    public double sliderMin, sliderMax, sliderStep = 1.0;
    public String comment = "";

    public PackOption(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }

    public PackOption label(String l) { this.label = l; return this; }
    public PackOption tab(String t) { this.screenTab = t == null ? "" : t; return this; }
    public PackOption defaultValue(String v) { this.defaultValue = v; return this; }
    public PackOption comment(String c) { this.comment = c == null ? "" : c; return this; }

    public PackOption choice(String value, String label) {
        choices.add(new Choice(value, label));
        return this;
    }

    public PackOption slider(double min, double max, double step) {
        this.sliderMin = min; this.sliderMax = max; this.sliderStep = step;
        return this;
    }

    public List<Choice> choices() { return Collections.unmodifiableList(choices); }

    /** True when {@code value} is one this option accepts. */
    public boolean accepts(String value) {
        if (value == null) return false;
        switch (kind) {
            case BOOL: return value.equals("true") || value.equals("false")
                    || value.equals("0") || value.equals("1");
            case SLIDER: {
                try {
                    double d = Double.parseDouble(value);
                    return d >= sliderMin - 1e-9 && d <= sliderMax + 1e-9;
                } catch (NumberFormatException e) { return false; }
            }
            case SELECTOR: case DEFINE: case CONST:
                for (Choice c : choices) if (c.value.equals(value)) return true;
                return choices.isEmpty();
            default: return false;
        }
    }

    /**
     * The {@code #define} line this option contributes; empty when the option
     * contributes nothing (a boolean that is off). CONST carries its value as
     * a define too - a shader branches on it with {@code #if NAME == n}, which
     * is how the reference pack's debug view switches without a rebuild of
     * the pipeline layout.
     */
    public String defineFor(String value) {
        if (kind == Kind.CONST) return "#define " + name + " " + value;
        if (kind == Kind.BOOL) {
            boolean on = value.equals("true") || value.equals("1");
            return on ? "#define " + name : "";
        }
        return "#define " + name + " " + value;
    }

    @Override public String toString() {
        return name + " (" + kind + ", default=" + defaultValue + ", choices=" + choices.size() + ")";
    }
}
