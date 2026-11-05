package com.example.memosync.cli;

import com.example.memosync.core.MemoRecord;
import com.example.memosync.core.MergeEngine;
import com.example.memosync.core.MergeResult;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI conformance runner for the shared merge cases.
 *
 * <p>Reads {@code ../shared/merge_cases.json} (or {@code --cases &lt;path&gt;}), runs
 * {@link MergeEngine} on every case, compares against the case's {@code expect} block, and
 * writes {@code out/results.json} (or {@code --out &lt;path&gt;}) in the contract format:
 *
 * <pre>{@code
 * {"results":[{"name":"watch_create","canonical":{"title":"W","body":"watch","deleted":false},
 *              "conflict":null}, ...]}
 * }</pre>
 *
 * <p>Exit code: 0 when every case matches, 1 on mismatches, 2 on I/O or parse errors.
 * The Lead's conformance harness ({@code memo-sync-poc/tools/run_conformance.py}) runs this as
 * {@code powershell -NoProfile -ExecutionPolicy Bypass -File run-merge-cases.ps1} from
 * {@code phone/}, so the default relative paths assume that working directory.
 */
public final class MergeCaseRunner {

    public static final Path DEFAULT_CASES_PATH = Paths.get("..", "shared", "merge_cases.json");
    public static final Path DEFAULT_OUT_PATH = Paths.get("out", "results.json");

    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private MergeCaseRunner() {
    }

    public static void main(String[] args) {
        Path casesPath = DEFAULT_CASES_PATH;
        Path outPath = DEFAULT_OUT_PATH;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (("--cases".equals(arg) || "--out".equals(arg)) && i + 1 < args.length) {
                Path value = Paths.get(args[++i]);
                if ("--cases".equals(arg)) {
                    casesPath = value;
                } else {
                    outPath = value;
                }
            } else if (arg.startsWith("--cases=")) {
                casesPath = Paths.get(arg.substring("--cases=".length()));
            } else if (arg.startsWith("--out=")) {
                outPath = Paths.get(arg.substring("--out=".length()));
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("Usage: MergeCaseRunner [--cases <merge_cases.json>] "
                        + "[--out <results.json>]");
                return;
            } else {
                System.err.println("Unknown argument: " + arg);
                System.exit(2);
                return;
            }
        }
        try {
            Outcome outcome = run(casesPath, outPath);
            for (String name : outcome.passedCaseNames) {
                System.out.println("PASS " + name);
            }
            for (String failure : outcome.failures) {
                System.out.println("FAIL " + failure);
            }
            System.out.println();
            System.out.println("merge cases: " + outcome.passed + "/" + outcome.total + " passed;"
                    + " wrote " + outcome.outputPath.toAbsolutePath());
            if (!outcome.ok()) {
                System.out.println("RESULT: FAIL");
                System.exit(1);
            }
            System.out.println("RESULT: PASS");
        } catch (IOException | RuntimeException e) {
            System.err.println("MergeCaseRunner failed: " + e);
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    /** Result of one full merge-case run (written file + pass/fail bookkeeping). */
    public static final class Outcome {
        public final List<JsonObject> results = new ArrayList<>();
        public final List<String> passedCaseNames = new ArrayList<>();
        public final List<String> failures = new ArrayList<>();
        public int total;
        public int passed;
        public int failed;
        public Path outputPath;
        public JsonObject root;

        public boolean ok() {
            return total > 0 && failed == 0 && passed == total;
        }
    }

    /**
     * Runs all cases and writes the results file.
     *
     * @throws IOException              when the cases file cannot be read or results written
     * @throws IllegalArgumentException when the cases JSON is malformed
     */
    public static Outcome run(Path casesPath, Path outPath) throws IOException {
        if (casesPath == null || !Files.isRegularFile(casesPath)) {
            throw new IOException("merge cases file not found: "
                    + (casesPath == null ? "null" : casesPath.toAbsolutePath()));
        }
        String casesJson = Files.readString(casesPath, StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(casesJson);
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException("merge cases must be a JSON array: " + casesPath);
        }
        JsonArray cases = parsed.getAsJsonArray();
        MergeEngine mergeEngine = new MergeEngine();

        Outcome outcome = new Outcome();
        JsonArray resultArray = new JsonArray();
        for (JsonElement caseElement : cases) {
            if (!caseElement.isJsonObject()) {
                throw new IllegalArgumentException("every merge case must be an object");
            }
            JsonObject testCase = caseElement.getAsJsonObject();
            String name = requiredString(testCase, "name");
            MemoRecord base = parseCaseRecord(optionalElement(testCase, "base"));
            MemoRecord phone = parseCaseRecord(optionalElement(testCase, "phone"));
            MemoRecord watch = parseCaseRecord(optionalElement(testCase, "watch"));

            MergeResult merged = mergeEngine.merge(base, phone, watch);

            JsonObject result = new JsonObject();
            result.addProperty("name", name);
            JsonObject canonicalJson = canonicalToJson(merged.canonical);
            result.add("canonical", canonicalJson == null ? JsonNull.INSTANCE : canonicalJson);
            JsonObject conflictJson = conflictToJson(merged.conflict);
            result.add("conflict", conflictJson == null ? JsonNull.INSTANCE : conflictJson);
            resultArray.add(result);
            outcome.results.add(result);
            outcome.total++;

            JsonObject expect = testCase.has("expect") && testCase.get("expect").isJsonObject()
                    ? testCase.getAsJsonObject("expect")
                    : new JsonObject();
            JsonObject expectedCanonical = expectedCanonical(expect);
            JsonObject expectedConflict = expectedConflict(expect);
            boolean canonicalOk = canonicalJson != null && canonicalJson.equals(expectedCanonical);
            boolean conflictOk = jsonObjectOrNullEquals(conflictJson, expectedConflict);
            if (canonicalOk && conflictOk) {
                outcome.passed++;
                outcome.passedCaseNames.add(name);
            } else {
                outcome.failed++;
                outcome.failures.add(name + ": expected " + expect
                        + " but got canonical=" + (canonicalJson == null ? "null" : canonicalJson)
                        + " conflict=" + (conflictJson == null ? "null" : conflictJson));
            }
        }

        JsonObject root = new JsonObject();
        root.add("results", resultArray);
        outcome.root = root;

        Path absoluteOut = outPath.toAbsolutePath();
        Path parent = absoluteOut.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(absoluteOut, PRETTY.toJson(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        outcome.outputPath = outPath;
        return outcome;
    }

    // ------------------------------------------------------------------
    // Long-key record JSON (shared/merge_cases.json format, design doc §3.1)
    // ------------------------------------------------------------------

    /** Parses one long-key {@link MemoRecord}; null/absent JSON element means "no record". */
    public static MemoRecord parseCaseRecord(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("record must be a JSON object or null");
        }
        JsonObject o = element.getAsJsonObject();
        MemoRecord record = new MemoRecord();
        record.id = stringOrNull(o, "id");
        record.title = stringOrNull(o, "title");
        record.body = stringOrNull(o, "body");
        record.created = stringOrNull(o, "created");
        record.updated = stringOrNull(o, "updated");
        record.titleHlc = stringOrNull(o, "titleHlc");
        record.bodyHlc = stringOrNull(o, "bodyHlc");
        record.deleted = boolOrNull(o, "deleted");
        record.deletedHlc = stringOrNull(o, "deletedHlc");
        record.rev = intOr(o, "rev", 0);
        record.base = stringOrNull(o, "base");
        record.force = boolOrNull(o, "f");
        return record;
    }

    // ------------------------------------------------------------------
    // Contract-format result normalization
    // ------------------------------------------------------------------

    private static JsonObject canonicalToJson(MemoRecord record) {
        if (record == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("title", record.title);
        o.addProperty("body", record.body);
        o.addProperty("deleted", record.deleted);
        return o;
    }

    private static JsonObject conflictToJson(MemoRecord record) {
        if (record == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("title", record.title);
        o.addProperty("body", record.body);
        return o;
    }

    private static JsonObject expectedCanonical(JsonObject expect) {
        JsonElement canonical = optionalElement(expect, "canonical");
        if (canonical == null || canonical.isJsonNull() || !canonical.isJsonObject()) {
            return null;
        }
        JsonObject src = canonical.getAsJsonObject();
        JsonObject o = new JsonObject();
        o.add("title", optionalElement(src, "title"));
        o.add("body", optionalElement(src, "body"));
        o.addProperty("deleted", boolOrNull(src, "deleted"));
        return o;
    }

    private static JsonObject expectedConflict(JsonObject expect) {
        JsonElement conflict = optionalElement(expect, "conflict");
        if (conflict == null || conflict.isJsonNull() || !conflict.isJsonObject()) {
            return null;
        }
        JsonObject src = conflict.getAsJsonObject();
        JsonObject o = new JsonObject();
        o.add("title", optionalElement(src, "title"));
        o.add("body", optionalElement(src, "body"));
        return o;
    }

    private static boolean jsonObjectOrNullEquals(JsonObject actual, JsonObject expected) {
        if (actual == null) {
            return expected == null;
        }
        return actual.equals(expected);
    }

    // ------------------------------------------------------------------
    // Tiny JSON helpers (explicit keys, no reflection)
    // ------------------------------------------------------------------

    private static JsonElement optionalElement(JsonObject o, String key) {
        if (o == null || !o.has(key)) {
            return null;
        }
        JsonElement e = o.get(key);
        return e instanceof JsonNull ? null : e;
    }

    private static String requiredString(JsonObject o, String key) {
        String value = stringOrNull(o, key);
        if (value == null) {
            throw new IllegalArgumentException("missing string field '" + key + "'");
        }
        return value;
    }

    private static String stringOrNull(JsonObject o, String key) {
        JsonElement e = optionalElement(o, key);
        return e == null ? null : e.getAsString();
    }

    private static boolean boolOrNull(JsonObject o, String key) {
        JsonElement e = optionalElement(o, key);
        if (e == null) {
            return false;
        }
        if (e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        return e.getAsInt() != 0;
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        JsonElement e = optionalElement(o, key);
        return e == null ? fallback : e.getAsInt();
    }
}
