package com.example.memosync.test;

import java.util.ArrayList;
import java.util.List;

/** Tiny test runner: named checks, PASS/FAIL lines, non-zero exit on failure. */
public final class TestRunner {

    private int passed;
    private int failed;
    private final List<String> failures = new ArrayList<>();

    public void test(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " -> " + t);
            System.out.println("FAIL " + name + " -> " + t);
            t.printStackTrace(System.out);
        }
    }

    public int passed() {
        return passed;
    }

    public int failed() {
        return failed;
    }

    public int total() {
        return passed + failed;
    }

    /** Prints a summary and exits non-zero when any check failed. */
    public void summaryAndExit(String suiteName) {
        System.out.println();
        System.out.println(suiteName + ": " + passed + "/" + (passed + failed) + " tests passed");
        if (failed == 0) {
            System.out.println("TESTS: PASS");
            return;
        }
        System.out.println("TESTS: FAIL");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }
}
