package dev.auralite.render.tests;

import java.util.ArrayList;
import java.util.List;

/**
 * The host-side test rig.
 *
 * <p>No JUnit, no reflection, no annotations: a suite is a {@code static void
 * run()} and an assertion is a method call, so {@code make test} needs nothing
 * but a JDK. That is deliberate -- the gates run in CI on a runner with no GPU,
 * no display and no dependency resolution, and they must not be able to fail
 * for a reason other than the code under test.
 *
 * <p>Every failure records the assertion, the expected value and the actual
 * one, and {@link #finish()} prints one line per fact plus a total, so a
 * regression reads as a diff rather than a stack trace.
 */
public final class TestRig {

    private TestRig() { }

    private static String suite = "";
    private static String section = "";
    private static int passed;
    private static int failed;
    private static int negativeControls;
    private static final List<String> failures = new ArrayList<String>();
    private static final List<String> receipts = new ArrayList<String>();

    public static void reset() {
        suite = ""; section = "";
        passed = 0; failed = 0; negativeControls = 0;
        failures.clear();
        receipts.clear();
    }

    public static void suite(String name) { suite = name; }
    public static void section(String name) { section = name; }

    /** Records a measured fact that the plan docs quote. */
    public static void receipt(String fact) { receipts.add("[" + suite + "] " + fact); }

    public static void check(boolean ok, String what) {
        if (ok) passed++;
        else fail(what, "true", "false");
    }

    public static void eq(long actual, long expected, String what) {
        if (actual == expected) passed++;
        else fail(what, Long.toString(expected), Long.toString(actual));
    }

    public static void eq(int actual, int expected, String what) { eq((long) actual, (long) expected, what); }

    public static void eq(Object actual, Object expected, String what) {
        if (expected == null ? actual == null : expected.equals(actual)) passed++;
        else fail(what, String.valueOf(expected), String.valueOf(actual));
    }

    public static void eq(String actual, String expected, String what) { eq((Object) actual, (Object) expected, what); }

    public static void near(double actual, double expected, double eps, String what) {
        if (Math.abs(actual - expected) <= eps) passed++;
        else fail(what, expected + " +/-" + eps, Double.toString(actual));
    }

    public static void notNull(Object o, String what) {
        if (o != null) passed++;
        else fail(what, "non-null", "null");
    }

    public static void isNull(Object o, String what) {
        if (o == null) passed++;
        else fail(what, "null", String.valueOf(o));
    }

    public static void contains(String haystack, String needle, String what) {
        if (haystack != null && haystack.contains(needle)) passed++;
        else fail(what, "contains '" + needle + "'", String.valueOf(haystack));
    }

    public static void notContains(String haystack, String needle, String what) {
        if (haystack == null || !haystack.contains(needle)) passed++;
        else fail(what, "does not contain '" + needle + "'", haystack);
    }

    /**
     * Asserts that {@code body} throws, and that the message names the reason.
     * A refusal with no reason is treated as a failure: this codebase does not
     * throw bare exceptions.
     */
    public static void throwsNamed(Runnable body, String expectedInMessage, String what) {
        try {
            body.run();
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            if (expectedInMessage == null || m.contains(expectedInMessage)) { passed++; return; }
            fail(what, "message contains '" + expectedInMessage + "'", m);
            return;
        }
        fail(what, "an exception naming '" + expectedInMessage + "'", "no exception");
    }

    /**
     * The negative control: revert the fix and the gate must redden.
     *
     * <p>Called with a body that is expected to FAIL. If it passes, the
     * assertion is not actually testing anything and that is reported.
     */
    public static void negativeControl(String what, Runnable bodyThatMustFail) {
        int before = failed;
        boolean threw = false;
        try {
            bodyThatMustFail.run();
        } catch (RuntimeException e) {
            threw = true;
        }
        // A reddened gate counts even when it only called check()/fail():
        // capture that BEFORE the rollback below, which resets the counter
        // (the old code re-tested `failed > before` after resetting it, so
        // only a throwing control could ever be credited).
        boolean reddened = failed > before;
        // Roll back whatever the control recorded so it does not pollute totals.
        if (reddened) {
            failures.remove(failures.size() - 1);
            failed = before;
        }
        if (threw || reddened) { negativeControls++; return; }
        fail("NEGATIVE CONTROL '" + what + "' did not redden -- the gate proves nothing", "failure", "success");
    }

    private static void fail(String what, String expected, String actual) {
        failed++;
        failures.add("  FAIL [" + suite + (section.isEmpty() ? "" : "/" + section) + "] " + what
                + "\n       expected: " + expected + "\n       actual:   " + actual);
    }

    public static int passed() { return passed; }
    public static int failed() { return failed; }
    public static int negativeControls() { return negativeControls; }
    public static List<String> failures() { return failures; }
    public static List<String> receipts() { return receipts; }

    /** Prints the summary. Returns a process exit code. */
    public static int finish() {
        for (String r : receipts) System.out.println(r);
        if (!failures.isEmpty()) {
            System.out.println();
            for (String f : failures) System.out.println(f);
        }
        System.out.println();
        System.out.println("assertions: " + passed + " passed, " + failed + " failed, "
                + negativeControls + " negative controls");
        System.out.println(failed == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        return failed == 0 ? 0 : 1;
    }
}
