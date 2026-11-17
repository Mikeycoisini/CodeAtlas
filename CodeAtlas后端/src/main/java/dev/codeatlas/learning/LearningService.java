package dev.codeatlas.learning;

import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class LearningService {
    private final AtlasStore store;
    private final SourceEvidenceService sources;

    public LearningService(AtlasStore store, SourceEvidenceService sources) { this.store = store; this.sources = sources; }

    public Map<String, Object> get(String kind, String id) {
        var row = store.one("SELECT * FROM learning_records WHERE kind=? AND id=? AND deleted_at IS NULL", kind, id);
        var result = sources.decode((String) row.get("payload"));
        for (String name : List.of("id", "kind", "title", "revision", "createdAt", "updatedAt", "knowledgeId", "generationId")) result.put(name, row.get(name));
        var links = store.rows("SELECT source_id FROM learning_sources WHERE record_id=? ORDER BY source_id", id);
        result.put("sourceIds", links.stream().map(link -> link.get("sourceId")).toList());
        result.put("sources", links.stream().map(link -> sources.get((String) link.get("sourceId"))).toList());
        Object knowledge = row.get("knowledgeId");
        result.put("knowledgeDeleted", knowledge != null && !store.rows("SELECT id FROM learning_records WHERE id=? AND deleted_at IS NOT NULL", knowledge).isEmpty());
        return result;
    }

    public Map<String, Object> list(String kind, String query, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw new ApiException(400, "INVALID_PAGE", "分页范围无效");
        String term = query.toLowerCase(Locale.ROOT);
        var matching = store.rows("SELECT id,title,payload FROM learning_records WHERE kind=? AND deleted_at IS NULL ORDER BY updated_at DESC,id", kind)
                .stream().filter(row -> (((String) row.get("title")) + " " + row.get("payload")).toLowerCase(Locale.ROOT).contains(term)).toList();
        var items = matching.stream().skip(offset).limit(limit).map(row -> get(kind, (String) row.get("id"))).toList();
        return Map.of("items", items, "total", matching.size(), "limit", limit, "offset", offset);
    }

    public Map<String, Object> create(String kind, Map<String, Object> input) {
        String key = text(input, "idempotencyKey", 100, true);
        var normalized = normalize(kind, input, true);
        String hash = SnapshotScanner.hash(store.encode(normalized));
        try {
            return store.transaction(() -> {
                var previous = store.rows("SELECT * FROM mutation_requests WHERE idempotency_key=?", key);
                if (!previous.isEmpty()) return reused(kind, hash, previous.getFirst());
                String id = AtlasStore.uuid();
                store.update("INSERT INTO mutation_requests(idempotency_key,operation,body_hash,result_id,created_at) VALUES(?,?,?,?,?)", key, kind, hash, id, AtlasStore.now());
                validateLinks(normalized, null);
                String title = (String) normalized.get(kind.equals("CARD") ? "question" : "title");
                store.update("INSERT INTO learning_records(id,kind,title,payload,knowledge_id,generation_id,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,1,?,?)",
                        id, kind, title, store.encode(normalized), blankToNull(normalized.get("knowledgeId")), blankToNull(normalized.get("generationId")), AtlasStore.now(), AtlasStore.now());
                saveLinks(id, normalized);
                return get(kind, id);
            });
        } catch (DuplicateKeyException e) {
            var previous = store.one("SELECT * FROM mutation_requests WHERE idempotency_key=?", key);
            return reused(kind, hash, previous);
        }
    }

    private Map<String, Object> reused(String kind, String hash, Map<String, Object> previous) {
        if (!kind.equals(previous.get("operation")) || !hash.equals(previous.get("bodyHash"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "同一请求标识已用于不同内容");
        return get(kind, (String) previous.get("resultId"));
    }

    public Map<String, Object> update(String kind, String id, Map<String, Object> input) {
        int revision = SourceEvidenceService.integer(input, "revision", 0);
        if (revision < 1) throw new ApiException(400, "REVISION_REQUIRED", "更新需要当前版本号");
        var normalized = normalize(kind, input, false);
        return store.transaction(() -> {
            var existing = store.one("SELECT * FROM learning_records WHERE id=? AND kind=? AND deleted_at IS NULL FOR UPDATE", id, kind);
            if (((Number) existing.get("revision")).intValue() != revision) throw new ApiException(409, "REVISION_CONFLICT", "资料已被其他操作更新，请保留草稿并重新加载");
            validateLinks(normalized, existing.get("knowledgeId"));
            String title = (String) normalized.get(kind.equals("CARD") ? "question" : "title");
            store.update("UPDATE learning_records SET title=?,payload=?,knowledge_id=?,generation_id=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                    title, store.encode(normalized), blankToNull(normalized.get("knowledgeId")), blankToNull(normalized.get("generationId")), AtlasStore.now(), id, revision);
            store.update("DELETE FROM learning_sources WHERE record_id=?", id);
            saveLinks(id, normalized);
            return get(kind, id);
        });
    }

    public Map<String, Object> delete(String kind, String id) {
        return store.transaction(() -> {
            store.one("SELECT id FROM learning_records WHERE id=? AND kind=? AND deleted_at IS NULL FOR UPDATE", id, kind);
            store.update("UPDATE learning_records SET deleted_at=?,updated_at=?,revision=revision+1 WHERE id=?", AtlasStore.now(), AtlasStore.now(), id);
            return Map.of("id", id, "deleted", true);
        });
    }

    private Map<String, Object> normalize(String kind, Map<String, Object> input, boolean creating) {
        var allowed = new HashSet<>(List.of("idempotencyKey", "revision", "sourceIds", "tags"));
        allowed.addAll(kind.equals("CARD") ? List.of("knowledgeId", "question", "answer", "explanation", "codeExample", "notes")
                : List.of("title", "body", "generationId", "type"));
        if (!allowed.containsAll(input.keySet())) throw new ApiException(400, "INVALID_FIELD", "资料包含不支持的字段");
        var normalized = new LinkedHashMap<String, Object>();
        if (kind.equals("CARD")) {
            normalized.put("question", text(input, "question", 500, true));
            normalized.put("answer", text(input, "answer", 16_000, true));
            normalized.put("explanation", text(input, "explanation", 16_000, false));
            normalized.put("codeExample", text(input, "codeExample", 16_000, false));
            normalized.put("notes", text(input, "notes", 16_000, false));
            normalized.put("knowledgeId", text(input, "knowledgeId", 36, false));
        } else {
            normalized.put("title", text(input, "title", 200, true));
            normalized.put("body", text(input, "body", kind.equals("DOCUMENT") ? 64_000 : 32_000, true));
            normalized.put("type", input.containsKey("type") ? text(input, "type", 40, false) : "NOTE");
            normalized.put("generationId", text(input, "generationId", 36, false));
        }
        normalized.put("tags", strings(input, "tags", 10, 32));
        List<String> sourceIds = strings(input, "sourceIds", 50, 36);
        String generation = (String) normalized.get("generationId");
        if (generation != null && !generation.isEmpty()) {
            var row = store.one("SELECT result_json,context_id FROM ai_generations WHERE id=? AND status='SUCCEEDED'", generation);
            var context = sources.decode((String) store.one("SELECT context_json FROM ai_contexts WHERE id=?", row.get("contextId")).get("contextJson"));
            if (!"EXPLANATION".equals(context.getOrDefault("operation", "EXPLANATION"))) throw new ApiException(400, "GENERATION_TYPE_MISMATCH", "此生成用途对应训练资料");
            var result = sources.decode((String) row.get("resultJson"));
            Object citations = result.get("citations");
            var verified = new LinkedHashSet<String>();
            if (citations instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> citation && "VERIFIED".equals(citation.get("verificationStatus"))) verified.add((String) citation.get("sourceId"));
                }
            }
            // AI-derived records obtain provenance from the server-validated generation.
            verified.addAll(sourceIds);
            if (creating) sourceIds = List.copyOf(verified);
        }
        normalized.put("sourceIds", sourceIds);
        return normalized;
    }

    private void validateLinks(Map<String, Object> normalized, Object existingKnowledge) {
        for (String source : sourceIds(normalized)) sources.get(source);
        Object knowledge = blankToNull(normalized.get("knowledgeId"));
        if (knowledge != null && !knowledge.equals(existingKnowledge)) store.one("SELECT id FROM learning_records WHERE id=? AND kind='KNOWLEDGE' AND deleted_at IS NULL", knowledge);
    }
    private void saveLinks(String id, Map<String, Object> normalized) {
        for (String source : sourceIds(normalized)) store.update("INSERT INTO learning_sources(record_id,source_id) VALUES(?,?)", id, source);
    }
    @SuppressWarnings("unchecked") private List<String> sourceIds(Map<String, Object> input) { return (List<String>) input.get("sourceIds"); }
    private Object blankToNull(Object value) { return value == null || "".equals(value) ? null : value; }
    public static String text(Map<String, Object> input, String name, int maximum, boolean required) {
        Object raw = input.get(name);
        if (raw != null && !(raw instanceof String)) throw new ApiException(400, "INVALID_FIELD", "字段类型无效 " + name);
        String value = raw == null ? "" : (String) raw;
        if (!Set.of("body", "answer", "explanation", "codeExample", "notes").contains(name)) value = value.trim();
        if ((required && value.isBlank()) || value.length() > maximum) throw new ApiException(400, "INVALID_FIELD", "字段长度无效 " + name);
        return value;
    }
    public static List<String> strings(Map<String, Object> input, String name, int maximum, int length) {
        Object value = input.get(name);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > maximum) throw new ApiException(400, "INVALID_FIELD", "列表大小无效 " + name);
        var result = new LinkedHashSet<String>();
        for (Object element : list) {
            if (!(element instanceof String text) || text.isBlank() || text.length() > length) throw new ApiException(400, "INVALID_FIELD", "列表内容无效 " + name);
            result.add(text.trim());
        }
        return List.copyOf(result);
    }
}
