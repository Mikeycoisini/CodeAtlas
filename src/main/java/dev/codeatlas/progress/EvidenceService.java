package dev.codeatlas.progress;

import dev.codeatlas.learning.MutationService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static dev.codeatlas.review.ReviewDefinitions.*;

@Service
public class EvidenceService {
    private final AtlasStore store;
    private final SourceEvidenceService json;
    private final MutationService mutations;
    private final Clock clock;
    public EvidenceService(AtlasStore store, SourceEvidenceService json, MutationService mutations, Clock clock) { this.store = store; this.json = json; this.mutations = mutations; this.clock = clock; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private Map<String, Object> knowledgeRow(String id, boolean lock) {
        return store.one("SELECT * FROM learning_records WHERE id=? AND kind='KNOWLEDGE'" + (lock ? " FOR UPDATE" : ""), id);
    }
    private Map<String, Object> decodeRow(Map<String, Object> row) { var result = new LinkedHashMap<>(row); result.put("payload", json.decode((String) row.get("payload"))); return result; }
    private void activeKnowledge(Map<String, Object> row) { if (row.get("deletedAt") != null) throw new ApiException(409, "KNOWLEDGE_DELETED", "知识已删除，历史证据保留"); }
    private String key(Map<String, Object> input) { return text(input, "idempotencyKey", 100, true); }
    private Map<String, Object> body(Map<String, Object> input) { var result = new LinkedHashMap<>(input); result.remove("idempotencyKey"); return result; }

    /** Caller holds the review transaction; frozen knowledge identity belongs to the original attempt. */
    public void recordReview(Map<String, Object> event) {
        var payload = object(event.get("payload")); var card = object(payload.get("card"));
        if (!(card.get("knowledgeId") instanceof String id) || id.isBlank()) return;
        var knowledge = knowledgeRow(id, true); var fact = reviewFact(event);
        fact.put("association", "CARD_AT_REVIEW_START"); fact.put("knowledgeSnapshot", card.get("knowledgeSnapshot"));
        saveFact(knowledge, ((Number) card.get("knowledgeRevision")).intValue(), "REVIEW_RATING", (String) event.get("id"), ((Number) event.get("occurredAtMs")).longValue(), fact);
    }
    private Map<String, Object> reviewFact(Map<String, Object> event) {
        if (!"REVIEW".equals(event.get("kind"))) throw new ApiException(400, "INVALID_EVIDENCE", "请选择已完成的评分记录");
        var payload = object(event.get("payload")); String rating = (String) payload.get("rating");
        var fact = new LinkedHashMap<String, Object>(); fact.put("source", "USER_SELF_REPORTED"); fact.put("rating", rating);
        fact.put("eligible", true); fact.put("strength", Set.of("AGAIN", "HARD").contains(rating) ? -1 : 1); fact.put("qualifier", "RECALL");
        fact.put("reviewEventId", event.get("id")); fact.put("card", payload.get("card")); fact.put("recalledAnswer", payload.get("recalledAnswer")); fact.put("reflection", payload.get("reflection")); return fact;
    }
    public Map<String, Object> associate(Map<String, Object> input) {
        fields(input, "knowledgeId", "sourceType", "sourceId", "questionId", "rating", "reflection", "idempotencyKey");
        String id = text(input, "knowledgeId", 36, true); String type = text(input, "sourceType", 40, true);
        if (!Set.of("REVIEW_RATING", "TRAINING_RESULT", "TRAINING_SELF_ASSESSMENT", "PERSONAL_REFLECTION").contains(type)) throw new ApiException(400, "INVALID_EVIDENCE", "学习证据类型无效");
        return mutations.once(key(input), "EVIDENCE_ASSOCIATE", body(input), () -> {
            var knowledge = knowledgeRow(id, true); activeKnowledge(knowledge); long occurred = clock.millis();
            Map<String, Object> fact; String sourceKey;
            if (type.equals("PERSONAL_REFLECTION")) {
                fields(input, "knowledgeId", "sourceType", "rating", "reflection", "idempotencyKey");
                String rating = text(input, "rating", 30, true); validateSelfRating(rating);
                fact = selfFact(rating, text(input, "reflection", 4000, false)); sourceKey = AtlasStore.uuid();
            } else {
                fields(input, "knowledgeId", "sourceType", "sourceId", "questionId", "idempotencyKey");
                sourceKey = text(input, "sourceId", 36, true);
                if (type.equals("REVIEW_RATING")) {
                    var event = decodeRow(store.one("SELECT * FROM review_events WHERE id=?", sourceKey));
                    fact = reviewFact(event); occurred = ((Number) event.get("occurredAtMs")).longValue();
                } else if (type.equals("TRAINING_SELF_ASSESSMENT")) {
                    var event = json.decode((String) store.one("SELECT payload FROM training_self_assessments WHERE id=?", sourceKey).get("payload"));
                    fact = selfFact((String) event.get("rating"), (String) event.get("reflection"));
                    fact.put("attemptId", event.get("attemptId")); fact.put("questionId", event.get("questionId"));
                    occurred = Instant.parse((String) event.get("createdAt")).toEpochMilli();
                } else {
                    String questionId = text(input, "questionId", 36, true); var attempt = store.one("SELECT * FROM training_attempts WHERE id=?", sourceKey);
                    if (!"SUBMITTED".equals(attempt.get("status"))) throw new ApiException(409, "ATTEMPT_NOT_SUBMITTED", "请选择已提交的理解题回答");
                    fact = trainingFact(attempt, questionId); sourceKey += ":" + questionId;
                    occurred = Instant.parse((String) attempt.get("submittedAt")).toEpochMilli();
                }
            }
            fact.put("association", "USER_ASSOCIATED"); fact.put("knowledgeSnapshot", json.decode((String) knowledge.get("payload")));
            return saveFact(knowledge, ((Number) knowledge.get("revision")).intValue(), type, sourceKey, occurred, fact);
        });
    }
    private void validateSelfRating(String rating) { if (!Set.of("UNDERSTOOD", "PARTIAL", "NEEDS_REVIEW").contains(rating)) throw new ApiException(400, "INVALID_RATING", "个人记录评分无效"); }
    private Map<String, Object> selfFact(String rating, String reflection) {
        validateSelfRating(rating); var fact = new LinkedHashMap<String, Object>(); fact.put("source", "USER_SELF_REPORTED");
        fact.put("rating", rating); fact.put("reflection", reflection); fact.put("eligible", true); fact.put("strength", rating.equals("UNDERSTOOD") ? 1 : -1); fact.put("qualifier", "NONE"); return fact;
    }
    private List<Map<String, Object>> items(String payload) { return ((List<?>) json.decode(payload).get("items")).stream().map(EvidenceService::object).toList(); }
    private Map<String, Object> trainingFact(Map<String, Object> attempt, String questionId) {
        var result = items((String) attempt.get("resultsPayload")).stream().filter(row -> questionId.equals(row.get("questionId"))).findFirst().orElseThrow(ApiException::missing);
        var answer = items((String) attempt.get("answersPayload")).stream().filter(row -> questionId.equals(row.get("questionId"))).findFirst().orElseThrow(ApiException::missing);
        var frozen = json.decode((String) store.one("SELECT payload FROM training_task_revisions WHERE task_id=? AND revision=?", attempt.get("taskId"), attempt.get("taskRevision")).get("payload"));
        var question = ((List<?>) frozen.get("questions")).stream().map(EvidenceService::object).filter(row -> questionId.equals(row.get("id"))).findFirst().orElseThrow(ApiException::missing);
        String verdict = (String) result.get("verdict"); boolean eligible = "RULE_BASED".equals(result.get("source")) && Set.of("CORRECT", "INCORRECT").contains(verdict);
        boolean assisted = Boolean.TRUE.equals(result.get("hintViewed")) || Boolean.TRUE.equals(result.get("referenceViewed"));
        var fact = new LinkedHashMap<String, Object>(); fact.put("source", result.get("source")); fact.put("verdict", verdict); fact.put("eligible", eligible);
        fact.put("strength", eligible ? verdict.equals("INCORRECT") ? -1 : 1 : 0); fact.put("qualifier", eligible && !assisted ? "TRAINING" : "NONE");
        fact.put("assisted", assisted); fact.put("attemptId", attempt.get("id")); fact.put("questionId", questionId); fact.put("question", question);
        fact.put("answer", answer); fact.put("resultAtSubmission", result); fact.put("taskId", attempt.get("taskId")); fact.put("taskRevision", attempt.get("taskRevision"));
        fact.put("submissionDigest", attempt.get("submissionDigest")); fact.put("sources", frozen.get("sources")); return fact;
    }
    private Map<String, Object> saveFact(Map<String, Object> knowledge, int revision, String type, String sourceKey, long occurred, Map<String, Object> fact) {
        var previous = store.rows("SELECT * FROM learning_evidence WHERE knowledge_id=? AND source_type=? AND source_key=? FOR UPDATE", knowledge.get("id"), type, sourceKey);
        if (!previous.isEmpty()) return decodeRow(previous.getFirst());
        String id = AtlasStore.uuid(); long now = clock.millis();
        // The knowledge row is locked by callers. Projection revision advances in the
        // same transaction as each fact, so it provides a durable per-knowledge write order.
        var progress = store.rows("SELECT revision FROM knowledge_progress WHERE knowledge_id=? FOR UPDATE", knowledge.get("id"));
        long recordOrder = progress.isEmpty() ? 1 : ((Number) progress.getFirst().get("revision")).longValue() + 1;
        fact.put("knowledgeTitle", knowledge.get("title"));
        store.update("INSERT INTO learning_evidence(id,knowledge_id,knowledge_revision,source_type,source_key,occurred_at_ms,created_at_ms,record_order,payload) VALUES(?,?,?,?,?,?,?,?,?)", id, knowledge.get("id"), revision, type, sourceKey, occurred, now, recordOrder, store.encode(fact));
        recompute(knowledge); return decodeRow(store.one("SELECT * FROM learning_evidence WHERE id=?", id));
    }
    public Map<String, Object> retract(String id, Map<String, Object> input) {
        fields(input, "reason", "idempotencyKey"); String reason = text(input, "reason", 2000, true);
        return mutations.once(key(input), "EVIDENCE_RETRACT", Map.of("id", id, "reason", reason), () -> {
            var origin = store.one("SELECT knowledge_id FROM learning_evidence WHERE id=?", id);
            var knowledge = knowledgeRow((String) origin.get("knowledgeId"), true);
            var previous = store.rows("SELECT * FROM evidence_retractions WHERE evidence_id=? FOR UPDATE", id);
            if (!previous.isEmpty()) return previous.getFirst();
            String eventId = AtlasStore.uuid(); store.update("INSERT INTO evidence_retractions(id,evidence_id,reason,created_at_ms) VALUES(?,?,?,?)", eventId, id, reason, clock.millis());
            recompute(knowledge); return store.one("SELECT * FROM evidence_retractions WHERE id=?", eventId);
        });
    }
    private List<Map<String, Object>> facts(String id, boolean lock) {
        var rows = store.rows("SELECT * FROM learning_evidence WHERE knowledge_id=? ORDER BY occurred_at_ms,created_at_ms,record_order,id" + (lock ? " FOR UPDATE" : ""), id);
        var retractions = store.rows("SELECT r.evidence_id FROM evidence_retractions r JOIN learning_evidence e ON e.id=r.evidence_id WHERE e.knowledge_id=?" + (lock ? " FOR UPDATE" : ""), id);
        var removed = retractions.stream().map(row -> row.get("evidenceId")).toList();
        return rows.stream().map(this::decodeRow).peek(row -> row.put("retracted", removed.contains(row.get("id")))).toList();
    }
    private Map<String, Object> assessment(Map<String, Object> knowledge, List<Map<String, Object>> facts) {
        int revision = ((Number) knowledge.get("revision")).intValue();
        var active = facts.stream().filter(row -> ((Number) row.get("knowledgeRevision")).intValue() == revision && !Boolean.TRUE.equals(row.get("retracted")) && Boolean.TRUE.equals(object(row.get("payload")).get("eligible"))).toList();
        long recalls = active.stream().filter(row -> "RECALL".equals(object(row.get("payload")).get("qualifier")) && ((Number) object(row.get("payload")).get("strength")).intValue() > 0).count();
        long attempts = active.stream().map(row -> object(row.get("payload"))).filter(row -> "TRAINING".equals(row.get("qualifier")) && ((Number) row.get("strength")).intValue() > 0).map(row -> row.get("attemptId")).distinct().count();
        String status = "NO_EVIDENCE"; String reason = "当前知识版本暂无有效学习证据";
        if (!active.isEmpty()) {
            var latest = object(active.getLast().get("payload"));
            if (((Number) latest.get("strength")).intValue() < 0) { status = "NEEDS_REVIEW"; reason = "最新有效记录提示回忆困难、回答错误或需要复盘"; }
            else if (recalls >= 2 || attempts >= 2) { status = "SUPPORTED"; reason = "至少两次自报记住/简单复习，或两个独立尝试的无帮助正确回答"; }
            else { status = "PRACTICING"; reason = "已有学习记录，继续结合实际源码与多次练习核对"; }
        }
        var result = new LinkedHashMap<String, Object>(); result.put("status", status); result.put("reason", reason); result.put("ruleVersion", "EVIDENCE_V1");
        result.put("knowledgeRevision", revision); result.put("positiveRecalls", recalls); result.put("unassistedAttempts", attempts); result.put("activeEvidenceCount", active.size());
        result.put("basisIds", active.stream().map(row -> row.get("id")).toList()); return result;
    }
    private void recompute(Map<String, Object> knowledge) {
        var facts = facts((String) knowledge.get("id"), true); var summary = assessment(knowledge, facts);
        String id = AtlasStore.uuid(); String digest = SnapshotScanner.hash(store.encode(summary));
        store.update("INSERT INTO mastery_assessments(id,knowledge_id,knowledge_revision,rule_version,basis_digest,payload,created_at_ms) VALUES(?,?,?,?,?,?,?)", id, knowledge.get("id"), knowledge.get("revision"), "EVIDENCE_V1", digest, store.encode(summary), clock.millis());
        var previous = store.rows("SELECT revision FROM knowledge_progress WHERE knowledge_id=? FOR UPDATE", knowledge.get("id"));
        if (previous.isEmpty()) store.update("INSERT INTO knowledge_progress(knowledge_id,assessment_id,revision) VALUES(?,?,1)", knowledge.get("id"), id);
        else store.update("UPDATE knowledge_progress SET assessment_id=?,revision=revision+1 WHERE knowledge_id=?", id, knowledge.get("id"));
    }
    public Map<String, Object> knowledge(String id) {
        return store.transaction(() -> {
            var row = knowledgeRow(id, false); var facts = facts(id, false); var result = new LinkedHashMap<String, Object>();
            result.put("id", id); result.put("title", row.get("title")); result.put("revision", row.get("revision")); result.put("deleted", row.get("deletedAt") != null);
            var projections = store.rows("SELECT p.revision AS progress_revision,a.knowledge_revision,a.payload FROM knowledge_progress p JOIN mastery_assessments a ON a.id=p.assessment_id WHERE p.knowledge_id=?", id);
            var summary = projections.isEmpty() || !projections.getFirst().get("knowledgeRevision").equals(row.get("revision")) ? assessment(row, facts) : json.decode((String) projections.getFirst().get("payload"));
            result.put("assessment", summary); result.put("evidence", facts);
            result.put("progressRevision", projections.isEmpty() ? 0 : projections.getFirst().get("progressRevision"));
            result.put("historicalEvidenceCount", facts.stream().filter(fact -> !fact.get("knowledgeRevision").equals(row.get("revision"))).count()); return result;
        });
    }
    public Map<String, Object> list(String query, String status, int limit, int offset) {
        page(limit, offset); String term = query.toLowerCase(Locale.ROOT);
        var all = store.rows("SELECT id,title FROM learning_records WHERE kind='KNOWLEDGE' AND deleted_at IS NULL ORDER BY updated_at DESC,id").stream()
                .filter(row -> row.get("title").toString().toLowerCase(Locale.ROOT).contains(term)).map(row -> knowledge((String) row.get("id")))
                .filter(row -> status.isEmpty() || status.equals(object(row.get("assessment")).get("status"))).toList();
        return Map.of("items", all.stream().skip(offset).limit(limit).toList(), "total", all.size(), "limit", limit, "offset", offset);
    }
    public Map<String, Object> history(String id, int limit, int offset) {
        page(limit, offset); knowledgeRow(id, false);
        var rows = store.rows("SELECT * FROM mastery_assessments WHERE knowledge_id=? ORDER BY created_at_ms DESC,id", id);
        return Map.of("items", rows.stream().skip(offset).limit(limit).map(this::decodeRow).toList(), "total", rows.size(), "limit", limit, "offset", offset);
    }
    public Map<String, Object> mistakes(int limit, int offset) {
        page(limit, offset); var rows = store.rows("SELECT * FROM training_attempts WHERE status='SUBMITTED' ORDER BY submitted_at DESC,id");
        var all = new ArrayList<Map<String, Object>>();
        for (var attempt : rows) for (var result : items((String) attempt.get("resultsPayload"))) {
            if (!Set.of("INCORRECT", "NEEDS_REVIEW").contains(result.get("verdict"))) continue;
            var item = trainingFact(attempt, (String) result.get("questionId")); item.put("id", attempt.get("id") + ":" + result.get("questionId")); all.add(item);
        }
        return Map.of("items", all.stream().skip(offset).limit(limit).toList(), "total", all.size(), "limit", limit, "offset", offset);
    }
}
