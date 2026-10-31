package dev.auralite.render.tools.packlint;

import dev.auralite.render.backend.nulldev.NullDevice;
import dev.auralite.render.pack.PackFormat;
import dev.auralite.render.pack.PackLoader;
import dev.auralite.render.pack.PackLoadReport;
import dev.auralite.render.pack.PackManifest;
import dev.auralite.render.pack.PackSource;
import dev.auralite.render.rhi.GfxDevice;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The loader's refusals as a CLI.
 *
 * <p>Every refusal {@link PackLoader} can issue, explained with the file and
 * line it came from, without a GPU and without the game. Exit contract:
 *
 * <ul>
 * <li>0 -- the pack loads; warnings (if any) are on stderr;</li>
 * <li>1 -- the pack is refused (errors), or {@code --strict} promoted a
 *       warning;</li>
 * <li>2 -- the tool could not run (bad arguments, unreadable path).</li>
 * </ul>
 *
 * <p>Usage: {@code packlint [--strict] [--null-device] <pack-dir-or-zip>}.
 * Without {@code --null-device} the load is format-level only (the loader's
 * own note says so); with it, required capabilities are also checked against
 * the NULL device's full capability set -- the spec every backend must back.
 */
public final class PackLint {

    private PackLint() { }

    public static void main(String[] args) {
        boolean strict = false;
        boolean nullDevice = false;
        String packPath = null;
        for (String a : args) {
            if (a.equals("--strict")) strict = true;
            else if (a.equals("--null-device")) nullDevice = true;
            else if (a.equals("--help") || a.equals("-h")) { usage(System.out); return; }
            else if (a.startsWith("-")) { System.err.println("packlint: unknown flag '" + a + "'"); usage(System.err); System.exit(2); }
            else if (packPath == null) packPath = a;
            else { System.err.println("packlint: unexpected argument '" + a + "'"); System.exit(2); }
        }
        if (packPath == null) {
            System.err.println("packlint: no pack path given");
            usage(System.err);
            System.exit(2);
        }

        Path p = Paths.get(packPath);
        if (!Files.exists(p)) {
            System.err.println("packlint: '" + packPath + "' does not exist");
            System.exit(2);
        }

        PackSource source;
        try {
            source = Files.isDirectory(p) ? new PackSource.Directory(p) : new PackSource.Zip(p);
        } catch (Exception e) {
            System.err.println("packlint: cannot open '" + packPath + "': " + e.getMessage());
            System.exit(2);
            return;
        }

        GfxDevice device = null;
        NullDevice nullDev = null;
        if (nullDevice) {
            nullDev = new NullDevice();
            device = nullDev;
        }

        int exit;
        try {
            PackLoader loader = new PackLoader(source);
            boolean ok = loader.load(device);
            PackManifest m = loader.manifest();
            PackLoadReport r = loader.report();

            PrintStream out = System.out;
            out.println("packlint: " + source.origin());
            out.println("  format:  " + (m.format == null ? "<undetected>" : m.format));
            out.println("  name:    " + m.name + " " + m.version
                    + (m.author.isEmpty() ? "" : " by " + m.author));
            out.println("  api:     " + m.apiLevel);
            out.println("  programs:" + m.programCount()
                    + (m.format == PackFormat.LEGACY_IRIS
                            ? ", coverage " + loader.legacyCoverage().size()
                              + "/" + PackLoader.LEGACY_CORE_PROGRAMS.size()
                            : ""));
            out.println("  options: " + m.optionCount());
            if (!m.requiredCapabilities.isEmpty()) {
                out.println("  requires:" + m.requiredCapabilities);
            }
            out.print(r.receipt());

            boolean warned = r.warningCount() > 0;
            if (!r.errors().isEmpty()) {
                for (String e : r.errors()) System.err.println("ERROR: " + e);
            }
            if (warned) {
                for (String w : r.warnings()) System.err.println("warning: " + w);
            }

            if (!ok) {
                System.err.println("packlint: REFUSED (" + r.errorCount() + " error(s)"
                        + (warned ? ", " + r.warningCount() + " warning(s)" : "") + ")");
                exit = 1;
            } else if (strict && warned) {
                System.err.println("packlint: --strict promoted " + r.warningCount()
                        + " warning(s) to failure");
                exit = 1;
            } else {
                System.out.println("packlint: OK" + (warned
                        ? " with " + r.warningCount() + " warning(s)" : "")
                        + (strict ? " (--strict)" : ""));
                exit = 0;
            }
        } finally {
            source.close();
            if (nullDev != null) nullDev.close();
        }
        System.exit(exit);
    }

    private static void usage(PrintStream s) {
        s.println("usage: packlint [--strict] [--null-device] <pack-dir-or-zip>");
        s.println("  --strict       warnings become failures (exit 1)");
        s.println("  --null-device  also validate required capabilities against the NULL device");
        s.println("exit: 0 = loads, 1 = refused (or strict warnings), 2 = tool error");
    }
}
