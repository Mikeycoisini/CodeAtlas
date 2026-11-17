package dev.codeatlas.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeatlas.analysis.JavaAnalyzer;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.tasks.TaskService;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static dev.codeatlas.architecture.ArchitectureModels.*;

@Service
public class ArchitectureService {
    public static final Set<String> PERSPECTIVES = Set.of("SYSTEM", "APPLICATION", "MODULE", "COMPONENT", "EXTERNAL");
    private final AtlasStore store;
    private final TaskService tasks;
    private final ObjectMapper json;

    public ArchitectureService(AtlasStore store, TaskService tasks, ObjectMapper json) {
        this.store = store;
        this.tasks = tasks;
        this.json = json;
    }

    public synchronized Document document(String snapshot, String generationId) {
        String generation = store.generation(snapshot, generationId);
        if (generation == null) return empty(snapshot, null);
        var existing = store.rows("SELECT * FROM architecture_projections WHERE snapshot_id=? AND generation_id=? AND analyzer_version=? ORDER BY created_at DESC LIMIT 1",
                snapshot, generation, ANALYZER_VERSION);
        if (!existing.isEmpty()) {
            try {
                return json.readValue((String) existing.getFirst().get("payload"), Document.class);
            } catch (Exception error) {
                throw new ApiException(500, "ARCHITECTURE_CORRUPT", "架构投影数据格式错误，请重建索引");
            }
        }
        var snapshotRow = store.snapshot(snapshot);
        var projectId = (String) snapshotRow.get("projectId");
        var project = store.one("SELECT name FROM projects WHERE id=?", projectId);
        var files = store.files(snapshot);
        var symbols = new ArrayList<JsonNode>();
        store.scanPayloads("SELECT id,payload FROM symbols WHERE generation_id=?", symbols::add, generation);
        var relations = new ArrayList<JsonNode>();
        store.scanPayloads("SELECT id,payload FROM relations WHERE generation_id=?", relations::add, generation);
        var analyzer = new ArchitectureAnalyzer(new ArchitectureAnalyzer.Input(projectId,
                String.valueOf(project.get("name")), snapshot, generation, tasks.snapshotRoot(snapshot),
                files, symbols, relations));
        var analyzed = analyzer.analyze();
        var projectionId = JavaAnalyzer.id(snapshot + "|architecture|" + generation + "|" + ANALYZER_VERSION);
        var digest = sha256(jsonBytes(analyzed));
        var finished = analyzed.withIdentity(projectionId, digest);
        var payload = jsonString(finished);
        store.update("INSERT INTO architecture_projections(id,project_id,snapshot_id,generation_id,analyzer_version,digest,payload,created_at) VALUES(?,?,?,?,?,?,?,?)",
                projectionId, projectId, snapshot, generation, ANALYZER_VERSION, digest, payload, AtlasStore.now());
        return finished;
    }

    public Map<String, Object> query(String snapshot, String generationId, String perspective, String focusId, int depth, int limit) {
        if (!PERSPECTIVES.contains(perspective)) throw new ApiException(400, "INVALID_ARCHITECTURE_QUERY", "架构视角无效");
        if (depth < 0 || depth > 4 || limit < 1 || limit > 200) throw new ApiException(400, "INVALID_ARCHITECTURE_QUERY", "depth 或 limit 超出范围");
        var document = document(snapshot, generationId);
        if (document.generationId() == null) {
            var result = metadata(document, perspective, focusId, depth, limit);
            result.put("nodes", List.of());
            result.put("edges", List.of());
            result.put("groups", List.of());
            result.put("evidence", List.of());
            result.put("totalNodeCount", 0);
            result.put("totalEdgeCount", 0);
            result.put("omittedEdgeCount", 0);
            return result;
        }
        var byId = new LinkedHashMap<String, Node>();
        for (var node : document.nodes()) byId.put(node.id(), node);
        var included = new LinkedHashSet<String>();
        if (focusId != null && !focusId.isBlank()) {
            if (!byId.containsKey(focusId)) throw ApiException.missing();
            includeTree(focusId, byId, included, 0, depth);
        } else {
            for (var node : document.nodes()) {
                if (perspective.equals("SYSTEM")
                        || perspective.equals("APPLICATION") && !node.kind().equals("SYSTEM")
                        || perspective.equals("MODULE") && Set.of("MODULE", "COMPONENT").contains(node.kind())
                        || perspective.equals("COMPONENT") && node.kind().equals("COMPONENT")
                        || perspective.equals("EXTERNAL") && Set.of("EXTERNAL_SERVICE", "DATASTORE").contains(node.kind())) {
                    included.add(node.id());
                }
            }
        }
        var ordered = included.stream().map(byId::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(ArchitectureService::rank).thenComparing(Node::name).thenComparing(Node::id)).toList();
        var page = ordered.stream().limit(limit).toList();
        var pageIds = new LinkedHashSet<>(page.stream().map(Node::id).toList());
        var allEdges = document.edges().stream().filter(edge -> included.contains(edge.source()) && included.contains(edge.target())).toList();
        var pageEdges = allEdges.stream().filter(edge -> pageIds.contains(edge.source()) && pageIds.contains(edge.target())).toList();
        var evidenceIds = new LinkedHashSet<String>();
        page.forEach(node -> evidenceIds.addAll(node.evidenceIds()));
        pageEdges.forEach(edge -> evidenceIds.addAll(edge.evidenceIds()));
        var evidence = document.evidence().stream().filter(item -> evidenceIds.contains(item.id())).limit(500).toList();
        var groups = document.groups().stream().map(group -> new Group(group.id(), group.kind(), group.name(), group.parentId(),
                        group.nodeIds().stream().filter(pageIds::contains).toList()))
                .filter(group -> !group.nodeIds().isEmpty()).toList();
        var result = metadata(document, perspective, focusId, depth, limit);
        result.put("nodes", page);
        result.put("edges", pageEdges);
        result.put("groups", groups);
        result.put("evidence", evidence);
        result.put("totalNodeCount", ordered.size());
        result.put("totalEdgeCount", allEdges.size());
        result.put("omittedEdgeCount", Math.max(0, allEdges.size() - pageEdges.size()));
        result.put("truncated", document.truncated() || ordered.size() > page.size() || allEdges.size() > pageEdges.size());
        return result;
    }

    private Map<String, Object> metadata(Document document, String perspective, String focusId, int depth, int limit) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", document.schemaVersion());
        result.put("projectId", document.projectId());
        result.put("snapshotId", document.snapshotId());
        result.put("generationId", document.generationId());
        result.put("projectionId", document.projectionId());
        result.put("digest", document.digest());
        result.put("analyzerVersion", document.analyzerVersion());
        result.put("perspective", perspective);
        result.put("focusId", focusId == null ? "" : focusId);
        result.put("depth", depth);
        result.put("limit", limit);
        result.put("offset", 0);
        result.put("coverage", document.coverage());
        result.put("diagnostics", document.diagnostics());
        return result;
    }

    private void includeTree(String root, Map<String, Node> byId, Set<String> included, int level, int depth) {
        if (!included.add(root) || level >= depth) return;
        for (var node : byId.values()) if (root.equals(node.parentId())) includeTree(node.id(), byId, included, level + 1, depth);
    }

    private static int rank(Node node) {
        return switch (node.kind()) {
            case "SYSTEM" -> 0;
            case "APPLICATION" -> 1;
            case "MODULE" -> 2;
            case "COMPONENT" -> 3;
            case "EXTERNAL_SERVICE" -> 4;
            case "DATASTORE" -> 5;
            default -> 6;
        };
    }

    private Document empty(String snapshot, String generation) {
        var snapshotRow = store.snapshot(snapshot);
        return new Document(SCHEMA_VERSION, (String) snapshotRow.get("projectId"), snapshot, generation,
                ANALYZER_VERSION, "", "", List.of(), List.of(), List.of(), List.of(),
                List.of(new Diagnostic("ARCH_NO_GENERATION", "该快照尚无已发布索引。", null, null, 0)),
                new Coverage(0, 0, 0, 0, 0, 0, 0, 0, false), false);
    }

    private byte[] jsonBytes(Object value) {
        try { return json.writeValueAsBytes(value); }
        catch (Exception error) { throw new IllegalStateException("架构投影序列化失败", error); }
    }

    private String jsonString(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("架构投影序列化失败", error); }
    }

    private static String sha256(byte[] value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value);
            var result = new StringBuilder();
            for (var item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
