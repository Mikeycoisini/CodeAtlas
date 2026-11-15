package dev.codeatlas.web;

import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.tasks.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AtlasController {
    private final AtlasStore store;
    private final TaskService tasks;
    private final LocalAccessFilter access;
    private final dev.codeatlas.learning.SourceEvidenceService evidence;
    public AtlasController(AtlasStore store, TaskService tasks, LocalAccessFilter access, dev.codeatlas.learning.SourceEvidenceService evidence) {
        this.store = store; this.tasks = tasks; this.access = access; this.evidence = evidence;
    }
    record ImportRequest(String path) {}
    record IndexRequest(boolean rebuild) {}

    @GetMapping("/session") public Object session() { return Map.of("token", access.token()); }
    @GetMapping("/health") public Object health() { return Map.of("status", "UP", "version", "0.4.1"); }
    @GetMapping("/projects") public Object projects() { return store.rows("SELECT * FROM projects ORDER BY created_at DESC"); }
    @PostMapping("/imports") public ResponseEntity<?> importProject(@RequestBody ImportRequest request) {
        return ResponseEntity.accepted().body(tasks.importProject(request.path()));
    }
    @GetMapping({"/imports/{id}", "/tasks/{id}"}) public Object task(@PathVariable String id) { return store.task(id); }
    @PostMapping({"/imports/{id}/cancel", "/tasks/{id}/cancel"}) public Object cancel(@PathVariable String id) { return store.cancel(id); }
    @PostMapping("/tasks/{id}/retry") public ResponseEntity<?> retry(@PathVariable String id) { return ResponseEntity.accepted().body(tasks.retry(id)); }
    @PostMapping("/snapshots/{id}/index") public ResponseEntity<?> index(@PathVariable String id, @RequestBody IndexRequest request) {
        var task = tasks.index(id, request.rebuild());
        boolean active = List.of("QUEUED", "RUNNING").contains(task.get("status"));
        return ResponseEntity.status(active ? 202 : 200).body(task);
    }

    @GetMapping("/projects/{project}/snapshots") public Object snapshots(@PathVariable String project) {
        store.one("SELECT id FROM projects WHERE id=?", project);
        var snapshots = store.rows("SELECT * FROM snapshots WHERE project_id=? ORDER BY created_at DESC", project);
        for (var snapshot : snapshots) {
            var latest = store.rows("SELECT * FROM tasks WHERE snapshot_id=? AND kind='INDEX' ORDER BY created_at DESC", snapshot.get("id"));
            snapshot.put("latestTask", latest.isEmpty() ? null : latest.getFirst());
        }
        return snapshots;
    }

    @GetMapping("/snapshots/{snapshot}/files") public Object files(@PathVariable String snapshot,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "500") int limit, @RequestParam(defaultValue = "0") int offset) {
        var matched = store.files(snapshot).stream().filter(f -> ((String) f.get("path")).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        return page(matched, null, limit, offset);
    }

    @GetMapping("/snapshots/{snapshot}/files/{file}/content") public Object content(@PathVariable String snapshot, @PathVariable String file) {
        return evidence.content(snapshot, file);
    }

    @GetMapping("/snapshots/{snapshot}/symbols") public Object symbols(@PathVariable String snapshot,
            @RequestParam(defaultValue = "") String query, @RequestParam(required = false) String kind,
            @RequestParam(required = false) String generationId,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        String generation = store.generation(snapshot, generationId);
        List<Object> matched = generation == null ? List.of() : store.payloads("SELECT payload FROM symbols WHERE generation_id=? ORDER BY name,id", generation);
        String term = query.toLowerCase(Locale.ROOT);
        matched = matched.stream().filter(o -> {
            var symbol = (com.fasterxml.jackson.databind.JsonNode) o;
            return (kind == null || symbol.get("kind").asText().equals(kind))
                    && (symbol.get("name").asText().toLowerCase(Locale.ROOT).contains(term)
                    || (term.contains(".") && symbol.get("qualifiedName").asText().toLowerCase(Locale.ROOT).contains(term))
                    || symbol.get("signature").asText().toLowerCase(Locale.ROOT).contains(term));
        }).toList();
        return page(matched, generation, limit, offset);
    }

    @GetMapping("/snapshots/{snapshot}/symbols/{symbol}/relations") public Object relations(@PathVariable String snapshot,
            @PathVariable String symbol, @RequestParam(required = false) String generationId,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        String generation = store.generation(snapshot, generationId);
        if (generation == null) throw ApiException.missing();
        store.one("SELECT id FROM symbols WHERE generation_id=? AND id=?", generation, symbol);
        return page(store.payloads("SELECT payload FROM relations WHERE generation_id=? AND (source_id=? OR target_id=?) ORDER BY id", generation, symbol, symbol), generation, limit, offset);
    }
    @GetMapping("/snapshots/{snapshot}/symbols/{symbol}") public Object symbol(@PathVariable String snapshot,
            @PathVariable String symbol, @RequestParam(required = false) String generationId) {
        String generation = store.generation(snapshot, generationId);
        if (generation == null) throw ApiException.missing();
        var symbols = store.payloads("SELECT payload FROM symbols WHERE generation_id=? AND id=?", generation, symbol);
        if (symbols.isEmpty()) throw ApiException.missing();
        return symbols.getFirst();
    }
    @GetMapping("/tasks/{task}/diagnostics") public Object diagnostics(@PathVariable String task,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        var row = store.task(task);
        return page(store.payloads("SELECT payload FROM diagnostics WHERE generation_id=? ORDER BY file_id", row.get("generationId")), (String) row.get("generationId"), limit, offset);
    }
    @GetMapping("/tasks/{task}/symbols") public Object partialSymbols(@PathVariable String task,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        var row = store.task(task);
        return page(store.payloads("SELECT payload FROM symbols WHERE generation_id=? ORDER BY name,id", row.get("generationId")), (String) row.get("generationId"), limit, offset);
    }
    private Object page(List<?> values, String generation, int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0) throw new ApiException(400, "INVALID_PAGE", "limit 范围为 1–1000，offset 必须为非负数");
        int start = Math.min(offset, values.size());
        int end = start + Math.min(limit, values.size() - start);
        var result = new LinkedHashMap<String, Object>();
        result.put("items", values.subList(start, end)); result.put("total", values.size());
        result.put("limit", limit); result.put("offset", offset); result.put("generationId", generation);
        return result;
    }
}
