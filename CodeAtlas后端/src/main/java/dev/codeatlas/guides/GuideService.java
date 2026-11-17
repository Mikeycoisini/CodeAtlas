package dev.codeatlas.guides;

import dev.codeatlas.learning.LearningService;
import dev.codeatlas.learning.MutationService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static dev.codeatlas.review.ReviewDefinitions.*;

@Service
public class GuideService {
    public static final List<String> STEP_IDS = List.of("BUSINESS", "SOURCE", "PRINCIPLE", "CODE", "VERIFY", "REFLECT");
    private static final List<String> TITLES = List.of("理解业务", "分析源码", "学习原理", "编码实践", "核对验证", "回忆复盘");
    private final AtlasStore store;
    private final SourceEvidenceService json;
    private final MutationService mutations;
    private final Clock clock;
    public GuideService(AtlasStore store, SourceEvidenceService json, MutationService mutations, Clock clock) { this.store = store; this.json = json; this.mutations = mutations; this.clock = clock; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private Map<String, Object> raw(String id, boolean lock) { return store.one("SELECT * FROM guide_runs WHERE id=?" + (lock ? " FOR UPDATE" : ""), id); }
    public Map<String, Object> get(String id) { return view(raw(id, false)); }
    private Map<String, Object> view(Map<String, Object> row) {
        var result = json.decode((String) row.get("payload"));
        for (String key : List.of("id", "revision", "status", "createdAtMs", "updatedAtMs")) result.put(key, row.get(key));
        var steps = (List<?>) result.get("steps");
        result.put("doneCount", steps.stream().map(GuideService::object).filter(step -> step.get("status").equals("DONE")).count());
        result.put("skippedCount", steps.stream().map(GuideService::object).filter(step -> step.get("status").equals("SKIPPED")).count()); return result;
    }
    private List<Map<String, Object>> defaults() {
        var steps = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < STEP_IDS.size(); index++) steps.add(Map.of("id", STEP_IDS.get(index), "title", TITLES.get(index), "status", "TODO", "notes", "", "advice", "", "reason", ""));
        return steps;
    }
    private List<Map<String, Object>> steps(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 6) throw new ApiException(400, "INVALID_STEPS", "引导须保留六个步骤，可自由调整顺序与状态");
        var ids = new HashSet<String>(); var result = new ArrayList<Map<String, Object>>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?>)) throw new ApiException(400, "INVALID_STEPS", "步骤格式无效");
            var step = object(raw); fields(step, "id", "title", "status", "notes", "advice", "reason");
            String id = text(step, "id", 20, true); String status = text(step, "status", 20, true);
            if (!STEP_IDS.contains(id) || !ids.add(id) || !Set.of("TODO", "DONE", "SKIPPED").contains(status)) throw new ApiException(400, "INVALID_STEPS", "步骤身份或状态无效");
            result.add(Map.of("id", id, "title", text(step, "title", 200, true), "status", status, "notes", text(step, "notes", 4000, false), "advice", text(step, "advice", 4000, false), "reason", text(step, "reason", 2000, false)));
        }
        return result;
    }
    private Map<String, Object> normalize(Map<String, Object> input, Map<String, Object> old) {
        fields(input, "title", "goal", "steps", "sourceIds", "knowledgeIds", "revision", "idempotencyKey");
        var result = new LinkedHashMap<String, Object>(); result.put("title", text(input, "title", 200, true)); result.put("goal", text(input, "goal", 4000, true));
        var sourceIds = LearningService.strings(input, "sourceIds", 6, 36); var knowledgeIds = LearningService.strings(input, "knowledgeIds", 10, 36);
        var sources = new ArrayList<Map<String, Object>>(); var knowledge = new ArrayList<Map<String, Object>>();
        for (String id : sourceIds) {
            var previous = old == null ? Optional.<Map<String, Object>>empty() : ((List<?>) old.get("sources")).stream().map(GuideService::object).filter(item -> id.equals(item.get("id"))).findFirst();
            if (previous.isPresent()) sources.add(previous.get());
            else { var source = json.get(id); source.remove("content"); source.remove("snapshotOutdated"); source.remove("currentSourceStatus"); sources.add(source); }
        }
        for (String id : knowledgeIds) {
            var previous = old == null ? Optional.<Map<String, Object>>empty() : ((List<?>) old.get("knowledge")).stream().map(GuideService::object).filter(item -> id.equals(item.get("id"))).findFirst();
            if (previous.isPresent()) knowledge.add(previous.get());
            else knowledge.add(store.one("SELECT id,title,revision FROM learning_records WHERE id=? AND kind='KNOWLEDGE' AND deleted_at IS NULL", id));
        }
        result.put("sourceIds", sourceIds); result.put("knowledgeIds", knowledgeIds); result.put("sources", sources); result.put("knowledge", knowledge);
        result.put("steps", input.containsKey("steps") ? steps(input.get("steps")) : old == null ? defaults() : old.get("steps"));
        if (old != null) for (String field : List.of("suggestionGenerationId", "suggestionSummary")) if (old.containsKey(field)) result.put(field, old.get(field));
        return result;
    }
    public Map<String, Object> create(Map<String, Object> input) {
        fields(input, "title", "goal", "sourceIds", "knowledgeIds", "idempotencyKey");
        var body = new LinkedHashMap<>(input); body.remove("idempotencyKey");
        return mutations.once(text(input, "idempotencyKey", 100, true), "GUIDE_CREATE", body, () -> {
            var normalized = normalize(input, null); String id = AtlasStore.uuid(); long now = clock.millis();
            store.update("INSERT INTO guide_runs(id,title,status,revision,payload,created_at_ms,updated_at_ms) VALUES(?,?,'ACTIVE',1,?,?,?)", id, normalized.get("title"), store.encode(normalized), now, now);
            var guide = get(id); event(id, 1, "CREATE", Map.of(), guide); return guide;
        });
    }
    public Map<String, Object> update(String id, Map<String, Object> input) {
        fields(input, "title", "goal", "steps", "sourceIds", "knowledgeIds", "revision");
        return store.transaction(() -> {
            var row = raw(id, true); revision(row, input);
            if (row.get("status").equals("COMPLETED")) throw new ApiException(409, "GUIDE_COMPLETED", "请先重新打开已结束的引导");
            var before = view(row); var normalized = normalize(input, before);
            return write(row, normalized, (String) row.get("status"), "EDIT", before);
        });
    }
    private Map<String, Object> write(Map<String, Object> row, Map<String, Object> payload, String status, String kind, Map<String, Object> before) {
        int next = ((Number) row.get("revision")).intValue() + 1; String id = (String) row.get("id");
        store.update("UPDATE guide_runs SET title=?,status=?,revision=?,payload=?,updated_at_ms=? WHERE id=?", payload.get("title"), status, next, store.encode(payload), clock.millis(), id);
        var after = get(id); event(id, next, kind, before, after); return after;
    }
    public Map<String, Object> action(String id, Map<String, Object> input) {
        fields(input, "revision", "action", "idempotencyKey"); String action = text(input, "action", 20, true);
        var body = new LinkedHashMap<>(input); body.remove("idempotencyKey"); body.put("id", id);
        return mutations.once(text(input, "idempotencyKey", 100, true), "GUIDE_ACTION", body, () -> {
            var row = raw(id, true); revision(row, input); var before = view(row); String current = (String) row.get("status");
            String next = switch (action) {
                case "PAUSE" -> "PAUSED";
                case "EXIT" -> "EXITED";
                case "RESUME", "REOPEN" -> "ACTIVE";
                case "COMPLETE" -> "COMPLETED";
                default -> throw new ApiException(400, "INVALID_ACTION", "引导操作无效");
            };
            boolean valid = switch (action) {
                case "PAUSE", "COMPLETE" -> current.equals("ACTIVE");
                case "EXIT" -> Set.of("ACTIVE", "PAUSED").contains(current);
                case "RESUME" -> Set.of("PAUSED", "EXITED").contains(current);
                case "REOPEN" -> current.equals("COMPLETED");
                default -> false;
            };
            if (!valid) throw new ApiException(409, "GUIDE_STATE_CONFLICT", "当前引导状态不支持该操作");
            if (action.equals("COMPLETE") && ((Number) before.get("doneCount")).longValue() + ((Number) before.get("skippedCount")).longValue() != 6) throw new ApiException(409, "GUIDE_STEPS_PENDING", "请先将步骤标记完成或跳过");
            return write(row, json.decode((String) row.get("payload")), next, action, before);
        });
    }
    public Map<String, Object> applySuggestion(String id, Map<String, Object> input) {
        fields(input, "revision", "generationId", "idempotencyKey"); String generationId = text(input, "generationId", 36, true);
        var body = new LinkedHashMap<>(input); body.remove("idempotencyKey"); body.put("id", id);
        return mutations.once(text(input, "idempotencyKey", 100, true), "GUIDE_APPLY_AI", body, () -> {
            var row = raw(id, true); revision(row, input);
            if (row.get("status").equals("COMPLETED")) throw new ApiException(409, "GUIDE_COMPLETED", "请先重新打开引导");
            var linked = store.one("SELECT g.result_json,c.context_json,r.guide_id,r.guide_revision FROM ai_generations g JOIN ai_contexts c ON c.id=g.context_id JOIN guide_ai_requests r ON r.context_id=c.id WHERE g.id=? AND g.status='SUCCEEDED'", generationId);
            var context = json.decode((String) linked.get("contextJson"));
            if (!id.equals(linked.get("guideId")) || !"LEARNING_GUIDE".equals(context.get("operation"))) throw new ApiException(400, "GENERATION_TYPE_MISMATCH", "建议不属于当前引导");
            if (!row.get("revision").equals(linked.get("guideRevision"))) throw new ApiException(409, "GUIDE_SUGGESTION_STALE", "引导已编辑，请保留当前内容并重新准备建议");
            var before = view(row); var payload = json.decode((String) row.get("payload"));
            var result = json.decode((String) linked.get("resultJson")); var steps = new ArrayList<Map<String, Object>>();
            for (Object raw : (List<?>) result.get("steps")) {
                var advice = object(raw); var existing = ((List<?>) payload.get("steps")).stream().map(GuideService::object).filter(step -> step.get("id").equals(advice.get("stepId"))).findFirst().orElseThrow(ApiException::missing);
                var changed = new LinkedHashMap<>(existing); changed.put("reason", advice.get("reason")); changed.put("advice", advice.get("actionAdvice")); steps.add(changed);
            }
            payload.put("steps", steps); payload.put("suggestionGenerationId", generationId); payload.put("suggestionSummary", result.get("summary"));
            return write(row, payload, (String) row.get("status"), "AI_APPLY", before);
        });
    }
    private void event(String id, int revision, String kind, Map<String, Object> before, Map<String, Object> after) {
        store.update("INSERT INTO guide_events(id,guide_id,revision,kind,payload,created_at_ms) VALUES(?,?,?,?,?,?)", AtlasStore.uuid(), id, revision, kind, store.encode(Map.of("before", before, "after", after)), clock.millis());
    }
    public Map<String, Object> list(String query, String status, int limit, int offset) {
        page(limit, offset); if (!status.isEmpty() && !Set.of("ACTIVE", "PAUSED", "EXITED", "COMPLETED").contains(status)) throw new ApiException(400, "INVALID_STATE", "引导状态无效");
        String term = query.toLowerCase(Locale.ROOT);
        var rows = store.rows("SELECT * FROM guide_runs ORDER BY updated_at_ms DESC,id").stream().filter(row -> (status.isEmpty() || status.equals(row.get("status"))) && (row.get("title") + " " + row.get("payload")).toLowerCase(Locale.ROOT).contains(term)).toList();
        return Map.of("items", rows.stream().skip(offset).limit(limit).map(this::view).toList(), "total", rows.size(), "limit", limit, "offset", offset);
    }
    public Map<String, Object> history(String id, int limit, int offset) {
        page(limit, offset); raw(id, false);
        var rows = store.rows("SELECT * FROM guide_events WHERE guide_id=? ORDER BY revision DESC", id);
        var items = rows.stream().skip(offset).limit(limit).map(row -> { var result = new LinkedHashMap<>(row); result.put("payload", json.decode((String) row.get("payload"))); return result; }).toList();
        return Map.of("items", items, "total", rows.size(), "limit", limit, "offset", offset);
    }
}
