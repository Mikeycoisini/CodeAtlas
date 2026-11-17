package dev.codeatlas.relations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codeatlas.learning.MutationService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/**
 * Append-only user corrections over immutable analyzer relations.
 *
 * <p>The original relation row and its evidence status are never rewritten. A correction records
 * the user verdict, optional replacement kind/target, reason, source and time. Later corrections
 * supersede earlier ones for display while the full history remains queryable.</p>
 */
@Service
public class RelationCorrectionService {
    public static final Set<String> VERDICTS = Set.of("CORRECTED", "REJECTED", "UNCERTAIN");
    public static final Set<String> KINDS = Set.of("CALL", "RUNTIME_CALL", "EXTENDS", "IMPLEMENTS", "TRANSACTION_BOUNDARY");
    private final AtlasStore store;
    private final SourceEvidenceService json;
    private final MutationService mutations;
    private final Clock clock;
    private final ObjectMapper mapper;

    public RelationCorrectionService(AtlasStore store, SourceEvidenceService json, MutationService mutations,
                                     Clock clock, ObjectMapper mapper) {
        this.store = store; this.json = json; this.mutations = mutations; this.clock = clock; this.mapper = mapper;
    }

    public Map<String, Object> create(String snapshot, String relationId, Map<String, Object> input) {
        fields(input, "generationId", "verdict", "correctedKind", "correctedTargetText", "reason", "source", "idempotencyKey");
        String generation = generation(snapshot, text(input, "generationId", 36, true));
        String verdict = text(input, "verdict", 20, true);
        if (!VERDICTS.contains(verdict)) throw invalid("修正结论无效");
        String correctedKind = text(input, "correctedKind", 40, false);
        if (!correctedKind.isEmpty() && !KINDS.contains(correctedKind)) throw invalid("修正后的关系类别无效");
        String correctedTarget = text(input, "correctedTargetText", 1000, false);
        String reason = text(input, "reason", 2000, true);
        String source = text(input, "source", 1000, false);
        String key = text(input, "idempotencyKey", 100, true);
        var relation = relation(generation, relationId);
        if (verdict.equals("CORRECTED")) {
            if (correctedKind.isEmpty() && correctedTarget.isEmpty()) throw invalid("请至少修改关系类别或目标");
            boolean kindSame = correctedKind.isEmpty() || correctedKind.equals(relation.get("kind"));
            boolean targetSame = correctedTarget.isEmpty() || correctedTarget.equals(relation.get("targetText"));
            if (kindSame && targetSame) throw invalid("修正内容与原关系一致");
        } else if (!correctedKind.isEmpty() || !correctedTarget.isEmpty()) {
            throw invalid("不成立或存疑的修正不能修改关系内容");
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("snapshotId", snapshot); body.put("relationId", relationId); body.put("generationId", generation);
        body.put("verdict", verdict); body.put("correctedKind", correctedKind.isEmpty() ? null : correctedKind);
        body.put("correctedTargetText", correctedTarget.isEmpty() ? null : correctedTarget);
        body.put("reason", reason); body.put("source", source.isEmpty() ? null : source);
        return mutations.once(key, "RELATION_CORRECTION", body, () -> {
            relation(generation, relationId);
            String id = AtlasStore.uuid(); String now = clock.instant().toString();
            store.update("INSERT INTO relation_corrections(id,snapshot_id,generation_id,relation_id,verdict,corrected_kind,corrected_target_text,reason,source,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, snapshot, generation, relationId, verdict, body.get("correctedKind"), body.get("correctedTargetText"),
                    reason, body.get("source"), now);
            return store.one("SELECT * FROM relation_corrections WHERE id=?", id);
        });
    }

    public List<Map<String, Object>> list(String snapshot, String relationId, String generationId) {
        String generation = generation(snapshot, generationId);
        relation(generation, relationId);
        return store.rows("SELECT * FROM relation_corrections WHERE generation_id=? AND relation_id=? ORDER BY created_at,id", generation, relationId);
    }

    public Map<String, List<Map<String, Object>>> grouped(String generation, Collection<String> relationIds) {
        if (generation == null || generation.isBlank() || relationIds.isEmpty()) return Map.of();
        var selected = new HashSet<>(relationIds);
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var row : store.rows("SELECT * FROM relation_corrections WHERE generation_id=? ORDER BY created_at,id", generation)) {
            String relationId = (String) row.get("relationId");
            if (selected.contains(relationId)) result.computeIfAbsent(relationId, key -> new ArrayList<>()).add(row);
        }
        return result;
    }

    /** Adds correction history and effective display fields to mutable relation JSON nodes. */
    public void enrich(List<?> items, String generation) {
        var ids = items.stream().filter(JsonNode.class::isInstance).map(item -> ((JsonNode) item).path("id").asText())
                .filter(id -> !id.isBlank()).toList();
        var grouped = grouped(generation, ids);
        for (Object item : items) {
            if (!(item instanceof ObjectNode node)) continue;
            decorate(node, grouped.getOrDefault(node.path("id").asText(), List.of()));
        }
    }

    public void decorate(ObjectNode node, List<Map<String, Object>> corrections) {
        node.set("corrections", mapper.valueToTree(corrections));
        if (corrections.isEmpty()) {
            node.putNull("latestCorrection"); node.putNull("effectiveVerdict");
            return;
        }
        var latest = corrections.getLast();
        node.set("latestCorrection", mapper.valueToTree(latest));
        node.put("effectiveVerdict", String.valueOf(latest.get("verdict")));
        String kind = node.path("kind").asText();
        String target = node.path("targetText").asText();
        if ("CORRECTED".equals(latest.get("verdict"))) {
            if (latest.get("correctedKind") instanceof String value && !value.isBlank()) kind = value;
            if (latest.get("correctedTargetText") instanceof String value && !value.isBlank()) target = value;
        }
        node.put("effectiveKind", kind); node.put("effectiveTargetText", target);
    }

    public Map<String, Object> summary(List<Map<String, Object>> corrections) {
        var result = new LinkedHashMap<String, Object>();
        result.put("corrections", corrections);
        result.put("latestCorrection", corrections.isEmpty() ? null : corrections.getLast());
        result.put("effectiveVerdict", corrections.isEmpty() ? null : corrections.getLast().get("verdict"));
        return result;
    }

    private String generation(String snapshot, String generationId) {
        if (generationId == null || generationId.isBlank()) throw invalid("请指定已发布索引代次");
        store.generation(snapshot, generationId);
        return generationId;
    }

    private Map<String, Object> relation(String generation, String relationId) {
        if (relationId == null || !relationId.matches("[0-9a-f-]{36}")) throw ApiException.missing();
        var row = store.one("SELECT id,payload FROM relations WHERE id=? AND generation_id=?", relationId, generation);
        var payload = new LinkedHashMap<>(json.decode((String) row.get("payload")));
        payload.put("id", row.get("id"));
        return payload;
    }

    private static void fields(Map<String, Object> input, String... allowed) {
        if (!Set.of(allowed).containsAll(input.keySet())) throw invalid("请求包含不支持的字段");
    }

    private static String text(Map<String, Object> input, String name, int maximum, boolean required) {
        Object raw = input.get(name);
        if (raw != null && !(raw instanceof String)) throw invalid(name + " 字段类型无效");
        String value = raw == null ? "" : ((String) raw).trim();
        if ((required && value.isBlank()) || value.length() > maximum) throw invalid(name + " 长度无效");
        return value;
    }

    private static ApiException invalid(String message) {
        return new ApiException(400, "INVALID_RELATION_CORRECTION", message);
    }
}
