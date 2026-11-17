package dev.codeatlas.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.tasks.TaskService;
import dev.codeatlas.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

@Service
public class SourceEvidenceService {
    private final AtlasStore store;
    private final TaskService tasks;
    private final ObjectMapper json;

    public SourceEvidenceService(AtlasStore store, TaskService tasks, ObjectMapper json) {
        this.store = store; this.tasks = tasks; this.json = json;
    }

    public Map<String, Object> content(String snapshot, String file) {
        var metadata = store.one("SELECT * FROM snapshot_files WHERE snapshot_id=? AND id=?", snapshot, file);
        try {
            Path root = tasks.snapshotRoot(snapshot);
            Path path = root.resolve((String) metadata.get("path")).normalize();
            if (!path.startsWith(root) || Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root.toRealPath()))
                throw new ApiException(403, "IMPORT_BOUNDARY", "源码路径越界");
            byte[] bytes = Files.readAllBytes(path);
            if (!SnapshotScanner.hash(bytes).equals(metadata.get("sha256")))
                throw new ApiException(409, "SNAPSHOT_CORRUPTED", "快照文件校验失败");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return Map.of("snapshotId", snapshot, "fileId", file, "path", metadata.get("path"), "sha256", metadata.get("sha256"),
                    "encoding", "UTF-8", "content", text, "lineCount", text.split("\n", -1).length);
        } catch (CharacterCodingException e) {
            throw new ApiException(422, "UNSUPPORTED_ENCODING", "该文件包含非 UTF-8 文本或二进制内容");
        } catch (java.io.IOException e) {
            throw new ApiException(409, "SNAPSHOT_UNAVAILABLE", "快照文件不可用，请核对数据目录");
        }
    }

    public Map<String, Object> create(Map<String, Object> input) {
        String snapshotId = required(input, "snapshotId");
        var snapshot = store.snapshot(snapshotId);
        String generationId = optional(input, "generationId");
        String symbolId = optional(input, "symbolId");
        String fileId = optional(input, "fileId");
        JsonNode symbol = null;
        if (!symbolId.isEmpty()) {
            generationId = store.generation(snapshotId, generationId);
            if (generationId == null) throw ApiException.missing();
            var symbols = store.payloads("SELECT payload FROM symbols WHERE generation_id=? AND id=?", generationId, symbolId);
            if (symbols.isEmpty()) throw ApiException.missing();
            symbol = (JsonNode) symbols.getFirst();
            String expectedFile = symbol.get("fileId").asText();
            if (!fileId.isEmpty() && !fileId.equals(expectedFile)) throw new ApiException(400, "SOURCE_MISMATCH", "符号与文件不匹配");
            fileId = expectedFile;
        } else if (!generationId.isEmpty()) {
            generationId = store.generation(snapshotId, generationId);
        }
        if (fileId.isEmpty()) throw new ApiException(400, "INVALID_SOURCE", "请选择来源文件或符号");
        var file = content(snapshotId, fileId);
        int start = integer(input, "startLine", symbol == null ? 1 : symbol.get("startLine").asInt());
        int end = integer(input, "endLine", symbol == null ? (int) file.get("lineCount") : symbol.get("endLine").asInt());
        if (start < 1 || end < start || end > (int) file.get("lineCount")
                || (symbol != null && (start < symbol.get("startLine").asInt() || end > symbol.get("endLine").asInt())))
            throw new ApiException(400, "INVALID_SOURCE_RANGE", "来源范围超出文件或符号声明");
        if (input.containsKey("projectId") && !snapshot.get("projectId").equals(input.get("projectId")))
            throw new ApiException(400, "SOURCE_MISMATCH", "来源项目与快照不匹配");
        var source = new LinkedHashMap<String, Object>();
        source.put("projectId", snapshot.get("projectId")); source.put("snapshotId", snapshotId);
        source.put("generationId", generationId.isEmpty() ? null : generationId);
        source.put("fileId", fileId); source.put("symbolId", symbolId.isEmpty() ? null : symbolId);
        source.put("filePath", file.get("path")); source.put("sha256", file.get("sha256"));
        source.put("startLine", start); source.put("endLine", end);
        source.put("symbolName", symbol == null ? "" : symbol.get("qualifiedName").asText());
        String hash = SnapshotScanner.hash(store.encode(source));
        String id = UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString();
        source.put("id", id);
        try {
            store.update("INSERT INTO evidence_sources(id,source_hash,project_id,snapshot_id,file_id,generation_id,symbol_id,payload) VALUES(?,?,?,?,?,?,?,?)",
                    id, hash, source.get("projectId"), snapshotId, fileId, source.get("generationId"), source.get("symbolId"), store.encode(source));
        } catch (DuplicateKeyException e) {
            store.one("SELECT id FROM evidence_sources WHERE id=? AND source_hash=?", id, hash);
        }
        return get(id);
    }

    public Map<String, Object> get(String id) {
        var row = store.one("SELECT payload FROM evidence_sources WHERE id=?", id);
        Map<String, Object> source = decode((String) row.get("payload"));
        var file = content((String) source.get("snapshotId"), (String) source.get("fileId"));
        if (!source.get("sha256").equals(file.get("sha256"))) throw new ApiException(409, "SOURCE_CHANGED", "来源哈希不匹配");
        String[] lines = ((String) file.get("content")).split("\n", -1);
        int start = ((Number) source.get("startLine")).intValue();
        int end = ((Number) source.get("endLine")).intValue();
        source.put("content", String.join("\n", Arrays.copyOfRange(lines, start - 1, end)));
        source.put("snapshotOutdated", !store.rows("SELECT id FROM snapshots WHERE project_id=? AND created_at>?", source.get("projectId"), store.snapshot((String) source.get("snapshotId")).get("createdAt")).isEmpty());
        source.put("currentSourceStatus", "UNCHECKED");
        return source;
    }

    public Map<String, Object> checkCurrent(String id) {
        var source = get(id);
        var project = store.one("SELECT source_path FROM projects WHERE id=?", source.get("projectId"));
        var result = new LinkedHashMap<String, Object>();
        result.put("sourceId", id);
        result.put("snapshotSha256", source.get("sha256"));
        String status;
        String message;
        try {
            String hash = tasks.currentFileHash((String) project.get("sourcePath"), (String) source.get("filePath"));
            result.put("currentSha256", hash);
            boolean matched = hash.equals(source.get("sha256"));
            status = matched ? "MATCHED" : "CHANGED";
            message = matched ? "检查时当前文件与历史快照一致" : "当前源码已变化，引用继续指向历史快照";
        } catch (NoSuchFileException e) {
            status = "MISSING"; message = "当前源码文件或目录已缺失，历史快照仍保留";
        } catch (java.io.IOException e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            if (detail.startsWith("IMPORT_BOUNDARY")) { status = "UNSAFE"; message = "当前路径包含链接、越界或特殊文件，已停止检查"; }
            else if (detail.startsWith("IMPORT_LIMIT")) { status = "LIMIT_EXCEEDED"; message = "当前文件超过 2 MiB 检查上限"; }
            else { status = "UNAVAILABLE"; message = "当前文件不可读或检查期间发生变化，请稍后重试"; }
        } catch (SecurityException | InvalidPathException e) {
            status = "UNAVAILABLE"; message = "当前文件不可访问，请核对本机目录权限";
        }
        result.put("status", status); result.put("message", message); result.put("checkedAt", AtlasStore.now());
        return result;
    }

    public Map<String, Object> decode(String payload) {
        try { return json.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Stored learning data is invalid", e); }
    }
    public static String required(Map<String, Object> input, String name) {
        String value = optional(input, name);
        if (value.isBlank()) throw new ApiException(400, "INVALID_REQUEST", "缺少字段 " + name);
        return value;
    }
    public static String optional(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (value == null) return "";
        if (!(value instanceof String text)) throw new ApiException(400, "INVALID_REQUEST", "字段类型错误 " + name);
        return text.trim();
    }
    public static int integer(Map<String, Object> input, String name, int fallback) {
        Object value = input.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue()) throw new ApiException(400, "INVALID_REQUEST", "字段必须为整数 " + name);
        return n.intValue();
    }
}
