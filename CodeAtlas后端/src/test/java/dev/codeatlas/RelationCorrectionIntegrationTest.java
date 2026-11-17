package dev.codeatlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${TEST_DB_URL_RELATIONS:jdbc:h2:mem:relations;MODE=MySQL;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${TEST_DB_USER:sa}",
                "spring.datasource.password=${TEST_DB_PASSWORD:}",
                "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}",
                "codeatlas.data=${TEST_DATA_RELATIONS:target/test-data-relations}"})
class RelationCorrectionIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    String token;

    record Fixture(String snapshot, String generation, String symbol) {}

    @BeforeEach void session() {
        token = http.getForObject("/api/session", JsonNode.class).get("token").asText();
    }

    JsonNode request(HttpMethod method, String path, Object body) {
        var response = exchange(method, path, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        headers.set("X-CodeAtlas-Token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    JsonNode waitTask(String path) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var task = request(HttpMethod.GET, path, null);
            if (!List.of("QUEUED", "RUNNING").contains(task.get("status").asText())) return task;
            Thread.sleep(50);
        }
        throw new AssertionError("Task timed out");
    }

    Fixture fixture() throws Exception {
        Path source = Files.createTempDirectory(Files.createDirectories(Path.of("target/relation-fixtures")), "sample-").toAbsolutePath();
        try (var paths = Files.walk(Path.of("test-fixtures/spring-sample").toAbsolutePath())) {
            for (Path path : paths.toList()) {
                Path target = source.resolve(Path.of("test-fixtures/spring-sample").toAbsolutePath().relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        var imported = request(HttpMethod.POST, "/api/imports", Map.of("path", source.toString()));
        String snapshot = waitTask("/api/tasks/" + imported.get("id").asText()).get("snapshotId").asText();
        var indexed = request(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", false));
        assertThat(waitTask("/api/tasks/" + indexed.get("id").asText()).get("status").asText()).isEqualTo("PARTIAL_SUCCESS");
        var symbols = request(HttpMethod.GET, "/api/snapshots/" + snapshot + "/symbols?query=pay&kind=METHOD&limit=100", null);
        var symbol = find(symbols.get("items"), item -> "sample.PaymentService.pay".equals(item.get("qualifiedName").asText())
                && "pay(String)".equals(item.get("signature").asText()));
        return new Fixture(snapshot, symbols.get("generationId").asText(), symbol.get("id").asText());
    }

    JsonNode outgoingRelation(Fixture fixture) {
        var relations = request(HttpMethod.GET, "/api/snapshots/" + fixture.snapshot() + "/symbols/" + fixture.symbol()
                + "/relations?generationId=" + fixture.generation() + "&limit=1000", null);
        return find(relations.get("items"), item -> fixture.symbol().equals(item.get("sourceId").asText())
                && item.get("targetText").asText().toLowerCase(Locale.ROOT).contains("audit"));
    }

    Map<String, Object> correction(String verdict, String kind, String target, String key) {
        var body = new LinkedHashMap<String, Object>();
        body.put("verdict", verdict); body.put("correctedKind", kind); body.put("correctedTargetText", target);
        body.put("reason", "人工核对调用点与运行时目标"); body.put("source", "PaymentService.java:L8");
        body.put("idempotencyKey", key);
        return body;
    }

    @Test void keepsOriginalRelationAndExposesCorrectionOverlay() throws Exception {
        var fixture = fixture();
        var relation = outgoingRelation(fixture);
        String relationId = relation.get("id").asText();
        String originalKind = relation.get("kind").asText();
        String originalTarget = relation.get("targetText").asText();
        String correctedKind = originalKind.equals("RUNTIME_CALL") ? "CALL" : "RUNTIME_CALL";
        String correctedTarget = "sample.AuditService.audit";
        String key = UUID.randomUUID().toString();
        var body = new LinkedHashMap<>(correction("CORRECTED", correctedKind, correctedTarget, key));
        body.put("generationId", fixture.generation());
        var created = request(HttpMethod.POST, "/api/snapshots/" + fixture.snapshot() + "/relations/" + relationId + "/corrections", body);
        assertThat(created.get("verdict").asText()).isEqualTo("CORRECTED");
        assertThat(created.get("correctedKind").asText()).isEqualTo(correctedKind);
        assertThat(created.get("correctedTargetText").asText()).isEqualTo(correctedTarget);
        assertThat(created.get("reason").asText()).isNotBlank();
        assertThat(created.get("source").asText()).isEqualTo("PaymentService.java:L8");
        assertThat(created.get("createdAt").asText()).isNotBlank();

        var after = request(HttpMethod.GET, "/api/snapshots/" + fixture.snapshot() + "/symbols/" + fixture.symbol()
                + "/relations?generationId=" + fixture.generation() + "&limit=1000", null);
        var corrected = find(after.get("items"), item -> relationId.equals(item.get("id").asText()));
        assertThat(corrected.get("kind").asText()).isEqualTo(originalKind);
        assertThat(corrected.get("status").asText()).isEqualTo(relation.get("status").asText());
        assertThat(corrected.get("targetText").asText()).isEqualTo(originalTarget);
        assertThat(corrected.get("effectiveKind").asText()).isEqualTo(correctedKind);
        assertThat(corrected.get("effectiveTargetText").asText()).isEqualTo(correctedTarget);
        assertThat(corrected.get("effectiveVerdict").asText()).isEqualTo("CORRECTED");
        assertThat(corrected.get("latestCorrection").get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(corrected.get("corrections").size()).isEqualTo(1);

        var history = request(HttpMethod.GET, "/api/snapshots/" + fixture.snapshot() + "/relations/" + relationId
                + "/corrections?generationId=" + fixture.generation(), null);
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).get("id").asText()).isEqualTo(created.get("id").asText());

        var retried = request(HttpMethod.POST, "/api/snapshots/" + fixture.snapshot() + "/relations/" + relationId + "/corrections", body);
        assertThat(retried.get("id").asText()).isEqualTo(created.get("id").asText());
        var conflict = new LinkedHashMap<>(body);
        conflict.put("reason", "不同理由");
        assertThat(exchange(HttpMethod.POST, "/api/snapshots/" + fixture.snapshot() + "/relations/" + relationId + "/corrections", conflict)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void rejectsInvalidCorrectionContent() throws Exception {
        var fixture = fixture();
        var relation = outgoingRelation(fixture);
        String path = "/api/snapshots/" + fixture.snapshot() + "/relations/" + relation.get("id").asText() + "/corrections";

        var rejectedWithKind = new LinkedHashMap<>(correction("REJECTED", "CALL", null, UUID.randomUUID().toString()));
        rejectedWithKind.put("generationId", fixture.generation());
        var rejected = exchange(HttpMethod.POST, path, rejectedWithKind);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody().get("code").asText()).isEqualTo("INVALID_RELATION_CORRECTION");

        var unchanged = new LinkedHashMap<>(correction("CORRECTED", relation.get("kind").asText(),
                relation.get("targetText").asText(), UUID.randomUUID().toString()));
        unchanged.put("generationId", fixture.generation());
        assertThat(exchange(HttpMethod.POST, path, unchanged).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var wrongGeneration = new LinkedHashMap<>(correction("CORRECTED", "CALL", "sample.Other", UUID.randomUUID().toString()));
        wrongGeneration.put("generationId", UUID.randomUUID().toString());
        assertThat(exchange(HttpMethod.POST, path, wrongGeneration).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void graphEvidenceShowsCorrectionBesideOriginalRelation() throws Exception {
        var fixture = fixture();
        var relation = outgoingRelation(fixture);
        String relationId = relation.get("id").asText();
        String originalKind = relation.get("kind").asText();
        var body = new LinkedHashMap<>(correction("REJECTED", null, null, UUID.randomUUID().toString()));
        body.put("generationId", fixture.generation());
        request(HttpMethod.POST, "/api/snapshots/" + fixture.snapshot() + "/relations/" + relationId + "/corrections", body);

        var graph = request(HttpMethod.GET, "/api/snapshots/" + fixture.snapshot() + "/graph?view=CALL&focusSymbolId="
                + fixture.symbol() + "&generationId=" + fixture.generation() + "&limit=80&edgeLimit=300", null);
        boolean found = false;
        for (JsonNode edge : graph.get("edges")) {
            var evidence = request(HttpMethod.GET, "/api/snapshots/" + fixture.snapshot() + "/graph/edges/"
                    + edge.get("id").asText() + "/evidence?view=CALL&focusSymbolId=" + fixture.symbol()
                    + "&generationId=" + fixture.generation() + "&limit=30", null);
            for (JsonNode item : evidence.get("items")) {
                if (relationId.equals(item.get("relation").get("id").asText())) {
                    assertThat(item.get("relation").get("kind").asText()).isEqualTo(originalKind);
                    assertThat(item.get("latestCorrection").get("verdict").asText()).isEqualTo("REJECTED");
                    assertThat(item.get("corrections").size()).isEqualTo(1);
                    found = true;
                }
            }
            if (found) break;
        }
        assertThat(found).as("corrected relation must be visible in graph evidence").isTrue();
    }

    private static JsonNode find(JsonNode items, Predicate<JsonNode> predicate) {
        for (JsonNode item : items) if (predicate.test(item)) return item;
        throw new AssertionError("No matching item in " + items);
    }
}
