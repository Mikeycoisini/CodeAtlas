package dev.codeatlas;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TEST_AI_DB_URL:jdbc:h2:mem:ai;MODE=MySQL;DB_CLOSE_DELAY=-1}", "spring.datasource.username=${TEST_DB_USER:sa}",
    "spring.datasource.password=${TEST_DB_PASSWORD:}", "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}",
    "codeatlas.data=target/ai-test-data", "codeatlas.ai.service-token=fixture-token", "codeatlas.ai.timeout-seconds=5"})
class AiIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static HttpServer server;
    static final AtomicInteger calls = new AtomicInteger();
    static volatile String mode = "NORMAL";
    static volatile String fingerprint = "f".repeat(64);
    static volatile CountDownLatch entered = new CountDownLatch(1);
    static volatile CountDownLatch release = new CountDownLatch(1);
    @Autowired TestRestTemplate http;
    @Autowired dev.codeatlas.store.AtlasStore store;
    @Autowired PreviewStore previewStore;
    @org.springframework.boot.test.context.TestConfiguration static class PreviewConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        PreviewStore previewStore(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.support.TransactionTemplate tx, ObjectMapper json) { return new PreviewStore(jdbc, tx, json); }
    }
    static class PreviewStore extends dev.codeatlas.store.AtlasStore {
        private volatile String target;
        private CountDownLatch read;
        private CountDownLatch resume;
        PreviewStore(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.support.TransactionTemplate tx, ObjectMapper json) { super(jdbc, tx, json); }
        public void arm(String id) { target = id; read = new CountDownLatch(1); resume = new CountDownLatch(1); }
        public boolean awaitRead() throws InterruptedException { return read.await(5, TimeUnit.SECONDS); }
        public void resumeRead() { resume.countDown(); }
        @Override public Map<String, Object> one(String sql, Object... args) {
            var row = super.one(sql, args);
            if (sql.startsWith("SELECT id,title,payload,revision FROM learning_records") && args.length > 0 && args[0].equals(target)) {
                target = null; read.countDown();
                try { if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("preview timeout"); } catch (InterruptedException error) { throw new AssertionError(error); }
            }
            return row;
        }
    }

    @Test void guidePreviewKeepsKnowledgeBodyAndEvidenceAtOneRevision() throws Exception {
        var knowledge = ok(HttpMethod.POST, "/api/knowledge", Map.of("title", "Snapshot", "body", "Original body", "sourceIds", List.of(), "idempotencyKey", UUID.randomUUID().toString()));
        String knowledgeId = knowledge.get("id").asText();
        var guide = ok(HttpMethod.POST, "/api/guides", Map.of("title", "Snapshot guide", "goal", "Keep coherent knowledge", "sourceIds", List.of(), "knowledgeIds", List.of(knowledgeId), "idempotencyKey", UUID.randomUUID().toString()));
        previewStore.arm(knowledgeId);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var preview = threads.submit(() -> ok(HttpMethod.POST, "/api/guides/" + guide.get("id").asText() + "/ai-context", Map.of("revision", 1, "sourceIds", List.of(), "knowledgeIds", List.of(knowledgeId))));
            assertThat(previewStore.awaitRead()).isTrue();
            var edit = threads.submit(() -> ok(HttpMethod.PUT, "/api/knowledge/" + knowledgeId, Map.of("revision", 1, "title", "Snapshot", "body", "New body", "sourceIds", List.of())));
            try { edit.get(500, TimeUnit.MILLISECONDS); } catch (TimeoutException expectedLock) { /* Coherent preview holds the knowledge lock. */ }
            finally { previewStore.resumeRead(); }
            var frozen = JSON.readTree(preview.get(10, TimeUnit.SECONDS).get("contextJson").asText()).get("guide").get("knowledge").get(0);
            assertThat(frozen.get("bodyExcerpt").asText()).isEqualTo("Original body");
            assertThat(frozen.get("revision")).isEqualTo(frozen.get("evidenceSummary").get("knowledgeRevision"));
            assertThat(edit.get(10, TimeUnit.SECONDS).get("revision").asInt()).isEqualTo(2);
        } finally { previewStore.resumeRead(); }
    }

    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/internal/provider", exchange -> {
                byte[] bytes = JSON.writeValueAsBytes(Map.of("provider", "fixture", "model", "fixture-model", "baseUrl", "https://provider.test", "configured", true,
                        "fingerprint", fingerprint, "maxTokens", 4096, "timeoutSeconds", 5));
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.createContext("/internal/generate", exchange -> {
                calls.incrementAndGet();
                var request = JSON.readTree(exchange.getRequestBody());
                var context = JSON.readTree(request.get("contextJson").asText());
                var source = context.get("sources").get(0);
                if (mode.equals("DELAY")) {
                    entered.countDown();
                    try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                Object result;
                int status = 200;
                if (mode.equals("ERROR")) { status = 503; result = Map.of("code", "PROVIDER_TIMEOUT", "message", "模型请求超时"); }
                else if (context.path("operation").asText().equals("LEARNING_GUIDE")) {
                    var suggestions = new ArrayList<Map<String, Object>>();
                    for (var step : context.get("guide").get("steps")) suggestions.add(Map.of("stepId", step.get("id").asText(), "reason", "Based on goal", "actionAdvice", "Read the linked material"));
                    Collections.reverse(suggestions);
                    if (mode.equals("FORGED_STEP")) suggestions.set(0, Map.of("stepId", "invented", "reason", "Guess", "actionAdvice", "Read"));
                    result = Map.of("result", Map.of("summary", "Try this learning sequence", "steps", suggestions, "citations", List.of(), "uncertainties", List.of("User can adjust")));
                }
                else {
                    var citation = new LinkedHashMap<String, Object>();
                    citation.put("sourceId", source.get("sourceId").asText());
                    citation.put("filePath", mode.equals("FORGED") ? "invented.java" : source.get("filePath").asText());
                    citation.put("startLine", source.get("startLine").asInt());
                    citation.put("endLine", mode.equals("RANGE") ? 999999 : source.get("endLine").asInt());
                    citation.put("explanation", "源码位置");
                    result = Map.of("result", Map.of("title", "方法解释", "summary", "静态源码说明", "keyPoints", List.of("声明"),
                            "explanation", "根据给定源码解释方法。", "citations", List.of(citation), "uncertainties", List.of("运行时依赖需要核对")),
                            "usage", Map.of("prompt_tokens", 100, "completion_tokens", 50));
                    if (context.path("operation").asText().equals("TRAINING_QUESTIONS")) {
                        result = Map.of("result", Map.of("title", "AI reading task", "goal", "Read source", "constraints", "Use the snippet", "acceptanceCriteria", "Identify the method",
                                "questions", List.of(Map.of("category", "CODE_READING", "format", "TRUE_FALSE", "prompt", "Does run print 1?", "options", List.of(), "correctAnswer", true,
                                        "referenceExplanation", "The source calls println(1).", "hints", List.of("Read the call"))), "citations", List.of(citation), "uncertainties", List.of()));
                    } else if (context.path("operation").asText().equals("TRAINING_FEEDBACK")) {
                        result = Map.of("result", Map.of("strengths", List.of("A clear attempt"), "gaps", List.of("Check the call"), "explanation", "Verify the returned suggestion against source",
                                "suggestedVerdict", "CORRECT", "citations", List.of(citation), "uncertainties", List.of()));
                    } else if (context.path("operation").asText().equals("PRACTICE_FEEDBACK")) {
                        result = Map.of("result", Map.of("strengths", List.of("Independent code change"), "gaps", List.of("Add a boundary test"), "explanation", "Review the diff against the user report",
                                "suggestedVerdict", "PARTIAL", "citations", List.of(citation), "uncertainties", List.of("User-provided report")));
                    }
                }
                byte[] bytes = JSON.writeValueAsBytes(result);
                try { exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) { registry.add("codeatlas.ai.url", () -> "http://127.0.0.1:" + server.getAddress().getPort()); }
    @BeforeEach void reset() { calls.set(0); mode = "NORMAL"; fingerprint = "f".repeat(64); entered = new CountDownLatch(1); release = new CountDownLatch(1); }
    @AfterEach void finish() { release.countDown(); }
    @AfterAll static void close() { server.stop(0); }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    JsonNode ok(HttpMethod method, String path, Object body) {
        var response = call(method, path, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue(); return response.getBody();
    }
    JsonNode waitFor(String path) throws Exception {
        for (int i = 0; i < 240; i++) {
            var task = ok(HttpMethod.GET, path, null);
            if (!List.of("QUEUED", "RUNNING").contains(task.get("status").asText())) return task;
            Thread.sleep(25);
        }
        throw new AssertionError("task timeout");
    }
    JsonNode context() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/ai-fixtures")), "source-").toAbsolutePath();
        Files.writeString(root.resolve("Example.java"), "class Example { void run() { System.out.println(1); } }");
        var imported = ok(HttpMethod.POST, "/api/imports", Map.of("path", root.toString()));
        String snapshot = waitFor("/api/tasks/" + imported.get("id").asText()).get("snapshotId").asText();
        var indexed = ok(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", false));
        waitFor("/api/tasks/" + indexed.get("id").asText());
        var page = ok(HttpMethod.GET, "/api/snapshots/" + snapshot + "/symbols?kind=METHOD", null);
        return ok(HttpMethod.POST, "/api/ai/contexts", Map.of("snapshotId", snapshot, "generationId", page.get("generationId").asText(),
                "symbolId", page.get("items").get(0).get("id").asText(), "question", "解释方法", "depth", "STANDARD", "purpose", "METHOD_EXPLANATION"));
    }
    JsonNode consent(JsonNode context, String decision) { return ok(HttpMethod.POST, "/api/ai/consents", Map.of("contextId", context.get("id").asText(), "decision", decision)); }
    JsonNode generate(JsonNode consent) { return ok(HttpMethod.POST, "/api/ai/generations", Map.of("consentId", consent.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString())); }

    JsonNode guide() { return ok(HttpMethod.POST, "/api/guides", Map.of("title", "Guided learning", "goal", "Understand fixed code evidence", "sourceIds", List.of(), "knowledgeIds", List.of(), "idempotencyKey", UUID.randomUUID().toString())); }
    Map<String, Object> guideBody(JsonNode guide) {
        return new LinkedHashMap<>(Map.of("title", guide.get("title").asText(), "goal", guide.get("goal").asText(), "steps", guide.get("steps"), "revision", guide.get("revision").asInt(), "sourceIds", List.of(), "knowledgeIds", List.of()));
    }
    JsonNode guidePreview(JsonNode guide) { return ok(HttpMethod.POST, "/api/guides/" + guide.get("id").asText() + "/ai-context", Map.of("revision", guide.get("revision").asInt(), "sourceIds", List.of(), "knowledgeIds", List.of())); }
    Map<String, Object> applyGuide(JsonNode guide, JsonNode generation) { return Map.of("revision", guide.get("revision").asInt(), "generationId", generation.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString()); }

    @Test void sourceLessGuideSuggestionsRequireConsentAndApplyExplicitlyWithStatusesPreserved() throws Exception {
        var guide = guide(); String path = "/api/guides/" + guide.get("id").asText();
        var body = guideBody(guide);
        var steps = JSON.convertValue(guide.get("steps"), new com.fasterxml.jackson.core.type.TypeReference<List<LinkedHashMap<String, Object>>>() {});
        steps.getFirst().put("status", "SKIPPED"); body.put("steps", steps); guide = ok(HttpMethod.PUT, path, body);
        var denied = consent(guidePreview(guide), "DENY");
        assertThat(call(HttpMethod.POST, "/api/ai/generations", Map.of("consentId", denied.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(calls.get()).isZero();
        var preview = guidePreview(guide); assertThat(preview.get("sources")).isEmpty();
        assertThat(preview.get("operation").asText()).isEqualTo("LEARNING_GUIDE");
        var task = generate(consent(preview, "ALLOW")); var done = waitFor("/api/ai/generations/" + task.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        var applied = ok(HttpMethod.POST, path + "/apply-suggestion", applyGuide(guide, done));
        assertThat(applied.get("steps").get(0).get("id").asText()).isEqualTo("REFLECT");
        assertThat(applied.get("skippedCount").asInt()).isEqualTo(1);
        assertThat(call(HttpMethod.POST, path + "/apply-suggestion", applyGuide(applied, done)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var afterManualEdit = ok(HttpMethod.PUT, path, guideBody(applied));
        assertThat(afterManualEdit.path("suggestionGenerationId").asText()).isEqualTo(done.get("id").asText());
        assertThat(call(HttpMethod.POST, "/api/documents", Map.of("title", "Wrong type", "body", "Advice", "generationId", done.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void forgedGuideStepsAndLateCandidateApplicationCannotReplaceEditedGuide() throws Exception {
        var guide = guide(); String path = "/api/guides/" + guide.get("id").asText(); mode = "FORGED_STEP";
        var badTask = generate(consent(guidePreview(guide), "ALLOW")); var bad = waitFor("/api/ai/generations/" + badTask.get("id").asText());
        assertThat(bad.get("errorCode").asText()).isEqualTo("INVALID_RESPONSE_SCHEMA");
        mode = "NORMAL";
        var task = generate(consent(guidePreview(guide), "ALLOW")); var done = waitFor("/api/ai/generations/" + task.get("id").asText());
        var body = guideBody(guide); body.put("goal", "User changed goal"); var edited = ok(HttpMethod.PUT, path, body);
        assertThat(call(HttpMethod.POST, path + "/apply-suggestion", applyGuide(edited, done)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ok(HttpMethod.GET, path, null).get("goal").asText()).isEqualTo("User changed goal");
    }

    @Test void trainingGenerationUsesConsentAndFrozenSubmissionWithoutChangingRuleResult() throws Exception {
        var original = context(); String sourceId = original.get("sources").get(0).get("sourceId").asText();
        var input = Map.of("sourceIds", List.of(sourceId), "goal", "Read the source method", "questionCount", 1, "formats", List.of("TRUE_FALSE"));
        var denied = ok(HttpMethod.POST, "/api/training/ai/contexts", input);
        var denyConsent = consent(denied, "DENY");
        assertThat(call(HttpMethod.POST, "/api/ai/generations", Map.of("consentId", denyConsent.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(calls.get()).isZero();
        var preview = ok(HttpMethod.POST, "/api/training/ai/contexts", input);
        assertThat(preview.get("operation").asText()).isEqualTo("TRAINING_QUESTIONS");
        var generated = generate(consent(preview, "ALLOW"));
        var done = waitFor("/api/ai/generations/" + generated.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        var result = done.get("result");
        assertThat(result.get("citations").get(0).get("verificationStatus").asText()).isEqualTo("VERIFIED");
        assertThat(call(HttpMethod.POST, "/api/documents", Map.of("title", "wrong type", "body", "text", "generationId", done.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> taskBody = JSON.convertValue(result, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        taskBody.remove("citations"); taskBody.remove("uncertainties"); taskBody.remove("usage");
        taskBody.put("sourceIds", List.of(sourceId)); taskBody.put("generationId", done.get("id").asText()); taskBody.put("status", "ACTIVE"); taskBody.put("idempotencyKey", UUID.randomUUID().toString());
        var task = ok(HttpMethod.POST, "/api/training/tasks", taskBody);
        var attempt = ok(HttpMethod.POST, "/api/training/tasks/" + task.get("id").asText() + "/attempts", Map.of("idempotencyKey", UUID.randomUUID().toString()));
        String path = "/api/training/attempts/" + attempt.get("id").asText(); String questionId = task.get("questions").get(0).get("id").asText();
        assertThat(call(HttpMethod.POST, path + "/questions/" + questionId + "/ai-context", Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var submitted = ok(HttpMethod.POST, path + "/submit", Map.of("revision", attempt.get("revision").asInt(), "answers", List.of(Map.of("questionId", questionId, "value", false)), "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(submitted.get("results").get(0).get("verdict").asText()).isEqualTo("INCORRECT");
        var feedbackPreview = ok(HttpMethod.POST, path + "/questions/" + questionId + "/ai-context", Map.of());
        assertThat(feedbackPreview.get("contextJson").asText()).contains(submitted.get("submissionDigest").asText());
        var feedbackTask = generate(consent(feedbackPreview, "ALLOW"));
        var feedback = waitFor("/api/ai/generations/" + feedbackTask.get("id").asText());
        assertThat(feedback.get("result").get("source").asText()).isEqualTo("AI_ASSISTED");
        var restored = ok(HttpMethod.GET, path, null);
        assertThat(restored.get("results")).isEqualTo(submitted.get("results"));
        assertThat(restored.get("aiFeedback")).hasSize(1);
        assertThat(call(HttpMethod.POST, "/api/ai/contexts", Map.of("sourceIds", List.of(sourceId), "parentGenerationId", generated.get("id").asText())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void practiceFeedbackUsesFrozenDiffAndUserReportWithConsent() throws Exception {
        var original = context();
        var sourceMeta = original.get("sources").get(0);
        var source = ok(HttpMethod.POST, "/api/sources", Map.of("snapshotId", sourceMeta.get("snapshotId").asText(),
                "generationId", sourceMeta.get("generationId").asText(), "symbolId", sourceMeta.get("symbolId").asText()));
        var question = Map.of("id", UUID.randomUUID().toString(), "format", "TRUE_FALSE", "category", "CODE_READING",
                "prompt", "Does run print 1?", "options", List.of(), "correctAnswer", true, "referenceExplanation", "Yes", "hints", List.of());
        var task = ok(HttpMethod.POST, "/api/training/tasks", Map.of("title", "Practice AI", "goal", "Modify run",
                "acceptanceCriteria", "Print 1", "status", "ACTIVE", "sourceIds", List.of(source.get("id").asText()),
                "questions", List.of(question), "idempotencyKey", UUID.randomUUID().toString()));
        String snapshot = sourceMeta.get("snapshotId").asText();
        var workspace = ok(HttpMethod.POST, "/api/practice/workspaces", Map.of("taskId", task.get("id").asText(),
                "snapshotId", snapshot, "idempotencyKey", UUID.randomUUID().toString()));
        Path edited = Path.of(workspace.get("path").asText()).resolve("Example.java");
        Files.writeString(edited, "class Example { void run() { System.out.println(2); } }");
        var submission = ok(HttpMethod.POST, "/api/practice/workspaces/" + workspace.get("id").asText() + "/submissions",
                Map.of("idempotencyKey", UUID.randomUUID().toString()));
        ok(HttpMethod.POST, "/api/practice/submissions/" + submission.get("id").asText() + "/test-results",
                Map.of("status", "FAILED", "method", "IDE", "environment", "local", "report", "Expected 1, got 2", "idempotencyKey", UUID.randomUUID().toString()));
        var preview = ok(HttpMethod.POST, "/api/practice/submissions/" + submission.get("id").asText() + "/ai-context",
                Map.of("question", "Does this satisfy acceptance?", "depth", "STANDARD"));
        assertThat(preview.get("operation").asText()).isEqualTo("PRACTICE_FEEDBACK");
        var frozen = JSON.readTree(preview.get("contextJson").asText());
        assertThat(frozen.get("practice").get("task").get("goal").asText()).isEqualTo("Modify run");
        assertThat(frozen.get("practice").get("changes").get(0).get("diff").asText()).contains("println");
        assertThat(frozen.get("practice").get("testResults").get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(frozen.get("practice").get("limitation").asText()).contains("未执行代码");
        var denied = consent(preview, "DENY");
        assertThat(call(HttpMethod.POST, "/api/ai/generations", Map.of("consentId", denied.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(calls.get()).isZero();
        var allowed = ok(HttpMethod.POST, "/api/practice/submissions/" + submission.get("id").asText() + "/ai-context",
                Map.of("question", "Does this satisfy acceptance?", "depth", "STANDARD"));
        var generated = generate(consent(allowed, "ALLOW"));
        var done = waitFor("/api/ai/generations/" + generated.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        var result = done.get("result");
        assertThat(result.get("source").asText()).isEqualTo("AI_ASSISTED");
        assertThat(result.get("suggestedVerdict").asText()).isEqualTo("PARTIAL");
        assertThat(result.get("citations").get(0).get("verificationStatus").asText()).isEqualTo("VERIFIED");
    }

    @Test void generatedTrainingTaskRequiresVerifiedOriginAndRequestedQuestionFormat() throws Exception {
        var original = context(); String sourceId = original.get("sources").get(0).get("sourceId").asText();
        mode = "FORGED";
        var preview = ok(HttpMethod.POST, "/api/training/ai/contexts", Map.of("sourceIds", List.of(sourceId), "goal", "Read source", "questionCount", 1, "formats", List.of("TRUE_FALSE")));
        var task = generate(consent(preview, "ALLOW")); var done = waitFor("/api/ai/generations/" + task.get("id").asText());
        assertThat(done.get("result").get("citations").get(0).get("verificationStatus").asText()).isEqualTo("INVALID");
        Map<String, Object> body = JSON.convertValue(done.get("result"), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        body.remove("citations"); body.remove("uncertainties"); body.remove("usage"); body.put("generationId", done.get("id").asText());
        body.put("sourceIds", List.of(sourceId)); body.put("status", "ACTIVE"); body.put("idempotencyKey", UUID.randomUUID().toString());
        assertThat(call(HttpMethod.POST, "/api/training/tasks", body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        mode = "NORMAL";
        var mismatch = ok(HttpMethod.POST, "/api/training/ai/contexts", Map.of("sourceIds", List.of(sourceId), "goal", "Read source", "questionCount", 1, "formats", List.of("SINGLE_CHOICE")));
        var wrong = generate(consent(mismatch, "ALLOW"));
        assertThat(waitFor("/api/ai/generations/" + wrong.get("id").asText()).get("errorCode").asText()).isEqualTo("INVALID_RESPONSE_SCHEMA");
    }

    @Test void rejectsDeniedExpiredAndChangedScopeWithoutOutbound() throws Exception {
        var denied = consent(context(), "DENY");
        assertThat(call(HttpMethod.POST, "/api/ai/generations", Map.of("consentId", denied.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var expired = context(); var granted = consent(expired, "ALLOW");
        store.update("UPDATE ai_contexts SET expires_at='2000-01-01T00:00:00Z' WHERE id=?", expired.get("id").asText());
        assertThat(call(HttpMethod.POST, "/api/ai/generations", Map.of("consentId", granted.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var changed = consent(context(), "ALLOW"); fingerprint = "a".repeat(64);
        var task = generate(changed);
        var result = waitFor("/api/ai/generations/" + task.get("id").asText());
        assertThat(result.get("errorCode").asText()).isEqualTo("CONSENT_SCOPE_CHANGED");
        assertThat(calls.get()).isZero();
    }

    @Test void deduplicatesGenerationValidatesCitationsAndPreservesManualEditsOnProviderFailure() throws Exception {
        var grant = consent(context(), "ALLOW");
        var body = Map.of("consentId", grant.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString());
        var first = ok(HttpMethod.POST, "/api/ai/generations", body);
        assertThat(ok(HttpMethod.POST, "/api/ai/generations", body).get("id")).isEqualTo(first.get("id"));
        var done = waitFor("/api/ai/generations/" + first.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("result").get("citations").get(0).get("verificationStatus").asText()).isEqualTo("VERIFIED");
        assertThat(calls.get()).isEqualTo(1);
        var doc = ok(HttpMethod.POST, "/api/documents", Map.of("title", "自己的笔记", "body", "人工内容", "generationId", done.get("id").asText(), "idempotencyKey", UUID.randomUUID().toString()));
        var detached = ok(HttpMethod.PUT, "/api/documents/" + doc.get("id").asText(), Map.of("title", "自己的笔记", "body", "人工内容", "generationId", done.get("id").asText(), "revision", 1, "sourceIds", List.of()));
        assertThat(detached.get("sourceIds")).isEmpty();
        assertThat(ok(HttpMethod.GET, "/api/ai/generations/" + done.get("id").asText(), null).get("result").get("citations")).isNotEmpty();
        mode = "ERROR";
        var failed = generate(consent(context(), "ALLOW"));
        assertThat(waitFor("/api/ai/generations/" + failed.get("id").asText()).get("status").asText()).isEqualTo("FAILED");
        assertThat(ok(HttpMethod.GET, "/api/documents/" + doc.get("id").asText(), null).get("body").asText()).isEqualTo("人工内容");
    }

    @Test void marksForgedPathAndRangeInvalid() throws Exception {
        for (String invalid : List.of("FORGED", "RANGE")) {
            mode = invalid;
            var task = generate(consent(context(), "ALLOW"));
            var done = waitFor("/api/ai/generations/" + task.get("id").asText());
            assertThat(done.get("result").get("citations").get(0).get("verificationStatus").asText()).isEqualTo("INVALID");
        }
    }

    @Test void cancelsQueuedRequestWithoutOutboundAndDiscardsLateResult() throws Exception {
        mode = "DELAY";
        var first = generate(consent(context(), "ALLOW"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var second = generate(consent(context(), "ALLOW"));
        ok(HttpMethod.POST, "/api/ai/generations/" + second.get("id").asText() + "/cancel", Map.of());
        ok(HttpMethod.POST, "/api/ai/generations/" + first.get("id").asText() + "/cancel", Map.of());
        release.countDown();
        assertThat(waitFor("/api/ai/generations/" + first.get("id").asText()).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(waitFor("/api/ai/generations/" + second.get("id").asText()).get("status").asText()).isEqualTo("CANCELLED");
        Thread.sleep(100);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, "/api/ai/generations/" + first.get("id").asText(), null).get("result").isNull()).isTrue();
    }
}
