package dev.codeatlas.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.tasks.TaskService;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Static, bounded control-flow graph for one fixed method symbol. */
@Service
public class FlowService {
    private static final int MAX_NODES = 180;
    private final AtlasStore store;
    private final TaskService tasks;

    public FlowService(AtlasStore store, TaskService tasks) { this.store = store; this.tasks = tasks; }

    public Map<String, Object> flow(String snapshot, String generationId, String symbolId, int limit) {
        var generation = store.generation(snapshot, generationId);
        if (generation == null) throw new ApiException(409, "NO_GENERATION", "该快照尚无已发布索引");
        var rows = store.payloads("SELECT payload FROM symbols WHERE generation_id=? AND id=?", generation, symbolId);
        if (rows.isEmpty()) throw ApiException.missing();
        var symbol = (JsonNode) rows.getFirst();
        var kind = symbol.path("kind").asText();
        if (!kind.equals("METHOD") && !kind.equals("CONSTRUCTOR")) throw new ApiException(400, "INVALID_FLOW_FOCUS", "请选择方法或构造器查看流程图");
        var fileId = symbol.path("fileId").asText();
        var file = store.one("SELECT * FROM snapshot_files WHERE snapshot_id=? AND id=?", snapshot, fileId);
        var path = (String) file.get("path");
        var startLine = symbol.path("startLine").asInt();
        var endLine = symbol.path("endLine").asInt();
        var nodeLimit = limit <= 0 ? MAX_NODES : Math.min(limit, MAX_NODES);
        ControlFlowBuilder.Result graph;
        try {
            var source = Files.readString(tasks.snapshotRoot(snapshot).resolve(path), StandardCharsets.UTF_8);
            var unit = StaticJavaParser.parse(source);
            var method = unit.findAll(MethodDeclaration.class).stream()
                    .filter(item -> item.getNameAsString().equals(symbol.path("name").asText()) && contains(item, startLine, endLine))
                    .findFirst().orElse(null);
            var constructor = method == null ? unit.findAll(ConstructorDeclaration.class).stream()
                    .filter(item -> item.getNameAsString().equals(symbol.path("name").asText()) && contains(item, startLine, endLine))
                    .findFirst().orElse(null) : null;
            if (method == null && constructor == null) {
                throw new ApiException(400, "FLOW_SOURCE_MISSING", "无法在当前固定快照中定位方法声明");
            }
            BlockStmt body = method == null ? constructor.getBody() : method.getBody().orElse(null);
            graph = ControlFlowBuilder.build(snapshot, generation, fileId, path, (String) file.get("sha256"),
                    startLine, endLine, nodeLimit, body);
        } catch (ApiException error) { throw error;
        } catch (Exception error) {
            throw new ApiException(422, "FLOW_PARSE_ERROR", "当前方法的源码无法生成静态流程图");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("snapshotId", snapshot); result.put("generationId", generation);
        result.put("symbolId", symbolId); result.put("signature", symbol.path("signature").asText());
        result.put("filePath", path); result.put("mode", "STATIC");
        result.put("nodes", graph.nodes()); result.put("edges", graph.edges());
        result.put("warnings", graph.warnings()); result.put("truncated", graph.truncated());
        return result;
    }

    private static boolean contains(Node node, int start, int end) {
        return node.getRange().map(range -> range.begin.line >= Math.max(start, 1) && range.end.line <= Math.max(end, start))
                .orElse(false) || node.getRange().map(range -> range.begin.line == start).orElse(false);
    }
}
