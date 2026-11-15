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
    "spring.datasource.url=${TEST_LEARNING_DB_URL:jdbc:h2:mem:learning;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=target/learning-test-data",
    "codeatlas.ai.service-token=test-internal-token"})
class LearningIntegrationTest {
    @Autowired TestRestTemplate http;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @Test void checksCurrentSourceOnlyOnRequestAndPreservesHistoricalEvidence() throws Exception {
        var source = source("current-");
        String id = source.get("id").asText();
        Path original = null;
        for (var project : ok(HttpMethod.GET, "/api/projects", null)) {
            if (project.get("id").equals(source.get("projectId"))) original = Path.of(project.get("sourcePath").asText()).resolve(source.get("filePath").asText());
        }
        assertThat(original).isNotNull();
        assertThat(ok(HttpMethod.POST, "/api/sources/" + id + "/check-current", Map.of()).get("status").asText()).isEqualTo("MATCHED");
        String historical = source.get("content").asText();
        Files.writeString(original, historical.replace("println(1)", "println(2)"));
        var unchecked = ok(HttpMethod.GET, "/api/sources/" + id, null);
        assertThat(unchecked.get("currentSourceStatus").asText()).isEqualTo("UNCHECKED");
        assertThat(unchecked.get("snapshotOutdated").asBoolean()).isFalse();
        var changed = ok(HttpMethod.POST, "/api/sources/" + id + "/check-current", Map.of());
        assertThat(changed.get("status").asText()).isEqualTo("CHANGED");
        assertThat(changed.get("checkedAt").asText()).isNotBlank();
        assertThat(changed.get("currentSha256")).isNotEqualTo(changed.get("snapshotSha256"));
        assertThat(ok(HttpMethod.GET, "/api/sources/" + id, null).get("content").asText()).isEqualTo(historical);
        Files.write(original, new byte[2_097_153]);
        assertThat(ok(HttpMethod.POST, "/api/sources/" + id + "/check-current", Map.of()).get("status").asText()).isEqualTo("LIMIT_EXCEEDED");
        Files.delete(original);
        assertThat(ok(HttpMethod.POST, "/api/sources/" + id + "/check-current", Map.of()).get("status").asText()).isEqualTo("MISSING");
        assertThat(ok(HttpMethod.GET, "/api/sources/" + id, null).get("content").asText()).isEqualTo(historical);
        Files.createDirectory(original);
        assertThat(ok(HttpMethod.POST, "/api/sources/" + id + "/check-current", Map.of()).get("status").asText()).isEqualTo("UNSAFE");
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        String token = http.getForObject("/api/session", JsonNode.class).get("token").asText();
        var headers = new HttpHeaders(); headers.set("X-CodeAtlas-Token", token); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    JsonNode ok(HttpMethod method, String path, Object body) {
        var response = call(method, path, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }
    JsonNode waitTask(String id) throws Exception {
        for (int n = 0; n < 200; n++) {
            var task = ok(HttpMethod.GET, "/api/tasks/" + id, null);
            if (!List.of("QUEUED", "RUNNING").contains(task.get("status").asText())) return task;
            Thread.sleep(25);
        }
        throw new AssertionError("task timeout");
    }
    JsonNode source(String name) throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/learning-fixtures")), name).toAbsolutePath();
        Files.writeString(root.resolve("Example.java"), "class Example { void run() { System.out.println(1); } }");
        var imported = ok(HttpMethod.POST, "/api/imports", Map.of("path", root.toString()));
        String snapshot = waitTask(imported.get("id").asText()).get("snapshotId").asText();
        var task = ok(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", false));
        waitTask(task.get("id").asText());
        var symbols = ok(HttpMethod.GET, "/api/snapshots/" + snapshot + "/symbols?kind=METHOD", null);
        var symbol = symbols.get("items").get(0);
        return ok(HttpMethod.POST, "/api/sources", Map.of("snapshotId", snapshot, "generationId", symbols.get("generationId").asText(),
                "symbolId", symbol.get("id").asText(), "fileId", symbol.get("fileId").asText(), "startLine", 1, "endLine", 1));
    }
    Map<String, Object> entity(String title, List<String> sources) {
        return Map.of("idempotencyKey", UUID.randomUUID().toString(), "title", title, "body", "事务原理", "tags", List.of("事务"), "sourceIds", sources);
    }

    @Test void managesDocumentsKnowledgeAndCardsWithCrossProjectEvidence() throws Exception {
        var first = source("one-"); var second = source("two-");
        var sources = List.of(first.get("id").asText(), second.get("id").asText());
        var body = entity("事务知识", sources);
        var knowledge = ok(HttpMethod.POST, "/api/knowledge", body);
        var again = ok(HttpMethod.POST, "/api/knowledge", body);
        assertThat(again.get("id")).isEqualTo(knowledge.get("id"));
        assertThat(knowledge.get("sources")).hasSize(2);
        assertThat(knowledge.get("sources").get(0).get("projectId")).isNotEqualTo(knowledge.get("sources").get(1).get("projectId"));
        var edited = new LinkedHashMap<String, Object>(body);
        edited.put("revision", knowledge.get("revision").asInt()); edited.put("body", "人工补充内容");
        var saved = ok(HttpMethod.PUT, "/api/knowledge/" + knowledge.get("id").asText(), edited);
        assertThat(saved.get("body").asText()).isEqualTo("人工补充内容");
        assertThat(call(HttpMethod.PUT, "/api/knowledge/" + knowledge.get("id").asText(), edited).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var document = ok(HttpMethod.POST, "/api/documents", entity("事务笔记", sources));
        assertThat(ok(HttpMethod.GET, "/api/documents?query=事务", null).get("items")).isNotEmpty();
        var card = ok(HttpMethod.POST, "/api/cards", Map.of("idempotencyKey", UUID.randomUUID().toString(), "knowledgeId", knowledge.get("id").asText(),
                "question", "事务是什么？", "answer", "原子操作", "explanation", "业务解释", "codeExample", "run()", "notes", "自己的笔记", "tags", List.of(), "sourceIds", sources));
        assertThat(card.get("sources")).hasSize(2);
        ok(HttpMethod.DELETE, "/api/knowledge/" + knowledge.get("id").asText(), null);
        assertThat(ok(HttpMethod.GET, "/api/cards/" + card.get("id").asText(), null).get("knowledgeDeleted").asBoolean()).isTrue();
        var cardEdit = Map.of("revision", card.get("revision").asInt(), "knowledgeId", knowledge.get("id").asText(),
                "question", "事务是什么？", "answer", "删除知识后继续编辑", "sourceIds", sources);
        assertThat(ok(HttpMethod.PUT, "/api/cards/" + card.get("id").asText(), cardEdit).get("answer").asText()).isEqualTo("删除知识后继续编辑");
        assertThat(call(HttpMethod.POST, "/api/cards", Map.of("idempotencyKey", UUID.randomUUID().toString(), "knowledgeId", knowledge.get("id").asText(),
                "question", "新关联", "answer", "答案")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ok(HttpMethod.DELETE, "/api/documents/" + document.get("id").asText(), null);
        assertThat(call(HttpMethod.GET, "/api/documents/" + document.get("id").asText(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ok(HttpMethod.GET, "/api/sources/" + first.get("id").asText(), null).get("filePath").asText()).isEqualTo("Example.java");
    }

    @Test void invalidSourcesRollbackEditsAndIdempotencyConflictsAreVisible() {
        var body = entity("手工文档", List.of());
        var doc = ok(HttpMethod.POST, "/api/documents", body);
        var altered = new LinkedHashMap<String, Object>(body); altered.put("title", "changed");
        assertThat(call(HttpMethod.POST, "/api/documents", altered).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        altered.put("revision", doc.get("revision").asInt()); altered.put("sourceIds", List.of("missing"));
        assertThat(call(HttpMethod.PUT, "/api/documents/" + doc.get("id").asText(), altered).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ok(HttpMethod.GET, "/api/documents/" + doc.get("id").asText(), null).get("title").asText()).isEqualTo("手工文档");
    }

    @Test void enforcesActualChunkedBodyLimit() throws Exception {
        String token = http.getForObject("/api/session", JsonNode.class).get("token").asText();
        byte[] body = ("{\"body\":\"" + "x".repeat(1_048_577) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/documents"))
                .header("X-CodeAtlas-Token", token).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body))).build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        }
    }
}
