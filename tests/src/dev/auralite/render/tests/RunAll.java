package dev.auralite.render.tests;

/**
 * Runs every host-side suite in one JVM and reports one total.
 *
 * <p>Each suite gets a clean rig, so a failure in one cannot mask or inflate
 * another. The exit code is the number of failed suites, which is what
 * {@code make test} and CI gate on.
 */
public final class RunAll {

    private interface Suite {
        String name();
        void run();
    }

    private static Suite suite(final String name, final Runnable body) {
        return new Suite() {
            @Override public String name() { return name; }
            @Override public void run() { body.run(); }
        };
    }

    public static void main(String[] args) {
        Suite[] suites = {
            suite("rhi", new Runnable() { @Override public void run() { RhiSuite.run(); } }),
            suite("graph", new Runnable() { @Override public void run() { GraphSuite.run(); } }),
            suite("uniform", new Runnable() { @Override public void run() { UniformSuite.run(); } }),
            suite("biome", new Runnable() { @Override public void run() { BiomeSuite.run(); } }),
            suite("nulldev", new Runnable() { @Override public void run() { NullDeviceSuite.run(); } }),
            suite("pack", new Runnable() { @Override public void run() { PackSuite.run(); } }),
            suite("tables", new Runnable() { @Override public void run() { BackendTableSuite.run(); } }),
        };

        boolean onlyReceipts = args.length > 0 && args[0].equals("--receipts");
        int totalPassed = 0, totalFailed = 0, totalNc = 0, failedSuites = 0;
        long t0 = System.nanoTime();

        System.out.println("AuraLite-Render host test gates");
        System.out.println("java " + System.getProperty("java.version")
                + " on " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println();

        for (Suite s : suites) {
            TestRig.reset();
            long st = System.nanoTime();
            Throwable crash = null;
            try {
                s.run();
            } catch (Throwable t) {
                crash = t;
            }
            long micros = (System.nanoTime() - st) / 1000L;
            int passed = TestRig.passed(), failed = TestRig.failed();
            if (crash != null) failed++;
            totalPassed += passed; totalFailed += failed; totalNc += TestRig.negativeControls();

            System.out.printf("%-9s %4d passed  %2d failed  %2d NC  %6d us  %s%n",
                    s.name(), passed, failed, TestRig.negativeControls(), micros,
                    failed == 0 ? "PASS" : "FAIL");

            if (!onlyReceipts) {
                for (String r : TestRig.receipts()) System.out.println(indent(r));
            }
            for (String f : TestRig.failures()) System.out.println(f);
            if (crash != null) {
                System.out.println("  CRASH [" + s.name() + "] " + crash);
                for (StackTraceElement e : crash.getStackTrace()) {
                    if (e.getClassName().startsWith("dev.auralite")) System.out.println("    at " + e);
                }
            }
            if (failed > 0) failedSuites++;
        }

        if (onlyReceipts) {
            System.out.println();
            System.out.println("receipts (rerun without --receipts to see them per suite):");
        }
        System.out.println();
        System.out.printf("TOTAL %d passed, %d failed, %d negative controls, %d/%d suites green, %d us%n",
                totalPassed, totalFailed, totalNc, suites.length - failedSuites, suites.length,
                (System.nanoTime() - t0) / 1000L);
        System.out.println(totalFailed == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(totalFailed == 0 ? 0 : 1);
    }

    private static String indent(String s) {
        return "    " + s.replace("\n", "\n    ");
    }
}
