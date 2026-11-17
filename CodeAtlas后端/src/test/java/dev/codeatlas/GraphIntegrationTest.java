package dev.codeatlas;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codeatlas.store.AtlasStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TEST_GRAPH_DB_URL:jdbc:h2:mem:graph;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=target/graph-test-data"})
class GraphIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired AtlasStore store;
    record Fixture(String snapshot, String generation, String a, String b, String run, String other, String inner, String nested) {}
    String id() { return AtlasStore.uuid(); }
    ResponseEntity<JsonNode> call(String route) {
        var headers = new HttpHeaders();
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        return http.exchange("/api" + route, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }
    JsonNode ok(String route) { var response = call(route); assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue(); return response.getBody(); }
    String base(Fixture fixture) { return "/snapshots/" + fixture.snapshot + "/graph?generationId=" + fixture.generation; }
    String file(String snapshot, String path) {
        String file = id(); store.update("INSERT INTO snapshot_files(snapshot_id,id,path,sha256,byte_size) VALUES(?,?,?,?,?)", snapshot, file, path, "a".repeat(64), 100); return file;
    }
    String symbol(String generation, String file, String kind, String name, String qualified, int start, int end) {
        return symbol(generation, file, kind, name, qualified, start, end, "", "");
    }
    String symbol(String generation, String file, String kind, String name, String qualified, int start, int end,
                  String visibility, String typeText) {
        String symbol = id();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", symbol); payload.put("fileId", file); payload.put("kind", kind); payload.put("name", name);
        payload.put("qualifiedName", qualified);
        payload.put("signature", kind.equals("FIELD") ? name + (typeText.isEmpty() ? "" : ": " + typeText)
                : name + (kind.equals("METHOD") ? "()" : ""));
        payload.put("startLine", start); payload.put("endLine", end); payload.put("startColumn", 1); payload.put("endColumn", 100);
        payload.put("annotations", List.of()); payload.put("visibility", visibility); payload.put("typeText", typeText);
        store.update("INSERT INTO symbols(generation_id,id,file_id,name,payload) VALUES(?,?,?,?,?)", generation, symbol, file, name, store.encode(payload)); return symbol;
    }
    void relation(String generation, String source, String target, String file, String kind, String status, String text, int line) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sourceId", source); payload.put("targetId", target); payload.put("fileId", file); payload.put("targetText", text);
        payload.put("kind", kind); payload.put("status", status); payload.put("reason", status + " evidence <script>bad()</script>");
        payload.put("startLine", line); payload.put("endLine", line); payload.put("startColumn", 1); payload.put("endColumn", 20); payload.put("resolver", "legacy-test");
        store.update("INSERT INTO relations(id,generation_id,source_id,target_id,payload) VALUES(?,?,?,?,?)", id(), generation, source, target, store.encode(payload));
    }
    Fixture seed() {
        String project = id(), snapshot = id(), task = id(), generation = id();
        store.update("INSERT INTO projects(id,name,source_path,path_hash,created_at) VALUES(?,?,?,?,?)", project, "Graph", "target/" + project, project, AtlasStore.now());
        store.update("INSERT INTO snapshots(id,project_id,manifest_hash,identity_hash,source_kind,storage_key,policy_version,created_at) VALUES(?,?,?,?,?,?,?,?)", snapshot, project, snapshot, snapshot, "NON_GIT", snapshot, "test", AtlasStore.now());
        store.update("INSERT INTO tasks(id,kind,status,snapshot_id,generation_id,analyzer_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", task, "INDEX", "PARTIAL_SUCCESS", snapshot, generation, "old-v2", AtlasStore.now(), AtlasStore.now());
        store.update("INSERT INTO generations(id,snapshot_id,task_id,published,created_at) VALUES(?,?,?,?,?)", generation, snapshot, task, true, AtlasStore.now());
        store.update("UPDATE snapshots SET published_generation=? WHERE id=?", generation, snapshot);
        String af = file(snapshot, "alpha/A.java"), bf = file(snapshot, "beta/B.java"), inf = file(snapshot, "beta/I.java"), cf = file(snapshot, "C.java");
        String a = symbol(generation, af, "CLASS", "A", "alpha.A", 1, 60, "PUBLIC", "");
        String name = symbol(generation, af, "FIELD", "name", "alpha.A.name", 2, 3, "PRIVATE", "String");
        String run = symbol(generation, af, "METHOD", "run", "alpha.A.run", 5, 15, "PUBLIC", "int");
        String helper = symbol(generation, af, "METHOD", "helper", "alpha.A.helper", 20, 25, "PRIVATE", "void");
        String inner = symbol(generation, af, "CLASS", "Inner", "alpha.A.Inner", 30, 50, "PRIVATE", "");
        String nested = symbol(generation, af, "METHOD", "nested", "alpha.A.Inner.nested", 35, 40, "PACKAGE", "void");
        String b = symbol(generation, bf, "CLASS", "B", "beta.B", 1, 40, "PUBLIC", "");
        String other = symbol(generation, bf, "METHOD", "other", "beta.B.other", 5, 20, "PUBLIC", "void");
        String iface = symbol(generation, inf, "INTERFACE", "I", "beta.I", 1, 5, "PUBLIC", "");
        String c = symbol(generation, cf, "CLASS", "C", "C", 1, 20, "PACKAGE", "");
        String constructor = symbol(generation, cf, "CONSTRUCTOR", "C", "C.C", 2, 10, "PUBLIC", "");
        relation(generation, run, other, af, "CALL", "CONFIRMED", "beta.B.other()", 8);
        relation(generation, run, other, af, "CALL", "CONFIRMED", "beta.B.other()", 9);
        relation(generation, other, run, bf, "CALL", "CONFIRMED", "alpha.A.run()", 10);
        relation(generation, run, null, af, "CALL", "UNRESOLVED", "missing.run()", 11);
        relation(generation, other, null, bf, "CALL", "UNRESOLVED", "missing.run()", 12);
        relation(generation, run, run, af, "TRANSACTION_BOUNDARY", "INFERRED", "Spring candidate", 5);
        relation(generation, a, iface, af, "IMPLEMENTS", "CONFIRMED", "beta.I", 1);
        relation(generation, nested, helper, af, "CALL", "CONFIRMED", "alpha.A.helper()", 36);
        relation(generation, other, null, bf, "RUNTIME_CALL", "UNRESOLVED", "alpha.A.run()", 10);
        relation(generation, constructor, run, cf, "CALL", "CONFIRMED", "alpha.A.run()", 4);
        store.update("INSERT INTO diagnostics(id,generation_id,file_id,payload) VALUES(?,?,?,?)", id(), generation, af, store.encode(Map.of("code", "PARSE_ERROR", "fileId", af, "message", "Other file partial", "startLine", 1)));
        return new Fixture(snapshot, generation, a, b, run, other, inner, nested);
    }
    JsonNode node(JsonNode page, String label) {
        for (var node : page.get("nodes")) if (label.equals(node.get("label").asText())) return node;
        throw new AssertionError("Missing node " + label + " in " + page);
    }
    JsonNode edge(JsonNode page, String source, String target, String kind) {
        for (var edge : page.get("edges")) if (source.equals(edge.get("source").asText()) && target.equals(edge.get("target").asText()) && kind.equals(edge.get("kind").asText())) return edge;
        throw new AssertionError("Missing edge " + page);
    }

    @Test void packagesTypesAndEvidencePreserveFixedSourceAndLegacyNestedOwners() {
        var fixture = seed(); var packages = ok(base(fixture) + "&view=PACKAGE");
        String alpha = node(packages, "alpha").get("id").asText(), beta = node(packages, "beta").get("id").asText();
        assertThat(node(packages, "默认包")).isNotNull();
        assertThat(edge(packages, alpha, beta, "CALL").get("evidenceCount").asInt()).isEqualTo(2);
        assertThat(packages.get("diagnosticCount").asInt()).isEqualTo(1);
        var types = ok(base(fixture) + "&view=TYPE");
        var call = edge(types, fixture.a, fixture.b, "CALL");
        assertThat(call.get("evidenceCount").asInt()).isEqualTo(2);
        assertThat(edge(types, fixture.inner, fixture.a, "CALL").get("status").asText()).isEqualTo("CONFIRMED");
        var source = node(types, "A").get("source");
        assertThat(source.get("generationId").asText()).isEqualTo(fixture.generation);
        assertThat(source.get("filePath").asText()).isEqualTo("alpha/A.java");
        assertThat(source.get("fileHash").asText()).isEqualTo("a".repeat(64));
        var evidence = ok("/snapshots/" + fixture.snapshot + "/graph/edges/" + call.get("id").asText() + "/evidence?generationId=" + fixture.generation + "&view=TYPE&limit=1");
        assertThat(evidence.get("total").asInt()).isEqualTo(2);
        assertThat(evidence.get("items")).hasSize(1);
        assertThat(evidence.get("items").get(0).get("source").get("startLine").asInt()).isIn(8, 9);
        var members = ok("/snapshots/" + fixture.snapshot + "/graph/types/" + fixture.inner + "/members?generationId=" + fixture.generation);
        assertThat(members.get("items")).hasSize(1);
        assertThat(members.get("items").get(0).get("id").asText()).isEqualTo(fixture.nested);
    }

    @Test void filtersPaginationAndCallCenterHaveExplicitCounts() {
        var fixture = seed(); var all = ok(base(fixture) + "&view=TYPE");
        String pkg = node(all, "A").get("packageId").asText();
        var scoped = ok(base(fixture) + "&view=TYPE&packageId=" + pkg + "&kinds=CALL&statuses=CONFIRMED");
        assertThat(scoped.get("nodes")).hasSize(2);
        assertThat(scoped.get("outsideScopeRelationCount").asInt()).isGreaterThanOrEqualTo(3);
        var filtered = ok(base(fixture) + "&view=TYPE&query=Inner&statuses=UNRESOLVED");
        assertThat(filtered.get("nodes")).hasSize(1); assertThat(filtered.get("edges")).isEmpty();
        var capped = ok(base(fixture) + "&view=TYPE&limit=1&edgeLimit=1");
        assertThat(capped.get("nodes")).hasSize(1); assertThat(capped.get("truncated").asBoolean()).isTrue();
        var call = ok(base(fixture) + "&view=CALL&focusSymbolId=" + fixture.run + "&limit=2&offset=1");
        assertThat(call.get("nodes")).hasSize(2);
        assertThat(call.get("nodes").findValuesAsText("id")).contains(fixture.run);
        var noMatch = ok(base(fixture) + "&view=CALL&focusSymbolId=" + fixture.run + "&query=none&statuses=CONFIRMED");
        assertThat(noMatch.get("nodes")).hasSize(1);
        assertThat(noMatch.get("nodes").get(0).get("id").asText()).isEqualTo(fixture.run);
        assertThat(call.get("neighborLimit").asInt()).isEqualTo(1);
        assertThat(call(base(fixture) + "&view=CALL&focusSymbolId=" + fixture.run + "&limit=1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void generationsAndMissingIndexNeverMix() {
        var first = seed(); var second = seed();
        assertThat(call(base(first) + "&generationId=" + second.generation).getStatusCode().is4xxClientError()).isTrue();
        assertThat(call("/snapshots/" + first.snapshot + "/graph?generationId=" + second.generation).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var before = ok(base(first));
        store.update("UPDATE snapshots SET published_generation=NULL WHERE id=?", first.snapshot);
        assertThat(ok("/snapshots/" + first.snapshot + "/graph").get("nodes")).isEmpty();
        assertThat(ok(base(first)).get("nodes")).isEqualTo(before.get("nodes"));
        store.update("UPDATE generations SET published=FALSE WHERE id=?", first.generation);
        assertThat(call(base(first)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void callEvidenceHasItsOwnPaginationAndInvalidQueriesAreRejected() {
        var fixture = seed();
        var page = ok(base(fixture) + "&view=CALL&focusSymbolId=" + fixture.run);
        var edge = edge(page, fixture.run, fixture.other, "CALL");
        var evidence = ok("/snapshots/" + fixture.snapshot + "/graph/edges/" + edge.get("id").asText()
                + "/evidence?generationId=" + fixture.generation + "&view=CALL&focusSymbolId=" + fixture.run + "&limit=1&offset=1");
        assertThat(evidence.get("total").asInt()).isEqualTo(2); assertThat(evidence.get("items")).hasSize(1);
        for (String suffix : List.of("&view=BAD", "&statuses=GUESSED", "&kinds=INVENTED", "&limit=201", "&edgeLimit=1001", "&offset=-1", "&includeSelf=maybe", "&unknown=x")) {
            assertThat(call(base(fixture) + suffix).getStatusCode()).as(suffix).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(call(base(fixture) + "&view=CALL&focusSymbolId=" + fixture.a).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void realNodesComeBeforeUnknownPlaceholdersAndFilteredPlaceholdersAreOmitted() {
        var fixture = seed();
        var typeFirst = ok(base(fixture) + "&view=TYPE&limit=1");
        assertThat(typeFirst.get("nodes").get(0).get("kind").asText()).isIn("CLASS", "INTERFACE");
        var packageFirst = ok(base(fixture) + "&view=PACKAGE&limit=1");
        assertThat(packageFirst.get("nodes").get(0).get("kind").asText()).isEqualTo("PACKAGE");
        var confirmed = ok(base(fixture) + "&view=TYPE&statuses=CONFIRMED");
        assertThat(confirmed.get("nodes").findValuesAsText("kind")).doesNotContain("UNRESOLVED");
        var all = ok(base(fixture) + "&view=TYPE");
        var placeholders = new ArrayList<JsonNode>();
        all.get("nodes").forEach(node -> { if ("UNRESOLVED".equals(node.get("kind").asText()) && "missing.run()".equals(node.get("label").asText())) placeholders.add(node); });
        assertThat(placeholders).hasSize(1);
        assertThat(edge(all, fixture.a, placeholders.getFirst().get("id").asText(), "CALL").get("evidenceCount").asInt()).isEqualTo(1);
        assertThat(edge(all, fixture.b, placeholders.getFirst().get("id").asText(), "CALL").get("evidenceCount").asInt()).isEqualTo(1);
    }

    @Test void structureViewBuildsPackageHierarchyWithoutSourceEvidence() {
        var fixture = seed();
        var structure = ok(base(fixture) + "&view=STRUCTURE");
        assertThat(structure.get("nodes").findValuesAsText("label")).contains("项目根", "alpha", "beta", "默认包");
        var alpha = node(structure, "alpha").get("id").asText();
        var beta = node(structure, "beta").get("id").asText();
        var root = node(structure, "项目根").get("id").asText();
        assertThat(edge(structure, root, alpha, "CONTAINS").get("evidenceCount").asInt()).isZero();
        assertThat(edge(structure, root, beta, "CONTAINS").get("status").asText()).isEqualTo("CONFIRMED");
        var scoped = ok(base(fixture) + "&view=STRUCTURE&query=alpha");
        assertThat(scoped.get("totalNodeCount").asInt()).isEqualTo(1);
        assertThat(scoped.get("outsideScopeRelationCount").asInt()).isEqualTo(1);
    }

    @Test void classViewIncludesFieldsMethodsAndVisibility() {
        var fixture = seed();
        var classes = ok(base(fixture) + "&view=CLASS");
        var type = node(classes, "A");
        assertThat(type.get("members")).hasSize(3);
        assertThat(type.get("members").findValuesAsText("signature")).contains("name: String", "run()", "helper()");
        assertThat(type.get("members").findValuesAsText("visibility")).contains("PUBLIC", "PRIVATE");
        assertThat(type.get("members").findValuesAsText("typeText")).contains("String", "int", "void");
        var scoped = ok(base(fixture) + "&view=CLASS&packageId=" + type.get("packageId").asText());
        assertThat(scoped.get("nodes").findValuesAsText("label")).contains("A", "Inner").doesNotContain("B");
    }
}
