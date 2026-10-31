package dev.auralite.render.tools.drift;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suite receipts may not move without their doc lines moving too.
 *
 * <p>Replays the host gate ({@code RunAll --receipts}) in a subprocess on this
 * tool's own classpath, parses the per-suite and TOTAL receipts, and compares
 * them against the numbers the docs currently claim:
 *
 * <ul>
 * <li>{@code docs/STATUS.md} -- per-suite counts AND the TOTAL line;</li>
 * <li>{@code README.md} -- the TOTAL line.</li>
 * </ul>
 *
 * <p>Any mismatch is printed as a named drift (suite, field, receipt value,
 * doc value, doc file) and the exit code is 1. Exit 0 means every tracked
 * number in the docs is the number the gates actually produce. Exit 2 means
 * the check itself could not run (missing files, a red host gate, a crashed
 * subprocess) -- a broken gate is never reported as "no drift".
 *
 * <p>Timings are deliberately not tracked: the receipt is the counts plus
 * PASS, never microseconds.
 */
public final class DriftCheck {

    // suite line with timing (fresh run): "rhi         51 passed   0 failed   0 NC   52640 us  PASS"
    private static final Pattern RUN_SUITE = Pattern.compile(
            "^(\\w+)\\s+(\\d+) passed\\s+(\\d+) failed\\s+(\\d+) NC\\s+\\d+ us\\s+(PASS|FAIL)\\s*$");
    // suite line in STATUS.md (timings excluded): "rhi         51 passed   0 failed   0 NC   PASS"
    private static final Pattern DOC_SUITE = Pattern.compile(
            "^(\\w+)\\s+(\\d+) passed\\s+(\\d+) failed\\s+(\\d+) NC\\s+(PASS|FAIL)\\s*$");
    // TOTAL in a fresh run: "... suites green, 1003119 us"
    private static final Pattern RUN_TOTAL = Pattern.compile(
            "^TOTAL (\\d+) passed, (\\d+) failed, (\\d+) negative controls, (\\d+)/(\\d+) suites green(?:, \\d+ us)?\\s*$");
    // README quotes the same numbers without the "TOTAL " prefix, mid-sentence.
    private static final Pattern PROSE_TOTAL = Pattern.compile(
            "(\\d+) passed, (\\d+) failed, (\\d+) negative controls, (\\d+)/(\\d+) suites green");

    static final class SuiteReceipt {
        final String name;
        final int passed, failed, nc;
        final boolean pass;
        SuiteReceipt(String name, int passed, int failed, int nc, boolean pass) {
            this.name = name; this.passed = passed; this.failed = failed; this.nc = nc; this.pass = pass;
        }
    }

    static final class TotalReceipt {
        final int passed, failed, nc, green, count;
        TotalReceipt(int passed, int failed, int nc, int green, int count) {
            this.passed = passed; this.failed = failed; this.nc = nc; this.green = green; this.count = count;
        }
        String compact() { return passed + "/" + failed + ", " + green + "/" + count; }
    }

    private DriftCheck() { }

    public static void main(String[] args) throws Exception {
        Path status = Paths.get("docs/STATUS.md");
        Path readme = Paths.get("README.md");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--status") && i + 1 < args.length) status = Paths.get(args[++i]);
            else if (args[i].equals("--readme") && i + 1 < args.length) readme = Paths.get(args[++i]);
            else {
                System.err.println("drift-check: unknown argument '" + args[i] + "'");
                System.err.println("usage: drift-check [--status docs/STATUS.md] [--readme README.md]");
                System.exit(2);
            }
        }

        if (!Files.isRegularFile(status) || !Files.isRegularFile(readme)) {
            System.err.println("drift-check: cannot find " + status + " and/or " + readme
                    + "; run from the repository root");
            System.exit(2);
        }

        // ---- replay the host gate on this tool's own classpath ----------------
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "dev.auralite.render.tests.RunAll", "--receipts");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        List<String> lines = new ArrayList<String>();
        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) lines.add(line);
        if (!proc.waitFor(10, TimeUnit.MINUTES)) { proc.destroyForcibly(); System.exit(2); }
        int runExit = proc.exitValue();

        Map<String, SuiteReceipt> runSuites = new LinkedHashMap<String, SuiteReceipt>();
        TotalReceipt runTotal = null;
        for (String l : lines) {
            Matcher s = RUN_SUITE.matcher(l);
            if (s.matches()) {
                runSuites.put(s.group(1), new SuiteReceipt(s.group(1),
                        Integer.parseInt(s.group(2)), Integer.parseInt(s.group(3)),
                        Integer.parseInt(s.group(4)), s.group(5).equals("PASS")));
                continue;
            }
            Matcher t = RUN_TOTAL.matcher(l);
            if (t.matches()) {
                runTotal = new TotalReceipt(Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)),
                        Integer.parseInt(t.group(3)), Integer.parseInt(t.group(4)), Integer.parseInt(t.group(5)));
            }
        }
        if (runTotal == null || runSuites.isEmpty()) {
            System.err.println("drift-check: could not parse the replayed receipts; raw output:");
            for (String l : lines) System.err.println("  | " + l);
            System.exit(2);
        }
        if (runExit != 0) {
            System.err.println("drift-check: the host gate itself is red (exit " + runExit
                    + ", " + runTotal.passed + " passed / " + runTotal.failed + " failed);"
                    + " fix the gate before asking about drift");
            System.exit(2);
        }

        // ---- parse the docs ---------------------------------------------------
        Map<String, SuiteReceipt> statusSuites = new LinkedHashMap<String, SuiteReceipt>();
        List<TotalReceipt> statusTotals = new ArrayList<TotalReceipt>();
        List<TotalReceipt> readmeTotals = new ArrayList<TotalReceipt>();
        parseDoc(status, DOC_SUITE, true, statusSuites, statusTotals);
        parseProse(readme, readmeTotals);

        // ---- compare -----------------------------------------------------------
        List<String> drifts = new ArrayList<String>();

        for (SuiteReceipt r : runSuites.values()) {
            SuiteReceipt d = statusSuites.get(r.name);
            if (d == null) {
                drifts.add("suite '" + r.name + "': receipts say " + r.passed + " passed, docs/STATUS.md has no line for it");
                continue;
            }
            if (d.passed != r.passed) drifts.add("suite '" + r.name + "': receipts say " + r.passed
                    + " passed, docs/STATUS.md says " + d.passed);
            if (d.failed != r.failed) drifts.add("suite '" + r.name + "': receipts say " + r.failed
                    + " failed, docs/STATUS.md says " + d.failed);
            if (d.nc != r.nc) drifts.add("suite '" + r.name + "': negative controls drifted: receipts "
                    + r.nc + ", docs " + d.nc);
            if (d.pass && !r.pass) drifts.add("suite '" + r.name + "': receipts say FAIL, docs/STATUS.md says PASS");
        }
        for (String name : statusSuites.keySet()) {
            if (!runSuites.containsKey(name)) {
                drifts.add("suite '" + name + "': docs/STATUS.md lists it, the replayed gate does not run it");
            }
        }

        if (statusTotals.isEmpty()) {
            drifts.add("docs/STATUS.md: no TOTAL line to compare against");
        }
        for (TotalReceipt d : statusTotals) {
            if (d.passed != runTotal.passed) drifts.add("TOTAL passed: receipts say " + runTotal.passed
                    + ", docs/STATUS.md says " + d.passed);
            if (d.failed != runTotal.failed) drifts.add("TOTAL failed: receipts say " + runTotal.failed
                    + ", docs/STATUS.md says " + d.failed);
            if (d.nc != runTotal.nc) drifts.add("TOTAL negative controls: receipts say " + runTotal.nc
                    + ", docs/STATUS.md says " + d.nc);
            if (d.green != runTotal.green || d.count != runTotal.count) drifts.add("TOTAL suites: receipts say "
                    + runTotal.green + "/" + runTotal.count + ", docs/STATUS.md says " + d.green + "/" + d.count);
        }
        if (readmeTotals.isEmpty()) {
            drifts.add("README.md: no TOTAL line to compare against");
        }
        for (TotalReceipt d : readmeTotals) {
            if (d.passed != runTotal.passed) drifts.add("TOTAL passed: receipts say " + runTotal.passed
                    + ", README.md says " + d.passed);
            if (d.failed != runTotal.failed) drifts.add("TOTAL failed: receipts say " + runTotal.failed
                    + ", README.md says " + d.failed);
            if (d.nc != runTotal.nc) drifts.add("TOTAL negative controls: receipts say " + runTotal.nc
                    + ", README.md says " + d.nc);
            if (d.green != runTotal.green || d.count != runTotal.count) drifts.add("TOTAL suites: receipts say "
                    + runTotal.green + "/" + runTotal.count + ", README.md says " + d.green + "/" + d.count);
        }

        // ---- report ------------------------------------------------------------
        if (!drifts.isEmpty()) {
            for (String d : drifts) System.err.println("DRIFT: " + d);
            System.err.println("drift-check: " + drifts.size() + " drift(s); move the doc line with the number,"
                    + " or the number with the doc line");
            System.exit(1);
        }
        System.out.println("drift-check: " + runSuites.size() + " suite receipts + TOTAL match "
                + status + " and " + readme);
        System.out.println("drift-check: TOTAL " + runTotal.passed + " passed, " + runTotal.failed
                + " failed, " + runTotal.nc + " negative controls, " + runTotal.green + "/" + runTotal.count
                + " suites green");
        System.out.println("RESULT: PASS");
        System.exit(0);
    }

    /** Parses suite lines and TOTAL lines out of a doc, keeping file/line context out of scope (names are enough). */
    private static void parseDoc(Path file, Pattern suitePattern, boolean perSuite,
                                 Map<String, SuiteReceipt> suites, List<TotalReceipt> totals) throws IOException {
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher s = suitePattern.matcher(l);
            if (perSuite && s.matches()) {
                String name = s.group(1);
                // The doc TOTAL would not match DOC_SUITE; skip anything that reads like prose.
                if (name.equals("TOTAL")) continue;
                suites.put(name, new SuiteReceipt(name,
                        Integer.parseInt(s.group(2)), Integer.parseInt(s.group(3)),
                        Integer.parseInt(s.group(4)), s.group(5).equals("PASS")));
                continue;
            }
            Matcher t = RUN_TOTAL.matcher(l);
            if (t.matches()) {
                totals.add(new TotalReceipt(Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)),
                        Integer.parseInt(t.group(3)), Integer.parseInt(t.group(4)), Integer.parseInt(t.group(5))));
            }
        }
    }

    /** Finds every prose TOTAL ("967 passed, 0 failed, ...") anywhere in the file. */
    private static void parseProse(Path file, List<TotalReceipt> totals) throws IOException {
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher t = PROSE_TOTAL.matcher(l);
            while (t.find()) {
                totals.add(new TotalReceipt(Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)),
                        Integer.parseInt(t.group(3)), Integer.parseInt(t.group(4)), Integer.parseInt(t.group(5))));
            }
        }
    }
}
