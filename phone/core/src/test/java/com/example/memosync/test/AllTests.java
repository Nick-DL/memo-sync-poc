package com.example.memosync.test;

/**
 * Entry point for the Java 17 POC test suite (no external test framework).
 *
 * <p>Run via {@code phone/run-tests.ps1}, which compiles {@code core/src/main/java} and
 * {@code core/src/test/java} with {@code javac}, then invokes this class.
 */
public final class AllTests {

    private AllTests() {
    }

    public static void main(String[] args) {
        TestRunner runner = new TestRunner();
        HlcTest.register(runner);
        MergeEngineTest.register(runner);
        ProtocolCodecTest.register(runner);
        ChunkingTest.register(runner);
        SyncManagerTest.register(runner);
        MergeCasesTest.register(runner);
        runner.summaryAndExit("phone Java core test suite");
    }
}
