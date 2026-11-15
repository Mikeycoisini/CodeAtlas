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
    "spring.datasource.url=${TEST_PROGRESS_DB_URL:jdbc:h2:mem:progress;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=target/progress-test-data"})
class ProgressIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired dev.codeatlas.store.AtlasStore store;
    @Autowired dev.codeatlas.progress.EvidenceService evidenceService;
    @Autowired javax.sql.DataSource dataSource;
    String key() { return UUID.randomUUID().toString(); }
    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        return http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    JsonNode ok(HttpMethod method, String path, Object body) {
        var response = call(method, path, body); assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue(); return response.getBody();
    }
    JsonNode knowledge(String title) { return ok(HttpMethod.POST, "/knowledge", Map.of("title", title, "body", "Understanding fixed return values", "tags", List.of(), "sourceIds", List.of(), "idempotencyKey", key())); }
    JsonNode card(JsonNode knowledge) { return ok(HttpMethod.POST, "/cards", Map.of("question", "What is returned?", "answer", "One", "knowledgeId", knowledge.get("id").asText(), "sourceIds", List.of(), "tags", List.of(), "idempotencyKey", key())); }
    JsonNode grade(JsonNode card, String rating) {
        String cardId = card.get("id").asText(); ok(HttpMethod.POST, "/reviews/cards/" + cardId + "/enroll", Map.of("idempotencyKey", key()));
        var started = ok(HttpMethod.POST, "/reviews/cards/" + cardId + "/attempts", Map.of("idempotencyKey", key()));
        return gradeAttempt(started, rating);
    }
    JsonNode gradeAttempt(JsonNode started, String rating) {
        String path = "/reviews/attempts/" + started.get("id").asText();
        var shown = ok(HttpMethod.POST, path + "/reveal", Map.of("revision", started.get("revision").asInt(), "idempotencyKey", key()));
        return ok(HttpMethod.POST, path + "/submit", Map.of("revision", shown.get("revision").asInt(), "rating", rating, "idempotencyKey", key()));
    }
    JsonNode progress(JsonNode knowledge) { return ok(HttpMethod.GET, "/progress/knowledge/" + knowledge.get("id").asText(), null); }
    JsonNode reflect(JsonNode knowledge, String rating) { return ok(HttpMethod.POST, "/progress/evidence", Map.of("knowledgeId", knowledge.get("id").asText(), "sourceType", "PERSONAL_REFLECTION", "rating", rating, "reflection", "My own judgement", "idempotencyKey", key())); }

    @Test void reviewEvidenceHasSeparateProvenanceAndSupportsRetractionAndKnowledgeVersions() {
        var knowledge = knowledge("Review evidence"); var card = card(knowledge);
        assertThat(progress(knowledge).get("assessment").get("status").asText()).isEqualTo("NO_EVIDENCE");
        grade(card, "GOOD"); grade(card, "EASY");
        var supported = progress(knowledge); assertThat(supported.get("assessment").get("status").asText()).isEqualTo("SUPPORTED");
        assertThat(supported.get("evidence")).hasSize(2);
        assertThat(supported.get("evidence").get(0).get("payload").get("source").asText()).isEqualTo("USER_SELF_REPORTED");
        String evidenceId = supported.get("evidence").get(0).get("id").asText();
        ok(HttpMethod.POST, "/progress/evidence/" + evidenceId + "/retract", Map.of("reason", "Wrong association", "idempotencyKey", key()));
        assertThat(progress(knowledge).get("assessment").get("status").asText()).isEqualTo("PRACTICING");
        ok(HttpMethod.PUT, "/knowledge/" + knowledge.get("id").asText(), Map.of("revision", 1, "title", "Review evidence updated", "body", "A changed principle", "tags", List.of(), "sourceIds", List.of()));
        var updated = progress(knowledge); assertThat(updated.get("assessment").get("status").asText()).isEqualTo("NO_EVIDENCE");
        assertThat(updated.get("historicalEvidenceCount").asInt()).isEqualTo(2);
        assertThat(ok(HttpMethod.GET, "/progress/knowledge/" + knowledge.get("id").asText() + "/history", null).get("total").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test void reflectionIsExplicitAndCannotForgeAiOrObjectiveEvidence() {
        var knowledge = knowledge("Self reflection"); reflect(knowledge, "UNDERSTOOD"); reflect(knowledge, "UNDERSTOOD");
        assertThat(progress(knowledge).get("assessment").get("status").asText()).isEqualTo("PRACTICING");
        reflect(knowledge, "NEEDS_REVIEW");
        assertThat(progress(knowledge).get("assessment").get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(call(HttpMethod.POST, "/progress/evidence", Map.of("knowledgeId", knowledge.get("id").asText(), "sourceType", "AI_ASSISTED", "rating", "CORRECT", "idempotencyKey", key())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/progress/evidence", Map.of("knowledgeId", knowledge.get("id").asText(), "sourceType", "PERSONAL_REFLECTION", "rating", "UNDERSTOOD", "verdict", "CORRECT", "idempotencyKey", key())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void reviewUsesKnowledgeFrozenAtStartAfterCardReassignment() {
        var old = knowledge("Old knowledge"); var replacement = knowledge("New knowledge"); var card = card(old);
        ok(HttpMethod.POST, "/reviews/cards/" + card.get("id").asText() + "/enroll", Map.of("idempotencyKey", key()));
        var started = ok(HttpMethod.POST, "/reviews/cards/" + card.get("id").asText() + "/attempts", Map.of("idempotencyKey", key()));
        ok(HttpMethod.PUT, "/cards/" + card.get("id").asText(), Map.of("revision", 1, "question", card.get("question").asText(), "answer", card.get("answer").asText(), "knowledgeId", replacement.get("id").asText(), "sourceIds", List.of(), "tags", List.of()));
        gradeAttempt(started, "GOOD");
        assertThat(progress(old).get("evidence")).hasSize(1);
        assertThat(progress(replacement).get("evidence")).isEmpty();
    }

    JsonNode waitTask(JsonNode task) throws Exception {
        for (int i = 0; i < 200; i++) { if (!List.of("QUEUED", "RUNNING").contains(task.get("status").asText())) { assertThat(task.get("status").asText()).isEqualTo("SUCCEEDED"); return task; } Thread.sleep(25); task = ok(HttpMethod.GET, "/tasks/" + task.get("id").asText(), null); }
        throw new AssertionError("timeout");
    }
    @Test void trainingResultsAreBoundToExactQuestionsAndAssistanceAndDeduplicated() throws Exception {
        var knowledge = knowledge("Training evidence");
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/progress-fixtures")), "source-").toAbsolutePath();
        Files.writeString(root.resolve("Example.java"), "class Example { int run() { return 1; } }");
        String snapshot = waitTask(ok(HttpMethod.POST, "/imports", Map.of("path", root.toString()))).get("snapshotId").asText();
        waitTask(ok(HttpMethod.POST, "/snapshots/" + snapshot + "/index", Map.of("rebuild", false)));
        var symbols = ok(HttpMethod.GET, "/snapshots/" + snapshot + "/symbols?kind=METHOD", null);
        var source = ok(HttpMethod.POST, "/sources", Map.of("snapshotId", snapshot, "generationId", symbols.get("generationId").asText(), "symbolId", symbols.get("items").get(0).get("id").asText()));
        String questionId = key();
        var question = Map.of("id", questionId, "format", "TRUE_FALSE", "category", "CODE_READING", "prompt", "Does run return 1?", "options", List.of(), "correctAnswer", true, "referenceExplanation", "Read return 1", "hints", List.of("Read return"));
        var task = ok(HttpMethod.POST, "/training/tasks", Map.of("title", "Bound source task", "goal", "Read return", "acceptanceCriteria", "Correctly read return", "status", "ACTIVE", "sourceIds", List.of(source.get("id").asText()), "questions", List.of(question), "idempotencyKey", key()));
        for (int index = 0; index < 4; index++) {
            var attempt = ok(HttpMethod.POST, "/training/tasks/" + task.get("id").asText() + "/attempts", Map.of("idempotencyKey", key()));
            String path = "/training/attempts/" + attempt.get("id").asText();
            if (index == 0) ok(HttpMethod.POST, path + "/reveal", Map.of("questionId", questionId, "kind", "HINT", "hintIndex", 0, "idempotencyKey", key()));
            ok(HttpMethod.POST, path + "/submit", Map.of("revision", 1, "answers", List.of(Map.of("questionId", questionId, "value", index != 3)), "idempotencyKey", key()));
            var association = Map.of("knowledgeId", knowledge.get("id").asText(), "sourceType", "TRAINING_RESULT", "sourceId", attempt.get("id").asText(), "questionId", questionId, "idempotencyKey", key());
            var evidence = ok(HttpMethod.POST, "/progress/evidence", association);
            var repeated = new LinkedHashMap<String, Object>(association); repeated.put("idempotencyKey", key());
            assertThat(ok(HttpMethod.POST, "/progress/evidence", repeated).get("id")).isEqualTo(evidence.get("id"));
            assertThat(progress(knowledge).get("assessment").get("status").asText()).isEqualTo(index == 2 ? "SUPPORTED" : index == 3 ? "NEEDS_REVIEW" : "PRACTICING");
        }
        assertThat(progress(knowledge).get("evidence")).hasSize(4);
        assertThat(ok(HttpMethod.GET, "/progress/mistakes", null).get("total").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test void mysqlWaitingEvidenceProjectionIncludesTheEarlierCommittedFact() throws Exception {
        try (var connection = dataSource.getConnection()) { org.junit.jupiter.api.Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName().equals("MySQL")); }
        var knowledge = knowledge("Concurrent projection"); String id = knowledge.get("id").asText();
        var written = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = threads.submit(() -> store.transaction(() -> {
                var result = evidenceService.associate(Map.of("knowledgeId", id, "sourceType", "PERSONAL_REFLECTION", "rating", "UNDERSTOOD", "idempotencyKey", key())); written.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); } catch (InterruptedException error) { throw new AssertionError(error); }
                return result;
            }));
            assertThat(written.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var reader = threads.submit(() -> evidenceService.associate(Map.of("knowledgeId", id, "sourceType", "PERSONAL_REFLECTION", "rating", "NEEDS_REVIEW", "idempotencyKey", key())));
            try {
                boolean waiting = false;
                for (int index = 0; index < 200; index++) {
                    waiting = store.rows("SHOW FULL PROCESSLIST").stream().anyMatch(row -> { String query = String.valueOf(row.get("info")); return query.contains(id) && query.contains("learning_records") && query.contains("FOR UPDATE"); });
                    if (waiting) break; Thread.sleep(20);
                }
                assertThat(waiting).as("second association must wait for knowledge lock").isTrue();
            } finally { release.countDown(); }
            writer.get(10, java.util.concurrent.TimeUnit.SECONDS); reader.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var result = progress(knowledge); assertThat(result.get("evidence")).hasSize(2);
            assertThat(result.get("assessment").get("activeEvidenceCount").asInt()).isEqualTo(2);
            assertThat(result.get("assessment").get("status").asText()).isEqualTo("NEEDS_REVIEW");
        } finally { release.countDown(); }
    }
}
