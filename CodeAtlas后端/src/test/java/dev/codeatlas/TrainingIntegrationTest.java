package dev.codeatlas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TEST_TRAINING_DB_URL:jdbc:h2:mem:training;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=target/training-test-data"})
class TrainingIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired dev.codeatlas.store.AtlasStore store;
    @Autowired dev.codeatlas.training.TrainingService trainingService;
    @Autowired javax.sql.DataSource dataSource;

    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        return http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    JsonNode ok(HttpMethod method, String path, Object body) {
        var response = call(method, path, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }
    JsonNode waitTask(JsonNode task) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (!List.of("QUEUED", "RUNNING").contains(task.get("status").asText())) {
                assertThat(task.get("status").asText()).isEqualTo("SUCCEEDED"); return task;
            }
            Thread.sleep(25); task = ok(HttpMethod.GET, "/tasks/" + task.get("id").asText(), null);
        }
        throw new AssertionError("task timeout");
    }
    JsonNode source() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/training-fixtures")), "source-").toAbsolutePath();
        Files.writeString(root.resolve("Example.java"), "class Example { int run() { return 1; } }");
        String snapshot = waitTask(ok(HttpMethod.POST, "/imports", Map.of("path", root.toString()))).get("snapshotId").asText();
        waitTask(ok(HttpMethod.POST, "/snapshots/" + snapshot + "/index", Map.of("rebuild", false)));
        var page = ok(HttpMethod.GET, "/snapshots/" + snapshot + "/symbols?kind=METHOD", null);
        return ok(HttpMethod.POST, "/sources", Map.of("snapshotId", snapshot, "generationId", page.get("generationId").asText(), "symbolId", page.get("items").get(0).get("id").asText()));
    }
    Map<String, Object> question(String format) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", UUID.randomUUID().toString()); result.put("category", "CODE_READING"); result.put("format", format);
        result.put("prompt", "What does run return?"); result.put("hints", List.of("Read the return statement"));
        result.put("referenceExplanation", "The method returns the literal one.");
        result.put("options", format.equals("SINGLE_CHOICE") ? List.of(Map.of("id", "A", "text", "1"), Map.of("id", "B", "text", "2")) : List.of());
        result.put("correctAnswer", format.equals("TRUE_FALSE") ? true : format.equals("SINGLE_CHOICE") ? "A" : "It returns 1");
        return result;
    }
    Map<String, Object> definition(JsonNode source, List<Map<String, Object>> questions) {
        var result = new LinkedHashMap<String, Object>();
        result.put("title", "Read return value"); result.put("goal", "Understand a fixed source method");
        result.put("constraints", "Use source evidence"); result.put("acceptanceCriteria", "Explain the return value");
        result.put("status", "ACTIVE"); result.put("sourceIds", List.of(source.get("id").asText())); result.put("tags", List.of("Java"));
        result.put("questions", questions); result.put("idempotencyKey", UUID.randomUUID().toString()); return result;
    }
    JsonNode create(Map<String, Object> definition) { return ok(HttpMethod.POST, "/training/tasks", definition); }
    JsonNode start(String task) { return ok(HttpMethod.POST, "/training/tasks/" + task + "/attempts", Map.of("idempotencyKey", UUID.randomUUID().toString())); }
    Map<String, Object> submission(JsonNode attempt, List<Map<String, Object>> answers) {
        return Map.of("revision", attempt.get("revision").asInt(), "answers", answers, "idempotencyKey", UUID.randomUUID().toString());
    }

    @Test void omittedSubmitAnswersUseSavedDraftAndRemainFinal() throws Exception {
        var question = question("SINGLE_CHOICE"); var task = create(definition(source(), List.of(question)));
        var attempt = start(task.get("id").asText()); String path = "/training/attempts/" + attempt.get("id").asText();
        var saved = ok(HttpMethod.PUT, path, Map.of("revision", 1, "answers", List.of(Map.of("questionId", question.get("id"), "value", "A"))));
        var body = Map.of("revision", saved.get("revision").asInt(), "idempotencyKey", UUID.randomUUID().toString());
        var submitted = ok(HttpMethod.POST, path + "/submit", body);
        assertThat(submitted.get("answers").get(0).get("value").asText()).isEqualTo("A");
        assertThat(submitted.get("results").get(0).get("verdict").asText()).isEqualTo("CORRECT");
        assertThat(ok(HttpMethod.POST, path + "/submit", body).get("submissionDigest")).isEqualTo(submitted.get("submissionDigest"));
        assertThat(call(HttpMethod.POST, path + "/submit", Map.of("revision", submitted.get("revision").asInt(), "idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ok(HttpMethod.GET, path, null).get("answers")).isEqualTo(submitted.get("answers"));
    }

    @Test void explicitEmptySubmitAnswersClearSavedDraft() throws Exception {
        var question = question("SINGLE_CHOICE"); var task = create(definition(source(), List.of(question)));
        var attempt = start(task.get("id").asText()); String path = "/training/attempts/" + attempt.get("id").asText();
        var saved = ok(HttpMethod.PUT, path, Map.of("revision", 1, "answers", List.of(Map.of("questionId", question.get("id"), "value", "A"))));
        var submitted = ok(HttpMethod.POST, path + "/submit", Map.of("revision", saved.get("revision").asInt(), "answers", List.of(), "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(submitted.get("answers").get(0).get("value").isNull()).isTrue();
        assertThat(submitted.get("results").get(0).get("verdict").asText()).isEqualTo("UNANSWERED");
    }

    @Test void simultaneousSubmitRetriesKeepOneFinalAnswer() throws Exception {
        var question = question("SINGLE_CHOICE"); var task = create(definition(source(), List.of(question)));
        var attempt = start(task.get("id").asText()); String path = "/training/attempts/" + attempt.get("id").asText();
        var saved = ok(HttpMethod.PUT, path, Map.of("revision", 1, "answers", List.of(Map.of("questionId", question.get("id"), "value", "A"))));
        var body = Map.of("revision", saved.get("revision").asInt(), "idempotencyKey", UUID.randomUUID().toString());
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> { gate.await(); return call(HttpMethod.POST, path + "/submit", body); });
            var second = threads.submit(() -> { gate.await(); return call(HttpMethod.POST, path + "/submit", body); });
            gate.countDown();
            var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(a.getBody().get("submissionDigest")).isEqualTo(b.getBody().get("submissionDigest"));
            assertThat(a.getBody().get("answers").get(0).get("value").asText()).isEqualTo("A");
        }
    }

    @Test void practiceCopySubmissionAndUserReportStayBoundToCapturedContent() throws Exception {
        var source = source(); var task = create(definition(source, List.of(question("SHORT_ANSWER"))));
        String snapshotId = source.get("snapshotId").asText();
        String projectId = source.get("projectId").asText();
        Path original = Path.of((String) store.one("SELECT source_path FROM projects WHERE id=?", projectId).get("sourcePath")).resolve("Example.java");
        String originalHash = dev.codeatlas.snapshot.SnapshotScanner.hash(Files.readAllBytes(original));
        var body = Map.of("taskId", task.get("id").asText(), "snapshotId", snapshotId, "idempotencyKey", UUID.randomUUID().toString());
        var workspace = ok(HttpMethod.POST, "/practice/workspaces", body);
        assertThat(ok(HttpMethod.POST, "/practice/workspaces", body).get("id")).isEqualTo(workspace.get("id"));
        Path edited = Path.of(workspace.get("path").asText()).resolve("Example.java");
        Files.writeString(edited, "class Example { int run() { return 2; } }");
        var submitBody = Map.of("idempotencyKey", UUID.randomUUID().toString());
        var submitted = ok(HttpMethod.POST, "/practice/workspaces/" + workspace.get("id").asText() + "/submissions", submitBody);
        assertThat(submitted.get("changes")).hasSize(1);
        assertThat(submitted.get("changes").get(0).get("diff").asText()).contains("return 1", "return 2");
        assertThat(ok(HttpMethod.POST, "/practice/workspaces/" + workspace.get("id").asText() + "/submissions", submitBody).get("id")).isEqualTo(submitted.get("id"));
        assertThat(dev.codeatlas.snapshot.SnapshotScanner.hash(Files.readAllBytes(original))).isEqualTo(originalHash);
        Files.writeString(edited, "class Example { int run() { return 3; } }");
        Path archive = Path.of(submitted.get("archivePath").asText()).resolve("Example.java");
        assertThat(Files.readString(archive)).contains("return 2");
        var reportBody = Map.of("status", "FAILED", "method", "IDE test run", "environment", "local JDK", "report", "Expected 1, got 2", "idempotencyKey", UUID.randomUUID().toString());
        var report = ok(HttpMethod.POST, "/practice/submissions/" + submitted.get("id").asText() + "/test-results", reportBody);
        assertThat(report.get("source").asText()).isEqualTo("USER_REPORTED");
        assertThat(report.get("contentDigest")).isEqualTo(submitted.get("contentDigest"));
        assertThat(ok(HttpMethod.POST, "/practice/submissions/" + submitted.get("id").asText() + "/test-results", reportBody).get("id")).isEqualTo(report.get("id"));
        assertThat(ok(HttpMethod.GET, "/practice/submissions/" + submitted.get("id").asText(), null).get("testResults")).hasSize(1);
        Files.writeString(archive, "tampered archive");
        assertThat(call(HttpMethod.GET, "/practice/submissions/" + submitted.get("id").asText(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ok(HttpMethod.POST, "/practice/workspaces/" + workspace.get("id").asText() + "/close", Map.of("idempotencyKey", UUID.randomUUID().toString()));
        assertThat(Files.exists(Path.of(workspace.get("path").asText()))).isFalse();
        assertThat(dev.codeatlas.snapshot.SnapshotScanner.hash(Files.readAllBytes(original))).isEqualTo(originalHash);
    }

    @Test void taskRevisionsFreezeAnswersAndSourceWhileEditsAndArchiveRemainIndependent() throws Exception {
        var source = source(); var question = question("SINGLE_CHOICE"); var input = definition(source, List.of(question));
        var task = create(input); String id = task.get("id").asText();
        assertThat(create(input).get("id")).isEqualTo(task.get("id"));
        var altered = new LinkedHashMap<>(input); altered.put("title", "different");
        assertThat(call(HttpMethod.POST, "/training/tasks", altered).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var publicTask = ok(HttpMethod.GET, "/training/tasks/" + id, null);
        assertThat(publicTask.get("questions").get(0).has("correctAnswer")).isFalse();
        assertThat(publicTask.get("questions").get(0).has("hints")).isFalse();
        assertThat(ok(HttpMethod.GET, "/training/tasks/" + id + "/editor", null).get("questions").get(0).get("correctAnswer").asText()).isEqualTo("A");
        var attempt = start(id);
        altered = new LinkedHashMap<>(input); altered.put("revision", task.get("revision").asInt());
        var revisedQuestion = new LinkedHashMap<>(question); revisedQuestion.put("correctAnswer", "B"); altered.put("questions", List.of(revisedQuestion));
        var edited = ok(HttpMethod.PUT, "/training/tasks/" + id, altered);
        assertThat(edited.get("revision").asInt()).isEqualTo(2);
        assertThat(call(HttpMethod.PUT, "/training/tasks/" + id, altered).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var body = submission(attempt, List.of(Map.of("questionId", question.get("id"), "value", "A")));
        var done = ok(HttpMethod.POST, "/training/attempts/" + attempt.get("id").asText() + "/submit", body);
        assertThat(done.get("results").get(0).get("verdict").asText()).isEqualTo("CORRECT");
        assertThat(done.get("taskRevision").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.POST, "/training/attempts/" + attempt.get("id").asText() + "/submit", body).get("submissionDigest")).isEqualTo(done.get("submissionDigest"));
        assertThat(done.get("task").get("sources").get(0).get("sha256")).isEqualTo(source.get("sha256"));
        altered.put("revision", 2); altered.put("status", "ARCHIVED"); ok(HttpMethod.PUT, "/training/tasks/" + id, altered);
        assertThat(call(HttpMethod.POST, "/training/tasks/" + id + "/attempts", Map.of("idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ok(HttpMethod.GET, "/training/attempts/" + attempt.get("id").asText(), null).get("results")).isEqualTo(done.get("results"));
    }

    @Test void recordsRevealBeforeSubmissionAndKeepsLaterRevealOutOfOriginalAssistance() throws Exception {
        var question = question("TRUE_FALSE"); var task = create(definition(source(), List.of(question))); var attempt = start(task.get("id").asText());
        String path = "/training/attempts/" + attempt.get("id").asText();
        var hint = Map.of("questionId", question.get("id"), "kind", "HINT", "hintIndex", 0, "idempotencyKey", UUID.randomUUID().toString());
        assertThat(ok(HttpMethod.POST, path + "/reveal", hint).get("content").asText()).contains("return");
        ok(HttpMethod.POST, path + "/reveal", hint);
        var current = ok(HttpMethod.GET, path, null);
        assertThat(current.get("reveals")).hasSize(1);
        var done = ok(HttpMethod.POST, path + "/submit", submission(current, List.of(Map.of("questionId", question.get("id"), "value", true))));
        assertThat(done.get("results").get(0).get("source").asText()).isEqualTo("RULE_BASED");
        assertThat(done.get("results").get(0).get("hintViewed").asBoolean()).isTrue();
        assertThat(done.get("results").get(0).get("referenceViewed").asBoolean()).isFalse();
        ok(HttpMethod.POST, path + "/reveal", Map.of("questionId", question.get("id"), "kind", "REFERENCE", "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(ok(HttpMethod.GET, path, null).get("results")).isEqualTo(done.get("results"));
        assertThat(call(HttpMethod.PUT, path, Map.of("revision", done.get("revision").asInt(), "answers", List.of())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void shortAnswerDraftSelfAssessmentAndDeletedTaskHistoryStayRecoverable() throws Exception {
        var question = question("SHORT_ANSWER"); var task = create(definition(source(), List.of(question))); var attempt = start(task.get("id").asText());
        String path = "/training/attempts/" + attempt.get("id").asText();
        var answers = List.of(Map.<String, Object>of("questionId", question.get("id"), "value", "Returns the literal one"));
        var draft = ok(HttpMethod.PUT, path, Map.of("revision", attempt.get("revision").asInt(), "answers", answers));
        assertThat(ok(HttpMethod.GET, path, null).get("answers").get(0).get("value").asText()).contains("literal");
        var done = ok(HttpMethod.POST, path + "/submit", submission(draft, answers));
        assertThat(done.get("results").get(0).get("verdict").asText()).isEqualTo("NEEDS_REVIEW");
        var assessment = Map.of("questionId", question.get("id"), "rating", "PARTIAL", "reflection", "Need more practice", "idempotencyKey", UUID.randomUUID().toString());
        ok(HttpMethod.POST, path + "/self-assessments", assessment); ok(HttpMethod.POST, path + "/self-assessments", assessment);
        ok(HttpMethod.DELETE, "/training/tasks/" + task.get("id").asText(), null);
        var restored = ok(HttpMethod.GET, path, null);
        assertThat(restored.get("selfAssessments")).hasSize(1);
        assertThat(restored.get("selfAssessments").get(0).get("source").asText()).isEqualTo("USER_SELF_REPORTED");
        assertThat(restored.get("task").get("title").asText()).isEqualTo("Read return value");
        assertThat(call(HttpMethod.GET, "/training/tasks/" + task.get("id").asText(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void validatesPublicationAndAnswersAtomicallyAndSupportsSkip() throws Exception {
        var input = definition(source(), List.of(question("SINGLE_CHOICE")));
        var bad = new LinkedHashMap<>(input); bad.put("questions", List.of());
        assertThat(call(HttpMethod.POST, "/training/tasks", bad).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        bad.put("status", "DRAFT"); var task = create(bad);
        assertThat(call(HttpMethod.POST, "/training/tasks/" + task.get("id").asText() + "/attempts", Map.of("idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        input.put("idempotencyKey", UUID.randomUUID().toString());
        var active = create(input); var attempt = start(active.get("id").asText()); String path = "/training/attempts/" + attempt.get("id").asText();
        assertThat(call(HttpMethod.POST, path + "/submit", submission(attempt, List.of(Map.of("questionId", "forged", "value", "A")))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ok(HttpMethod.GET, path, null).get("status").asText()).isEqualTo("DRAFT");
        String questionId = active.get("questions").get(0).get("id").asText();
        var draft = ok(HttpMethod.PUT, path, Map.of("revision", attempt.get("revision").asInt(), "answers", List.of(Map.of("questionId", questionId, "value", "A"))));
        var skipped = ok(HttpMethod.POST, path + "/skip", Map.of("revision", draft.get("revision").asInt(), "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(skipped.get("status").asText()).isEqualTo("SKIPPED");
        assertThat(skipped.get("answers").get(0).get("value").asText()).isEqualTo("A");
        assertThat(ok(HttpMethod.GET, "/training/tasks?query=Read", null).get("total").asInt()).isGreaterThan(0);
    }

    @Test void historicalQuestionAndAnswerRemainVisibleWhenSnapshotContentIsUnavailable() throws Exception {
        var source = source(); var question = question("SHORT_ANSWER"); var task = create(definition(source, List.of(question)));
        var attempt = start(task.get("id").asText()); String path = "/training/attempts/" + attempt.get("id").asText();
        var draft = ok(HttpMethod.PUT, path, Map.of("revision", 1, "answers", List.of(Map.of("questionId", question.get("id"), "value", "My answer"))));
        var snapshot = store.snapshot(source.get("snapshotId").asText());
        Path file = Path.of("target/training-test-data/snapshots").resolve(snapshot.get("storageKey").toString()).resolve(source.get("filePath").asText());
        byte[] original = Files.readAllBytes(file);
        try {
            Files.writeString(file, "corrupted fixture");
            var restored = ok(HttpMethod.GET, path, null);
            assertThat(restored.get("task").get("sources").get(0).get("availability").asText()).isEqualTo("UNAVAILABLE");
            assertThat(restored.get("answers")).isEqualTo(draft.get("answers"));
            assertThat(restored.get("task").get("questions").get(0).get("prompt").asText()).contains("run");
        } finally { Files.write(file, original); }
    }

    @Test void concurrentRevealAndSubmitUseTheSameAttemptBoundary() throws Exception {
        var question = question("TRUE_FALSE"); var task = create(definition(source(), List.of(question))); var attempt = start(task.get("id").asText());
        String path = "/training/attempts/" + attempt.get("id").asText();
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var reveal = threads.submit(() -> { gate.await(); return ok(HttpMethod.POST, path + "/reveal", Map.of("questionId", question.get("id"), "kind", "REFERENCE", "idempotencyKey", UUID.randomUUID().toString())); });
            var submit = threads.submit(() -> { gate.await(); return ok(HttpMethod.POST, path + "/submit", submission(attempt, List.of(Map.of("questionId", question.get("id"), "value", true)))); });
            gate.countDown();
            var viewed = reveal.get(10, java.util.concurrent.TimeUnit.SECONDS); var done = submit.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean before = !java.time.Instant.parse(viewed.get("createdAt").asText()).isAfter(java.time.Instant.parse(done.get("submittedAt").asText()));
            assertThat(done.get("results").get(0).get("referenceViewed").asBoolean()).isEqualTo(before);
        }
    }

    @Test void mysqlStartWaitingForTaskEditSeesTheCommittedVersion() throws Exception {
        try (var connection = dataSource.getConnection()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName().equals("MySQL"));
        }
        var source = source(); var question = question("TRUE_FALSE");
        var task = create(definition(source, List.of(question))); String id = task.get("id").asText();
        var changed = definition(source, List.of(question)); changed.put("revision", 1); changed.put("title", "New committed version");
        var saved = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = threads.submit(() -> store.transaction(() -> {
                var result = trainingService.updateTask(id, changed); saved.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return result;
            }));
            assertThat(saved.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var started = threads.submit(() -> trainingService.start(id, Map.of("idempotencyKey", UUID.randomUUID().toString())));
            try {
                boolean waiting = false;
                for (int index = 0; index < 200; index++) {
                    waiting = store.rows("SHOW FULL PROCESSLIST").stream().anyMatch(row -> {
                        String query = String.valueOf(row.get("info"));
                        return query.contains(id) && query.contains("training_tasks") && query.contains("FOR UPDATE");
                    });
                    if (waiting) break;
                    Thread.sleep(20);
                }
                if (started.isDone()) started.get();
                assertThat(waiting).as("start must wait for the uncommitted edit").isTrue();
            } finally { release.countDown(); }
            assertThat(edit.get(10, java.util.concurrent.TimeUnit.SECONDS).get("revision")).isEqualTo(2);
            var attempt = started.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(attempt.get("taskRevision")).isEqualTo(2);
            assertThat(((Map<?, ?>) attempt.get("task")).get("title")).isEqualTo("New committed version");
        } finally { release.countDown(); }
    }
    @Test void projectScopeReachesHttpTaskListsAndRejectsForeignSources() throws Exception {
        var a=source(); var b=source();
        String pa=a.get("projectId").asText(), pb=b.get("projectId").asText();
        var ta=ok(HttpMethod.POST,"/training/tasks?projectId="+pa,definition(a,List.of(question("TRUE_FALSE"))));
        ok(HttpMethod.POST,"/training/tasks?projectId="+pb,definition(b,List.of(question("TRUE_FALSE"))));
        var page=ok(HttpMethod.GET,"/training/tasks?projectId="+pa,null);
        assertThat(page.get("total").asInt()).isEqualTo(1);
        assertThat(call(HttpMethod.GET,"/training/tasks/"+ta.get("id").asText()+"?projectId="+pb,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(page.get("items").get(0).get("id")).isEqualTo(ta.get("id"));
        assertThat(call(HttpMethod.POST,"/training/tasks?projectId="+pa,definition(b,List.of(question("TRUE_FALSE")))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
