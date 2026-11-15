package dev.codeatlas.ai;

import dev.codeatlas.learning.LearningService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class AiContextService {
    private final AtlasStore store;
    private final SourceEvidenceService sources;
    private final AiClient client;
    public AiContextService(AtlasStore store, SourceEvidenceService sources, AiClient client) { this.store = store; this.sources = sources; this.client = client; }

    public Map<String, Object> prepare(Map<String, Object> input) {
        return prepare(input, "EXPLANATION", Map.of());
    }

    public Map<String, Object> prepareTraining(Map<String, Object> input, String operation, Map<String, Object> training) {
        if (!Set.of("TRAINING_QUESTIONS", "TRAINING_FEEDBACK").contains(operation)) throw new ApiException(400, "INVALID_REQUEST", "训练生成用途无效");
        return prepare(input, operation, training);
    }

    public Map<String, Object> prepareGuide(Map<String, Object> input, Map<String, Object> guide) {
        return prepare(input, "LEARNING_GUIDE", guide);
    }

    private Map<String, Object> prepare(Map<String, Object> input, String operation, Map<String, Object> training) {
        boolean guide = operation.equals("LEARNING_GUIDE");
        var provider = client.provider();
        if (!Boolean.TRUE.equals(provider.get("configured"))) throw new ApiException(503, "PROVIDER_NOT_CONFIGURED", "请在本机配置模型密钥");
        String question = LearningService.text(input, "question", 2000, false);
        if (question.isBlank()) question = "请解释所选源码的业务逻辑、技术要点和不确定项";
        String depth = input.containsKey("depth") ? SourceEvidenceService.required(input, "depth") : "STANDARD";
        String purpose = operation.equals("EXPLANATION") ? (input.containsKey("purpose") ? SourceEvidenceService.required(input, "purpose") : "METHOD_EXPLANATION") : operation;
        if (!Set.of("BRIEF", "STANDARD", "DETAILED").contains(depth) || (operation.equals("EXPLANATION") && !Set.of("METHOD_EXPLANATION", "TECHNICAL_NOTE", "PROJECT_NOTE", "BUSINESS_FLOW").contains(purpose)))
            throw new ApiException(400, "INVALID_REQUEST", "解释深度或用途无效");
        List<String> sourceIds = LearningService.strings(input, "sourceIds", 6, 36);
        var selected = new ArrayList<Map<String, Object>>();
        if (sourceIds.isEmpty() && !guide) {
            var focus = sources.create(input);
            if (((String) focus.get("content")).length() > 10_000) throw new ApiException(400, "CONTEXT_LIMIT", "所选声明超过单片段预算，请选择更小的方法或范围");
            selected.add(focus);
            int start = ((Number) focus.get("startLine")).intValue();
            if (start > 1) {
                var header = new LinkedHashMap<String, Object>();
                header.put("snapshotId", focus.get("snapshotId")); header.put("fileId", focus.get("fileId"));
                header.put("generationId", focus.get("generationId")); header.put("startLine", 1); header.put("endLine", Math.min(start - 1, 50));
                selected.add(sources.create(header));
            }
        } else for (String id : sourceIds) selected.add(sources.get(id));
        String snapshot = selected.isEmpty() ? "" : (String) selected.getFirst().get("snapshotId");
        Object generation = selected.isEmpty() ? null : selected.getFirst().get("generationId");
        if (selected.stream().anyMatch(s -> !snapshot.equals(s.get("snapshotId")) || !Objects.equals(generation, s.get("generationId"))))
            throw new ApiException(400, "SOURCE_MISMATCH", "一次解释的来源须属于同一快照与代次");
        var frozen = selected.stream().map(source -> { var copy = new LinkedHashMap<>(source); copy.put("sourceId", source.get("id")); return copy; }).toList();
        String parentId = guide ? "" : LearningService.text(input, "parentGenerationId", 36, false);
        var context = new LinkedHashMap<String, Object>();
        context.put("operation", operation);
        context.put("question", question); context.put("depth", depth); context.put("purpose", purpose); context.put("sources", frozen);
        if (!operation.equals("EXPLANATION")) context.put(guide ? "guide" : "training", training);
        context.put("history", guide ? List.of() : history(parentId, snapshot));
        String contextJson = store.encode(context);
        if (contextJson.length() > 24_000 || contextJson.getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw new ApiException(400, "CONTEXT_LIMIT", "上下文超过 24,000 字符或 64 KiB，请减少来源或收窄问题");
        String id = AtlasStore.uuid();
        String expiry = Instant.now().plusSeconds(900).toString();
        store.transaction(() -> {
            store.update("INSERT INTO ai_contexts(id,context_json,context_digest,provider_fingerprint,provider_json,parent_generation_id,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    id, contextJson, SnapshotScanner.hash(contextJson), provider.get("fingerprint"), store.encode(provider), parentId.isEmpty() ? null : parentId, expiry, AtlasStore.now());
            for (var source : selected) store.update("INSERT INTO ai_context_sources(context_id,source_id) VALUES(?,?)", id, source.get("id"));
            return null;
        });
        return get(id);
    }

    private List<Map<String, Object>> history(String parent, String snapshot) {
        var history = new ArrayList<Map<String, Object>>();
        String current = parent;
        for (int count = 0; count < 4 && !current.isEmpty(); count++) {
            var generation = store.one("SELECT * FROM ai_generations WHERE id=? AND status='SUCCEEDED'", current);
            var prior = store.one("SELECT * FROM ai_contexts WHERE id=?", generation.get("contextId"));
            var context = sources.decode((String) prior.get("contextJson"));
            if (!"EXPLANATION".equals(context.getOrDefault("operation", "EXPLANATION")))
                throw new ApiException(400, "GENERATION_TYPE_MISMATCH", "追问须使用源码解释类型的历史");
            Object rawSources = context.get("sources");
            if (!(rawSources instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> first) || !snapshot.equals(first.get("snapshotId")))
                throw new ApiException(400, "SOURCE_MISMATCH", "追问须关联同一源码快照");
            var explanation = sources.decode((String) generation.get("resultJson"));
            history.add(Map.of("question", context.get("question"), "answer", limitText((String) explanation.get("explanation"), 2000)));
            current = prior.get("parentGenerationId") == null ? "" : (String) prior.get("parentGenerationId");
        }
        Collections.reverse(history);
        return history;
    }
    private String limitText(String value, int maximum) { return value.substring(0, value.offsetByCodePoints(0, Math.min(maximum, value.codePointCount(0, value.length())))); }

    public Map<String, Object> get(String id) {
        var row = store.one("SELECT * FROM ai_contexts WHERE id=?", id);
        var result = sources.decode((String) row.get("contextJson"));
        result.put("id", id); result.put("provider", sources.decode((String) row.get("providerJson")));
        result.put("contextJson", row.get("contextJson")); result.put("contextDigest", row.get("contextDigest"));
        result.put("expiresAt", row.get("expiresAt")); result.put("providerFingerprint", row.get("providerFingerprint"));
        result.put("characterCount", ((String) row.get("contextJson")).length());
        result.put("byteCount", ((String) row.get("contextJson")).getBytes(StandardCharsets.UTF_8).length);
        return result;
    }

    public Map<String, Object> consent(Map<String, Object> input) {
        String context = SourceEvidenceService.required(input, "contextId");
        String decision = SourceEvidenceService.required(input, "decision");
        if (!Set.of("ALLOW", "DENY").contains(decision)) throw new ApiException(400, "INVALID_REQUEST", "授权决定无效");
        return store.transaction(() -> {
            var row = store.one("SELECT * FROM ai_contexts WHERE id=? FOR UPDATE", context);
            fresh(row);
            var previous = store.rows("SELECT * FROM ai_consents WHERE context_id=?", context);
            if (!previous.isEmpty()) {
                if (!decision.equals(previous.getFirst().get("decision"))) throw new ApiException(409, "CONSENT_SCOPE_CHANGED", "该预览已有授权决定，请重新准备");
                return previous.getFirst();
            }
            String id = AtlasStore.uuid();
            store.update("INSERT INTO ai_consents(id,context_id,decision,created_at) VALUES(?,?,?,?)", id, context, decision, AtlasStore.now());
            return store.one("SELECT * FROM ai_consents WHERE id=?", id);
        });
    }

    public static void fresh(Map<String, Object> context) {
        if (!Instant.parse((String) context.get("expiresAt")).isAfter(Instant.now())) throw new ApiException(409, "CONSENT_EXPIRED", "上下文已过期，请重新预览并授权");
    }
}
