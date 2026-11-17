package dev.codeatlas;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codeatlas.store.AtlasStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TEST_ARCH_DB_URL:jdbc:h2:mem:architecture;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=target/architecture-test-data"})
class ArchitectureIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired AtlasStore store;
    record Fixture(String snapshot, String generation) {}

    ResponseEntity<JsonNode> call(String method, String route, Object body) {
        var headers = new HttpHeaders();
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api" + route, HttpMethod.valueOf(method), new HttpEntity<>(body, headers), JsonNode.class);
    }

    ResponseEntity<byte[]> download(String route) {
        var headers = new HttpHeaders();
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        return http.exchange("/api" + route, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    Fixture seed() throws Exception {
        String project = id(), snapshot = id(), task = id(), generation = id();
        String storage = "snapshot-" + snapshot;
        Path root = Path.of("target/architecture-test-data/snapshots", storage);
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>demo</artifactId>
                <dependencies><dependency><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>
                </project>
                """);
        Files.writeString(root.resolve("src/main/resources/application.properties"),
                "spring.datasource.url=jdbc:mysql://db.example.com:3306/demo?user=secret&password=secret\nexternal.api.url=https://api.example.com/v1\n");
        Files.writeString(root.resolve("src/main/java/demo/DemoApplication.java"), "package demo; public class DemoApplication {}");
        Files.writeString(root.resolve("src/main/java/demo/DemoController.java"), """
                package demo;
                public class DemoController {
                    private DemoService service;
                    private java.util.List<DemoService> services;
                    public void process() {
                        if (service != null) {
                            service.load();
                        } else {
                            service.load();
                        }
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/demo/DemoService.java"), """
                package demo;
                public class DemoService {
                    public void load() {
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/demo/DemoRepository.java"), """
                package demo;
                public class DemoRepository {
                    public void find() {
                    }
                }
                """);
        store.update("INSERT INTO projects(id,name,source_path,path_hash,created_at) VALUES(?,?,?,?,?)", project, "Architecture", "target/" + project, project, AtlasStore.now());
        store.update("INSERT INTO snapshots(id,project_id,manifest_hash,identity_hash,source_kind,storage_key,policy_version,created_at) VALUES(?,?,?,?,?,?,?,?)",
                snapshot, project, snapshot, snapshot, "NON_GIT", storage, "test", AtlasStore.now());
        store.update("INSERT INTO tasks(id,kind,status,snapshot_id,generation_id,analyzer_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                task, "INDEX", "SUCCEEDED", snapshot, generation, "test", AtlasStore.now(), AtlasStore.now());
        store.update("INSERT INTO generations(id,snapshot_id,task_id,published,created_at) VALUES(?,?,?,?,?)", generation, snapshot, task, true, AtlasStore.now());
        store.update("UPDATE snapshots SET published_generation=? WHERE id=?", generation, snapshot);
        String pom = file(snapshot, "pom.xml", "pom");
        String config = file(snapshot, "src/main/resources/application.properties", "config");
        String appFile = file(snapshot, "src/main/java/demo/DemoApplication.java", "app");
        String controllerFile = file(snapshot, "src/main/java/demo/DemoController.java", "controller");
        String serviceFile = file(snapshot, "src/main/java/demo/DemoService.java", "service");
        String repoFile = file(snapshot, "src/main/java/demo/DemoRepository.java", "repo");
        symbol(generation, appFile, "CLASS", "DemoApplication", "demo.DemoApplication", "SpringBootApplication");
        String controller = symbol(generation, controllerFile, "CLASS", "DemoController", "demo.DemoController", "RestController");
        String service = symbol(generation, serviceFile, "CLASS", "DemoService", "demo.DemoService", "Service");
        String repo = symbol(generation, repoFile, "CLASS", "DemoRepository", "demo.DemoRepository", "Repository");
        String process = symbol(generation, controllerFile, "METHOD", "process", "demo.DemoController.process", "");
        String load = symbol(generation, serviceFile, "METHOD", "load", "demo.DemoService.load", "");
        String find = symbol(generation, repoFile, "METHOD", "find", "demo.DemoRepository.find", "");
        relation(generation, process, load, controllerFile, "CALL", "CONFIRMED");
        relation(generation, load, find, serviceFile, "CALL", "CONFIRMED");
        return new Fixture(snapshot, generation);
    }

    @Test void architectureViewAndExportAreEvidenceBoundAndStandalone() throws Exception {
        var fixture = seed();
        var response = call("GET", "/snapshots/" + fixture.snapshot + "/architecture?generationId=" + fixture.generation + "&perspective=SYSTEM&limit=80", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var page = response.getBody();
        assertThat(page.get("nodes").findValuesAsText("kind")).contains("SYSTEM", "APPLICATION", "MODULE", "COMPONENT", "EXTERNAL_SERVICE", "DATASTORE");
        assertThat(page.get("edges").findValuesAsText("kind")).contains("CONTAINS", "DEPENDS_ON", "READS_WRITES");
        assertThat(page.get("evidence").toString()).contains("application.properties");
        assertThat(page.get("evidence").toString()).doesNotContain("password=secret");
        assertThat(page.get("coverage").get("components").asInt()).isEqualTo(3);
        var export = call("POST", "/architecture/exports", Map.of("snapshotId", fixture.snapshot, "generationId", fixture.generation,
                "format", "ARCHIFY_HTML", "scope", "SYSTEM", "theme", "light", "idempotencyKey", id()));
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(export.getBody().get("status").asText()).isEqualTo("SUCCEEDED");
        var download = download(export.getBody().get("downloadUrl").asText().replace("/api", ""));
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody()).isNotNull();
        var html = new String(download.getBody(), StandardCharsets.UTF_8);
        assertThat(html).contains("系统架构总览").contains("api.example.com").doesNotContain("password=secret");
        assertThat(html).doesNotContain("<script src=", "<link rel=\"stylesheet\"", "url(http");
        var svgExport = call("POST", "/architecture/exports", Map.of("snapshotId", fixture.snapshot, "generationId", fixture.generation,
                "format", "SVG", "scope", "SYSTEM", "theme", "light", "idempotencyKey", id()));
        assertThat(svgExport.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(svgExport.getBody().get("artifactName").asText()).isEqualTo("architecture.svg");
        var svgDownload = download(svgExport.getBody().get("downloadUrl").asText().replace("/api", ""));
        assertThat(svgDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(svgDownload.getHeaders().getContentType().toString()).startsWith("image/svg+xml");
        var svg = new String(svgDownload.getBody(), StandardCharsets.UTF_8);
        assertThat(svg).startsWith("<svg").contains("系统架构总览").doesNotContain("<script", "<image", "url(http");
    }

    @Test void unboundProjectReturnsExplicitState() {
        String project = id();
        store.update("INSERT INTO projects(id,name,source_path,path_hash,created_at) VALUES(?,?,?,?,?)",
                project, "Unbound", "target/" + project, project, AtlasStore.now());
        var binding = call("GET", "/github/projects/" + project + "/binding", null);
        assertThat(binding.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(binding.getBody().get("bound").asBoolean()).isFalse();
        assertThat(binding.getBody().get("projectId").asText()).isEqualTo(project);
    }

    @Test void exportIdempotencyComparesContentAndRetriesFailures() throws Exception {
        var fixture = seed();
        String key = id();
        var body = new LinkedHashMap<String, Object>(Map.of("snapshotId", fixture.snapshot, "generationId", fixture.generation,
                "format", "ARCHIFY_HTML", "scope", "SYSTEM", "theme", "light", "idempotencyKey", key));
        var first = call("POST", "/architecture/exports", body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var firstId = first.getBody().get("id").asText();
        var replay = call("POST", "/architecture/exports", body);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("id").asText()).isEqualTo(firstId);
        assertThat(store.rows("SELECT id FROM architecture_exports WHERE idempotency_key=?", key)).hasSize(1);

        var conflict = call("POST", "/architecture/exports", new LinkedHashMap<String, Object>(Map.of(
                "snapshotId", fixture.snapshot, "generationId", fixture.generation,
                "format", "ARCHIFY_HTML", "scope", "SYSTEM", "theme", "dark", "idempotencyKey", key)));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(store.rows("SELECT id FROM architecture_exports WHERE idempotency_key=?", key)).hasSize(1);

        var stale = call("POST", "/architecture/exports", Map.of("snapshotId", fixture.snapshot, "generationId", fixture.generation,
                "digest", "0".repeat(64), "format", "ARCHIFY_HTML", "scope", "SYSTEM", "theme", "light", "idempotencyKey", id()));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asText()).isEqualTo("PROJECTION_STALE");

        store.update("UPDATE architecture_exports SET status='FAILED',error_code='TEST_FAILURE' WHERE id=?", firstId);
        var retried = call("POST", "/architecture/exports", body);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var retriedId = retried.getBody().get("id").asText();
        assertThat(retriedId).isNotEqualTo(firstId);
        assertThat(store.rows("SELECT id FROM architecture_exports WHERE idempotency_key=?", key)).hasSize(2);
        assertThat(store.one("SELECT status FROM architecture_exports WHERE id=?", firstId).get("status")).isEqualTo("FAILED");

        var omitted = call("POST", "/architecture/exports", Map.of("snapshotId", fixture.snapshot,
                "format", "ARCHIFY_HTML", "scope", "SYSTEM", "theme", "light", "idempotencyKey", id()));
        assertThat(omitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(store.one("SELECT generation_id FROM architecture_exports WHERE id=?", omitted.getBody().get("id").asText()).get("generationId"))
                .isEqualTo(fixture.generation);

        String artifact = (String) store.one("SELECT artifact_path FROM architecture_exports WHERE id=?", retriedId).get("artifactPath");
        Files.delete(Path.of("target/architecture-test-data").resolve(artifact));
        var missing = download("/architecture/exports/" + retriedId + "/download");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(new String(missing.getBody(), StandardCharsets.UTF_8)).contains("EXPORT_ARTIFACT_MISSING");
    }

    @Test void unboundProjectRejectsCheckAndSyncBeforeGithubAccess() {
        String project = id();
        store.update("INSERT INTO projects(id,name,source_path,path_hash,created_at) VALUES(?,?,?,?,?)",
                project, "Unbound", "target/" + project, project, AtlasStore.now());
        long before = ((Number) store.one("SELECT COUNT(*) AS count FROM tasks").get("count")).longValue();
        var check = call("POST", "/github/projects/" + project + "/check", null);
        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(check.getBody().get("code").asText()).isEqualTo("GITHUB_NOT_BOUND");
        var sync = call("POST", "/github/projects/" + project + "/sync", null);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sync.getBody().get("code").asText()).isEqualTo("GITHUB_NOT_BOUND");
        long after = ((Number) store.one("SELECT COUNT(*) AS count FROM tasks").get("count")).longValue();
        assertThat(after).isEqualTo(before);
        var missing = call("POST", "/github/projects/" + id() + "/check", null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test void privateFeaturesAreGatedAndSecretFree() {
        var status = call("GET", "/executions/status", null);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody().get("enabled").asBoolean()).isFalse();
        assertThat(status.getBody().get("message").asText()).contains("安全准入");
        var credentials = call("GET", "/github/credentials", null);
        assertThat(credentials.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(credentials.getBody().isArray()).isTrue();
        assertThat(credentials.getBody().findValuesAsText("token")).isEmpty();
        var ai = call("GET", "/ai/status", null);
        assertThat(ai.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ai.getBody().get("capabilities")).isNotEmpty();
        assertThat(ai.getBody().toString()).doesNotContain("service-token");
    }

    @Test void flowSequenceAndUmlKeepSourceEvidence() throws Exception {
        var fixture = seed();
        var controller = store.payloads("SELECT payload FROM symbols WHERE generation_id=? AND name='DemoController'", fixture.generation).getFirst();
        var controllerId = ((JsonNode) controller).get("id").asText();
        var process = store.payloads("SELECT payload FROM symbols WHERE generation_id=? AND name='process'", fixture.generation).getFirst();
        var processId = ((JsonNode) process).get("id").asText();
        var flow = call("GET", "/snapshots/" + fixture.snapshot + "/symbols/" + processId + "/flow?generationId=" + fixture.generation, null);
        assertThat(flow.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(flow.getBody().get("nodes").findValuesAsText("kind")).contains("START", "DECISION", "STATEMENT", "MERGE", "END");
        assertThat(flow.getBody().get("edges").findValuesAsText("label")).contains("TRUE", "FALSE");
        var sequence = call("GET", "/snapshots/" + fixture.snapshot + "/symbols/" + processId + "/sequence?generationId=" + fixture.generation + "&depth=3", null);
        assertThat(sequence.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sequence.getBody().get("messages")).hasSize(2);
        assertThat(sequence.getBody().get("participants").findValuesAsText("name")).contains("DemoController", "DemoService", "DemoRepository");
        var uml = call("GET", "/snapshots/" + fixture.snapshot + "/symbols/" + controllerId + "/uml?generationId=" + fixture.generation, null);
        assertThat(uml.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(uml.getBody().get("fields").findValuesAsText("name")).contains("service");
        assertThat(uml.getBody().get("methods").findValuesAsText("name")).contains("process");
        assertThat(uml.getBody().get("associations").get(0).get("targetName").asText()).isEqualTo("DemoService");
        assertThat(uml.getBody().get("associations").get(0).get("multiplicity").asText()).isEqualTo("1");
        var genericAssociation = uml.getBody().get("associations").findParents("field").stream()
                .filter(node -> node.get("field") != null && "services".equals(node.get("field").asText())).findFirst().orElseThrow();
        assertThat(uml.getBody().get("fields").findValuesAsText("name")).contains("services");
        assertThat(genericAssociation.get("multiplicity").asText()).isEqualTo("0..*");
        assertThat(genericAssociation.get("kind").asText()).isEqualTo("AGGREGATION_CANDIDATE");
    }

    @Test void sequenceReportsCyclesUnresolvedTargetsAndTruncation() throws Exception {
        var fixture = seed();
        var process = store.payloads("SELECT payload FROM symbols WHERE generation_id=? AND name='process'", fixture.generation).getFirst();
        var processId = ((JsonNode) process).get("id").asText();
        var load = store.payloads("SELECT payload FROM symbols WHERE generation_id=? AND name='load'", fixture.generation).getFirst();
        var loadId = ((JsonNode) load).get("id").asText();
        String serviceFile = (String) store.one("SELECT file_id FROM symbols WHERE generation_id=? AND id=?", fixture.generation, loadId).get("fileId");
        relation(fixture.generation, loadId, loadId, serviceFile, "CALL", "CONFIRMED");
        var unresolved = new LinkedHashMap<String, Object>();
        unresolved.put("sourceId", processId); unresolved.put("targetId", null); unresolved.put("fileId", serviceFile);
        unresolved.put("targetText", "missing.audit()"); unresolved.put("kind", "CALL"); unresolved.put("status", "UNRESOLVED");
        unresolved.put("reason", "fixture unresolved"); unresolved.put("startLine", 5); unresolved.put("endLine", 5);
        unresolved.put("startColumn", 1); unresolved.put("endColumn", 20); unresolved.put("resolver", "architecture-test");
        store.update("INSERT INTO relations(id,generation_id,source_id,target_id,payload) VALUES(?,?,?,?,?)",
                id(), fixture.generation, processId, null, store.encode(unresolved));
        var sequence = call("GET", "/snapshots/" + fixture.snapshot + "/symbols/" + processId + "/sequence?generationId=" + fixture.generation + "&depth=3&limit=20", null);
        assertThat(sequence.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sequence.getBody().get("messages").findValuesAsText("cycle")).contains("true");
        assertThat(sequence.getBody().get("participants").findValuesAsText("name")).anyMatch(name -> name.contains("未解析 · missing.audit()"));
        var truncated = call("GET", "/snapshots/" + fixture.snapshot + "/symbols/" + processId + "/sequence?generationId=" + fixture.generation + "&depth=3&limit=1", null);
        assertThat(truncated.getBody().get("truncated").asBoolean()).isTrue();
    }

    private String id() { return AtlasStore.uuid(); }
    private String file(String snapshot, String path, String marker) {
        String id = id();
        store.update("INSERT INTO snapshot_files(snapshot_id,id,path,sha256,byte_size) VALUES(?,?,?,?,?)", snapshot, id, path, marker.repeat(64).substring(0, 64), 100);
        return id;
    }
    private String symbol(String generation, String file, String kind, String name, String qualified, String annotation) {
        String id = id();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", id); payload.put("fileId", file); payload.put("kind", kind); payload.put("name", name);
        payload.put("qualifiedName", qualified); payload.put("signature", name + (kind.equals("METHOD") ? "()" : ""));
        payload.put("startLine", 1); payload.put("endLine", 20); payload.put("startColumn", 1); payload.put("endColumn", 100);
        payload.put("annotations", annotation.isBlank() ? List.of() : List.of(annotation)); payload.put("visibility", "PUBLIC"); payload.put("typeText", "");
        store.update("INSERT INTO symbols(generation_id,id,file_id,name,payload) VALUES(?,?,?,?,?)", generation, id, file, name, store.encode(payload));
        return id;
    }
    private void relation(String generation, String source, String target, String file, String kind, String status) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sourceId", source); payload.put("targetId", target); payload.put("fileId", file);
        payload.put("targetText", target); payload.put("kind", kind); payload.put("status", status);
        payload.put("reason", "fixture relation"); payload.put("startLine", 2); payload.put("endLine", 2);
        payload.put("startColumn", 1); payload.put("endColumn", 20); payload.put("resolver", "architecture-test");
        store.update("INSERT INTO relations(id,generation_id,source_id,target_id,payload) VALUES(?,?,?,?,?)", id(), generation, source, target, store.encode(payload));
    }
}
