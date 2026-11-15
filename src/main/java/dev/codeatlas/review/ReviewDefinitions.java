package dev.codeatlas.review;

import dev.codeatlas.learning.LearningService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ReviewDefinitions {
    private final AtlasStore store;
    private final SourceEvidenceService json;
    public ReviewDefinitions(AtlasStore store, SourceEvidenceService json) { this.store = store; this.json = json; }
    public Map<String, Object> card(String id, boolean lock) {
        return store.one("SELECT * FROM learning_records WHERE id=? AND kind='CARD'" + (lock ? " FOR UPDATE" : ""), id);
    }
    public Map<String, Object> freeze(Map<String, Object> row) {
        return freeze(row, true);
    }
    public Map<String, Object> freeze(Map<String, Object> row, boolean includeKnowledge) {
        var result = json.decode((String) row.get("payload"));
        for (String field : List.of("id", "revision", "knowledgeId")) result.put(field, row.get(field));
        var sources = new ArrayList<Map<String, Object>>();
        for (var link : store.rows("SELECT s.id,s.payload FROM learning_sources l JOIN evidence_sources s ON s.id=l.source_id WHERE l.record_id=? ORDER BY s.id FOR UPDATE", row.get("id"))) {
            var source = json.decode((String) link.get("payload")); source.remove("content"); source.put("id", link.get("id")); sources.add(source);
        }
        result.put("sources", sources); result.put("sourceIds", sources.stream().map(source -> source.get("id")).toList());
        result.put("contentDigest", digest(result));
        result.put("knowledgeRevision", 0);
        if (includeKnowledge && row.get("knowledgeId") != null) {
            var knowledge = store.one("SELECT id,title,payload,revision,deleted_at FROM learning_records WHERE id=? FOR UPDATE", row.get("knowledgeId"));
            result.put("knowledgeRevision", knowledge.get("revision"));
            result.put("knowledgeTitle", knowledge.get("title"));
            result.put("knowledgeSnapshot", json.decode((String) knowledge.get("payload")));
            result.put("knowledgeDeleted", knowledge.get("deletedAt") != null);
        }
        return result;
    }
    public String digest(Map<String, Object> frozen) {
        var content = new LinkedHashMap<String, Object>();
        for (String field : List.of("question", "answer", "explanation", "codeExample")) content.put(field, frozen.getOrDefault(field, ""));
        content.put("sources", frozen.get("sources")); return SnapshotScanner.hash(store.encode(content));
    }
    public Map<String, Object> view(Map<String, Object> frozen, boolean revealed) {
        var result = json.decode(store.encode(frozen)); result.remove("knowledgeSnapshot");
        if (!revealed) for (String field : List.of("answer", "explanation", "codeExample", "notes")) result.remove(field);
        var sources = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) result.get("sources")) {
            @SuppressWarnings("unchecked") var metadata = (Map<String, Object>) raw;
            try { var current = json.get((String) metadata.get("id")); current.put("availability", "AVAILABLE"); sources.add(current); }
            catch (ApiException error) { metadata.put("availability", "UNAVAILABLE"); metadata.put("availabilityMessage", "历史源码暂不可用，回忆与复习记录已保留"); sources.add(metadata); }
        }
        result.put("sources", sources); return result;
    }
    public static void fields(Map<String, Object> input, String... allowed) {
        if (!Set.of(allowed).containsAll(input.keySet())) throw new ApiException(400, "INVALID_FIELD", "包含不支持的字段");
    }
    public static String text(Map<String, Object> input, String field, int limit, boolean required) { return LearningService.text(input, field, limit, required); }
    public static int number(Map<String, Object> input, String field) {
        Object raw = input.get(field);
        if (!(raw instanceof Number value) || value.doubleValue() != value.intValue() || value.intValue() < 1) throw new ApiException(400, "INVALID_FIELD", "请提供有效整数 " + field);
        return value.intValue();
    }
    public static void revision(Map<String, Object> row, Map<String, Object> input) {
        if (((Number) row.get("revision")).intValue() != number(input, "revision")) throw new ApiException(409, "REVISION_CONFLICT", "内容已更新，请保留输入并重新加载");
    }
    public static void page(int limit, int offset) { if (limit < 1 || limit > 100 || offset < 0) throw new ApiException(400, "INVALID_PAGE", "分页范围无效"); }
}
