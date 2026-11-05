package com.example.memosync.test;

import com.example.memosync.cli.MergeCaseRunner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * End-to-end tests for the shared conformance harness: runs the actual CLI code against
 * {@code ../shared/merge_cases.json}, checks 7/7, then validates the written
 * {@code out/results.json} against the cases' {@code expect} blocks independently.
 */
public final class MergeCasesTest {

    private MergeCasesTest() {
    }

    /** Finds the shared cases file both from phone/ and from the repo root. */
    static Path casesPath() {
        Path fromPhone = Paths.get("..", "shared", "merge_cases.json");
        if (Files.isRegularFile(fromPhone)) {
            return fromPhone;
        }
        Path fromRoot = Paths.get("memo-sync-poc", "shared", "merge_cases.json");
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        throw new AssertionError("cannot find shared/merge_cases.json from cwd "
                + Paths.get("").toAbsolutePath());
    }

    private static JsonArray loadCases() throws IOException {
        return JsonParser.parseString(Files.readString(casesPath(), StandardCharsets.UTF_8))
                .getAsJsonArray();
    }

    private static JsonObject normalizeCanonical(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject source = element.getAsJsonObject();
        JsonObject normalized = new JsonObject();
        normalized.add("title", source.get("title"));
        normalized.add("body", source.get("body"));
        normalized.add("deleted", source.get("deleted"));
        return normalized;
    }

    private static JsonObject normalizeConflict(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject source = element.getAsJsonObject();
        JsonObject normalized = new JsonObject();
        normalized.add("title", source.get("title"));
        normalized.add("body", source.get("body"));
        return normalized;
    }

    public static void register(TestRunner r) {
        r.test("merge-cases: CLI reports 7/7 and writes out/results.json", () -> {
            Path out = Paths.get("out", "results.json");
            MergeCaseRunner.Outcome outcome;
            try {
                outcome = MergeCaseRunner.run(casesPath(), out);
            } catch (IOException e) {
                throw new AssertionError("MergeCaseRunner.run failed: " + e, e);
            }
            Assertions.assertEqualsInt(7, outcome.total, "shared case count");
            Assertions.assertEqualsInt(7, outcome.passed, "passed cases");
            Assertions.assertEqualsInt(0, outcome.failed, "failed cases");
            Assertions.assertTrue(outcome.ok(), "outcome.ok()");
            Assertions.assertTrue(Files.isRegularFile(out), "results.json exists at " + out);
            Assertions.assertEqualsInt(7, outcome.results.size(), "results array size");
        });

        r.test("merge-cases: every shared case matches expect and result order", () -> {
            Path out = Paths.get("out", "results.json");
            MergeCaseRunner.Outcome outcome;
            try {
                outcome = MergeCaseRunner.run(casesPath(), out);
            } catch (IOException e) {
                throw new AssertionError("MergeCaseRunner.run failed: " + e, e);
            }

            JsonArray cases;
            JsonArray results;
            try {
                cases = loadCases();
                results = JsonParser.parseString(Files.readString(out, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonArray("results");
            } catch (IOException e) {
                throw new AssertionError("cannot reload cases/results: " + e, e);
            }
            Assertions.assertEqualsInt(cases.size(), results.size(), "one result per case");

            List<String> names = new ArrayList<>();
            for (int i = 0; i < cases.size(); i++) {
                JsonObject testCase = cases.get(i).getAsJsonObject();
                String name = testCase.get("name").getAsString();
                names.add(name);
                JsonObject result = results.get(i).getAsJsonObject();
                Assertions.assertEquals(name, result.get("name").getAsString(),
                        "result order at index " + i);

                JsonObject expect = testCase.getAsJsonObject("expect");
                JsonObject expectedCanonical = normalizeCanonical(expect.get("canonical"));
                JsonElement expectedConflictElement = expect.has("conflict")
                        ? expect.get("conflict") : null;
                JsonObject expectedConflict = expectedConflictElement == null
                        || expectedConflictElement.isJsonNull()
                        ? null : normalizeConflict(expectedConflictElement);
                JsonObject actualCanonical = normalizeCanonical(result.get("canonical"));
                JsonObject actualConflict = normalizeConflict(result.get("conflict"));
                Assertions.assertEquals(expectedCanonical, actualCanonical,
                        "canonical mismatch for case " + name);
                Assertions.assertEquals(expectedConflict, actualConflict,
                        "conflict mismatch for case " + name);
            }
            Assertions.assertEquals(java.util.Arrays.asList(
                    "watch_create", "phone_create", "different_fields", "same_field_conflict",
                    "delete_vs_edit", "edit_vs_delete", "same_content_no_conflict"),
                    names, "shared case order preserved");
        });

        r.test("merge-cases: edit_vs_delete pins deletion + newer-body contract", () -> {
            // Regression for the contract update: deleted=true while body still takes the
            // watch's newer body (field-level merge is independent from the tombstone flag).
            Path out = Paths.get("out", "results.json");
            MergeCaseRunner.Outcome outcome;
            try {
                outcome = MergeCaseRunner.run(casesPath(), out);
            } catch (IOException e) {
                throw new AssertionError("MergeCaseRunner.run failed: " + e, e);
            }
            JsonObject editVsDelete = null;
            for (JsonObject result : outcome.results) {
                if ("edit_vs_delete".equals(result.get("name").getAsString())) {
                    editVsDelete = result;
                }
            }
            Assertions.assertNotNull(editVsDelete, "edit_vs_delete result present");
            JsonObject canonical = editVsDelete.getAsJsonObject("canonical");
            Assertions.assertEquals("A", canonical.get("title").getAsString(), "title");
            Assertions.assertEquals("W", canonical.get("body").getAsString(), "newer watch body");
            Assertions.assertTrue(canonical.get("deleted").getAsBoolean(), "deleted wins");
            Assertions.assertTrue(editVsDelete.get("conflict").isJsonNull(), "no conflict copy");
        });

        r.test("merge-cases: results.json uses exactly the contract shortlist of keys", () -> {
            Path out = Paths.get("out", "results.json");
            try {
                MergeCaseRunner.run(casesPath(), out);
                JsonObject root = JsonParser.parseString(
                        Files.readString(out, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray results = root.getAsJsonArray("results");
                for (JsonElement element : results) {
                    JsonObject result = element.getAsJsonObject();
                    Assertions.assertTrue(result.has("name"), "name present");
                    Assertions.assertEqualsInt(3, result.get("canonical").getAsJsonObject().size(),
                            "canonical exposes title/body/deleted only");
                    JsonElement conflict = result.get("conflict");
                    if (!conflict.isJsonNull()) {
                        Assertions.assertEqualsInt(2, conflict.getAsJsonObject().size(),
                                "conflict exposes title/body only");
                    }
                }
            } catch (IOException e) {
                throw new AssertionError("run/read failed: " + e, e);
            }
        });
    }
}
