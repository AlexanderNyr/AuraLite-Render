package dev.auralite.render.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What loading a pack actually did.
 *
 * <p>Errors refuse the pack, warnings let it run, notes record decisions that
 * would otherwise be invisible -- including the format that was chosen when a
 * pack ships both trees. Every line carries the file it came from, because
 * "your pack is broken" without a location is not a useful message.
 */
public final class PackLoadReport {
    private final List<String> errors = new ArrayList<String>();
    private final List<String> warnings = new ArrayList<String>();
    private final List<String> notes = new ArrayList<String>();
    private final List<String> unparsed = new ArrayList<String>();
    private long loadMicros;
    private int programFilesRead;
    private long shaderBytesRead;

    public PackLoadReport error(String where, String what) { errors.add(where + ": " + what); return this; }
    public PackLoadReport warning(String where, String what) { warnings.add(where + ": " + what); return this; }
    public PackLoadReport note(String what) { notes.add(what); return this; }
    public PackLoadReport unparsed(String where, String line) { unparsed.add(where + ": " + line); return this; }

    public List<String> errors() { return Collections.unmodifiableList(errors); }
    public List<String> warnings() { return Collections.unmodifiableList(warnings); }
    public List<String> notes() { return Collections.unmodifiableList(notes); }
    public List<String> unparsed() { return Collections.unmodifiableList(unparsed); }

    public boolean ok() { return errors.isEmpty(); }
    public int errorCount() { return errors.size(); }
    public int warningCount() { return warnings.size(); }

    public void setLoadMicros(long us) { this.loadMicros = us; }
    public long loadMicros() { return loadMicros; }
    public void countProgramFile(long bytes) { programFilesRead++; shaderBytesRead += bytes; }
    public int programFilesRead() { return programFilesRead; }
    public long shaderBytesRead() { return shaderBytesRead; }

    public String receipt() {
        StringBuilder sb = new StringBuilder();
        sb.append("pack load: ").append(ok() ? "OK" : "REFUSED").append(" in ")
          .append(loadMicros).append(" us, ").append(programFilesRead).append(" program files, ")
          .append(shaderBytesRead).append(" B\n");
        for (String e : errors) sb.append("  ERROR: ").append(e).append('\n');
        for (String w : warnings) sb.append("  warning: ").append(w).append('\n');
        for (String n : notes) sb.append("  note: ").append(n).append('\n');
        if (!unparsed.isEmpty()) {
            sb.append("  unparsed: ").append(unparsed.size()).append(" line(s)\n");
            int shown = Math.min(unparsed.size(), 12);
            for (int i = 0; i < shown; i++) sb.append("    ").append(unparsed.get(i)).append('\n');
            if (unparsed.size() > shown) sb.append("    ... ").append(unparsed.size() - shown).append(" more\n");
        }
        return sb.toString();
    }
}
