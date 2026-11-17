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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:atlas;MODE=MySQL;DB_CLOSE_DELAY=-1}", "spring.datasource.username=${TEST_DB_USER:sa}",
        "spring.datasource.password=${TEST_DB_PASSWORD:}", "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=${TEST_DATA:target/test-data}"})
class AtlasIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired dev.codeatlas.store.AtlasStore store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    String token;

    @BeforeEach void session() {
        token = http.getForObject("/api/session", JsonNode.class).get("token").asText();
    }

    JsonNode request(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        headers.set("X-CodeAtlas-Token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    JsonNode waitTask(String path) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var task = request(HttpMethod.GET, path, null);
            if (!List.of("QUEUED", "RUNNING").contains(task.get("status").asText())) return task;
            Thread.sleep(50);
        }
        throw new AssertionError("Task timed out");
    }

    @Test void importsIndexesDeduplicatesAndNavigatesSnapshotEvidence() throws Exception {
        Path original = Path.of("test-fixtures/spring-sample").toAbsolutePath().normalize();
        Path copy = Files.createTempDirectory(Files.createDirectories(Path.of("target/integration-fixtures")), "sample-").toAbsolutePath();
        try (var paths = Files.walk(original)) {
            for (Path path : paths.toList()) {
                Path destination = copy.resolve(original.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        String source = copy.toString();
        var imported = request(HttpMethod.POST, "/api/imports", Map.of("path", source));
        var done = waitTask("/api/imports/" + imported.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        String snapshot = done.get("snapshotId").asText();
        var indexed = request(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", false));
        var result = waitTask("/api/tasks/" + indexed.get("id").asText());
        assertThat(result.get("status").asText()).isEqualTo("PARTIAL_SUCCESS");
        var symbols = request(HttpMethod.GET, "/api/snapshots/" + snapshot + "/symbols?query=pay&kind=METHOD", null);
        assertThat(symbols.get("total").asInt()).isEqualTo(4);
        var first = symbols.get("items").get(0);
        var content = request(HttpMethod.GET, "/api/snapshots/" + snapshot + "/files/" + first.get("fileId").asText() + "/content", null);
        assertThat(content.get("snapshotId").asText()).isEqualTo(snapshot);
        assertThat(content.get("content").asText()).contains("pay(");
        var repeat = request(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", false));
        assertThat(repeat.get("id").asText()).isEqualTo(indexed.get("id").asText());
        var rebuilt = request(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", true));
        assertThat(waitTask("/api/tasks/" + rebuilt.get("id").asText()).get("status").asText()).isEqualTo("PARTIAL_SUCCESS");
        var after = request(HttpMethod.GET, "/api/snapshots/" + snapshot + "/symbols?query=pay&kind=METHOD", null);
        assertThat(after.get("total").asInt()).isEqualTo(4);
        assertThat(after.get("generationId").asText()).isNotEqualTo(symbols.get("generationId").asText());
        var old = request(HttpMethod.GET, "/api/snapshots/" + snapshot + "/symbols?query=pay&kind=METHOD&generationId=" + symbols.get("generationId").asText(), null);
        assertThat(old.get("total").asInt()).isEqualTo(4);
        var duplicate = request(HttpMethod.POST, "/api/imports", Map.of("path", source));
        assertThat(waitTask("/api/imports/" + duplicate.get("id").asText()).get("snapshotId").asText()).isEqualTo(snapshot);
    }

    @Test void rejectsUnauthenticatedAndCrossOriginReads() {
        assertThat(http.getForEntity("/api/projects", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var headers = new HttpHeaders();
        headers.set("Origin", "https://example.org");
        assertThat(http.exchange("/api/session", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void keepsPublishedIndexDuringConcurrentDraftCancelAndRecovery() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/integration-fixtures")), "lifecycle-").toAbsolutePath();
        Files.writeString(root.resolve("One.java"), "class One { void run() {} }");
        var imported = request(HttpMethod.POST, "/api/imports", Map.of("path", root.toString()));
        String snapshot = waitTask("/api/imports/" + imported.get("id").asText()).get("snapshotId").asText();
        var initial = request(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", false));
        assertThat(waitTask("/api/tasks/" + initial.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
        String published = (String) store.snapshot(snapshot).get("publishedGeneration");
        var requests = java.util.stream.IntStream.range(0, 6).mapToObj(n -> java.util.concurrent.CompletableFuture.supplyAsync(() -> store.createIndex(snapshot, true))).toList();
        var ids = requests.stream().map(java.util.concurrent.CompletableFuture::join).map(t -> (String) t.get("id")).distinct().toList();
        assertThat(ids).hasSize(1);
        String id = ids.getFirst();
        store.running(id, 1);
        String file = (String) store.files(snapshot).getFirst().get("id");
        var symbol = new dev.codeatlas.analysis.JavaAnalyzer.Symbol("draft", file, "CLASS", "Draft", "Draft", "Draft", 1, 1, 1, 12, List.of(), "PACKAGE", "");
        var partial = new dev.codeatlas.analysis.JavaAnalyzer.Analysis(List.of(symbol), List.of(), List.of(), 1, 1);
        store.appendDraft(id, partial);
        assertThat(request(HttpMethod.GET, "/api/tasks/" + id + "/symbols", null).get("total").asInt()).isEqualTo(1);
        assertThat(store.snapshot(snapshot).get("publishedGeneration")).isEqualTo(published);
        store.cancel(id);
        store.saveAnalysis(id, partial);
        assertThat(store.task(id).get("status")).isEqualTo("CANCELLED");
        assertThat(store.snapshot(snapshot).get("publishedGeneration")).isEqualTo(published);
        var interrupted = store.createIndex(snapshot, true);
        store.running((String) interrupted.get("id"), 1);
        store.recover();
        assertThat(store.task((String) interrupted.get("id")).get("errorCode")).isEqualTo("PROCESS_INTERRUPTED");
        assertThat(store.snapshot(snapshot).get("publishedGeneration")).isEqualTo(published);
        var retried = request(HttpMethod.POST, "/api/tasks/" + interrupted.get("id") + "/retry", Map.of());
        assertThat(waitTask("/api/tasks/" + retried.get("id").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test void oldSnapshotBytesSurviveSourceChangesAndEmptyProjectIsUsable() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/integration-fixtures")), "snapshot-").toAbsolutePath();
        Files.writeString(root.resolve("readme.txt"), "old bytes");
        var first = request(HttpMethod.POST, "/api/imports", Map.of("path", root.toString()));
        String snapshot = waitTask("/api/imports/" + first.get("id").asText()).get("snapshotId").asText();
        String file = (String) store.files(snapshot).getFirst().get("id");
        Files.writeString(root.resolve("readme.txt"), "new bytes");
        assertThat(request(HttpMethod.GET, "/api/snapshots/" + snapshot + "/files/" + file + "/content", null).get("content").asText()).isEqualTo("old bytes");
        var second = request(HttpMethod.POST, "/api/imports", Map.of("path", root.toString()));
        assertThat(waitTask("/api/imports/" + second.get("id").asText()).get("snapshotId").asText()).isNotEqualTo(snapshot);
        var indexed = request(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", false));
        var done = waitTask("/api/tasks/" + indexed.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("message").asText()).contains("Java");
    }

    @Test void retainsSnapshotWhenDatabaseAcknowledgementFailsAfterCommit() throws Exception {
        Path workspace = Files.createTempDirectory(Files.createDirectories(Path.of("target/integration-fixtures")), "commit-").toAbsolutePath();
        Path source = Files.createDirectory(workspace.resolve("source"));
        Files.writeString(source.resolve("One.java"), "class One {}");
        var uncertainStore = new dev.codeatlas.store.AtlasStore(jdbc, transactions, json) {
            @Override public String completeImport(String taskId, dev.codeatlas.snapshot.SnapshotScanner.CapturedSnapshot capture) {
                super.completeImport(taskId, capture);
                throw new IllegalStateException("Simulated connection loss after commit");
            }
        };
        var isolated = new dev.codeatlas.tasks.TaskService(uncertainStore, workspace.resolve("data").toString(), "",
                new dev.codeatlas.github.GitHubFetcher(workspace.resolve("data").toString(), 20));
        isolated.recover();
        JsonNode done;
        try {
            var started = isolated.importProject(source.toString());
            done = waitTask("/api/tasks/" + started.get("id"));
        } finally { isolated.close(); }
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        String key = (String) store.snapshot(done.get("snapshotId").asText()).get("storageKey");
        assertThat(workspace.resolve("data/snapshots").resolve(key).resolve("One.java")).exists();
    }
}
