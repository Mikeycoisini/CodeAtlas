package dev.codeatlas.review;

import dev.codeatlas.learning.MutationService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.progress.EvidenceService;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static dev.codeatlas.review.ReviewDefinitions.*;

@Service
public class ReviewService {
    private final AtlasStore store;
    private final SourceEvidenceService json;
    private final ReviewDefinitions definitions;
    private final FsrsScheduler scheduler;
    private final MutationService mutations;
    private final Clock clock;
    private final EvidenceService evidence;
    public ReviewService(AtlasStore store, SourceEvidenceService json, ReviewDefinitions definitions, FsrsScheduler scheduler, MutationService mutations, Clock clock, EvidenceService evidence) {
        this.store = store; this.json = json; this.definitions = definitions; this.scheduler = scheduler; this.mutations = mutations; this.clock = clock; this.evidence = evidence;
    }
    private Map<String, Object> command(String id, String operation, Map<String, Object> input, Supplier<Map<String, Object>> work) {
        var normalized = new LinkedHashMap<>(input); normalized.remove("idempotencyKey");
        return mutations.once(text(input, "idempotencyKey", 100, true), operation, Map.of("id", id, "input", normalized), work);
    }
    private Map<String, Object> rawState(String id) { return store.one("SELECT * FROM review_states WHERE card_id=? FOR UPDATE", id); }
    private void available(Map<String, Object> card) { if (card.get("deletedAt") != null) throw new ApiException(409, "CARD_DELETED", "卡片已删除，历史仍可读取"); }
    private void current(Map<String, Object> card, Map<String, Object> state) {
        available(card);
        if (Boolean.TRUE.equals(state.get("suspended"))) throw new ApiException(409, "REVIEW_SUSPENDED", "此卡片已暂停复习");
        var frozen = definitions.freeze(card, false);
        if (!frozen.get("contentDigest").equals(state.get("contentDigest"))) throw new ApiException(409, "CARD_CONTENT_CHANGED", "卡片内容已更新，请确认采用新版并重新学习");
    }
    public Map<String, Object> state(String cardId) {
        var card = definitions.card(cardId, false); var row = rawState(cardId); var frozen = definitions.freeze(card);
        var result = new LinkedHashMap<>(row); result.put("id", cardId); result.put("card", definitions.view(frozen, false));
        result.put("status", card.get("deletedAt") != null ? "DELETED" : !frozen.get("contentDigest").equals(row.get("contentDigest")) ? "CONTENT_CHANGED" : Boolean.TRUE.equals(row.get("suspended")) ? "PAUSED" : "ACTIVE");
        result.put("state", json.decode((String) result.remove("stateJson"))); return result;
    }
    public Map<String, Object> enroll(String cardId, Map<String, Object> input) {
        fields(input, "idempotencyKey");
        return command(cardId, "REVIEW_ENROLL", input, () -> {
            var card = definitions.card(cardId, true); available(card);
            if (!store.rows("SELECT card_id FROM review_states WHERE card_id=? FOR UPDATE", cardId).isEmpty()) return state(cardId);
            var frozen = definitions.freeze(card); long now = clock.millis(); String initial = scheduler.initial(now);
            store.update("INSERT INTO review_states(card_id,epoch,revision,content_digest,state_json,algorithm_id,config_hash,due_at_ms,suspended,created_at_ms,updated_at_ms) VALUES(?,1,1,?,?,?,?,?,FALSE,?,?)",
                    cardId, frozen.get("contentDigest"), initial, FsrsScheduler.ALGORITHM, scheduler.configHash(), now, now, now);
            event(cardId, null, "ENROLL", 1, 1, now, Map.of("card", frozen, "after", json.decode(initial), "configuration", json.decode(scheduler.configJson())));
            return state(cardId);
        });
    }
    public Map<String, Object> manage(String cardId, String action, Map<String, Object> input) {
        fields(input, "revision", "epoch", "idempotencyKey");
        if (!Set.of("suspend", "resume", "reset").contains(action)) throw new ApiException(400, "INVALID_ACTION", "复习操作无效");
        return command(cardId, "REVIEW_" + action.toUpperCase(Locale.ROOT), input, () -> {
            var card = definitions.card(cardId, true); available(card); var before = rawState(cardId); revision(before, input);
            if (((Number) before.get("epoch")).intValue() != number(input, "epoch")) throw new ApiException(409, "STALE_REVIEW_STATE", "复习轮次已变化，请重新加载");
            long now = clock.millis(); int epoch = ((Number) before.get("epoch")).intValue(); int revision = ((Number) before.get("revision")).intValue() + 1;
            String state = (String) before.get("stateJson"); long due = ((Number) before.get("dueAtMs")).longValue();
            String digest = (String) before.get("contentDigest"); var frozen = definitions.freeze(card);
            if (action.equals("reset")) { epoch++; revision = 1; state = scheduler.initial(now); due = now; digest = (String) frozen.get("contentDigest"); }
            boolean suspended = action.equals("suspend");
            store.update("UPDATE review_states SET epoch=?,revision=?,content_digest=?,state_json=?,due_at_ms=?,suspended=?,updated_at_ms=? WHERE card_id=?", epoch, revision, digest, state, due, suspended, now, cardId);
            event(cardId, null, action.toUpperCase(Locale.ROOT), epoch, revision, now, Map.of("card", frozen, "before", before, "after", json.decode(state), "configuration", json.decode(scheduler.configJson())));
            return state(cardId);
        });
    }
    public Map<String, Object> start(String cardId, Map<String, Object> input) {
        fields(input, "idempotencyKey");
        return command(cardId, "REVIEW_START", input, () -> {
            var card = definitions.card(cardId, true); var state = rawState(cardId); current(card, state); var frozen = definitions.freeze(card);
            String id = AtlasStore.uuid(); long now = clock.millis();
            store.update("INSERT INTO review_attempts(id,card_id,epoch,state_revision,revision,status,card_payload,content_digest,recalled_answer,reflection,created_at_ms,updated_at_ms) VALUES(?,?,?,?,1,'DRAFT',?,?,'','',?,?)",
                    id, cardId, state.get("epoch"), state.get("revision"), store.encode(frozen), frozen.get("contentDigest"), now, now);
            return attempt(id);
        });
    }
    public Map<String, Object> attempt(String id) {
        var row = store.one("SELECT * FROM review_attempts WHERE id=? FOR UPDATE", id);
        var result = new LinkedHashMap<>(row); result.remove("cardPayload");
        result.put("card", definitions.view(json.decode((String) row.get("cardPayload")), row.get("revealedAtMs") != null));
        var events = store.rows("SELECT * FROM review_events WHERE attempt_id=? FOR UPDATE", id);
        result.put("reviewEvent", events.isEmpty() ? null : eventView(events.getFirst())); return result;
    }
    private Map<String, Object> lockAttempt(String id) {
        String cardId = (String) store.one("SELECT card_id FROM review_attempts WHERE id=?", id).get("cardId");
        definitions.card(cardId, true); rawState(cardId);
        return store.one("SELECT * FROM review_attempts WHERE id=? FOR UPDATE", id);
    }
    private void notFinal(Map<String, Object> row) {
        if (Set.of("COMMITTED", "SKIPPED").contains(row.get("status"))) throw new ApiException(409, "REVIEW_FINALIZED", "本次复习已结束，可开始新尝试");
    }
    public Map<String, Object> save(String id, Map<String, Object> input) {
        fields(input, "revision", "recalledAnswer");
        String answer = text(input, "recalledAnswer", 4000, false);
        return store.transaction(() -> {
            var row = lockAttempt(id); revision(row, input);
            if (!row.get("status").equals("DRAFT")) throw new ApiException(409, "RECALL_FROZEN", "答案已揭示，原回忆已保存");
            store.update("UPDATE review_attempts SET recalled_answer=?,revision=revision+1,updated_at_ms=? WHERE id=?", answer, clock.millis(), id);
            return attempt(id);
        });
    }
    public Map<String, Object> reveal(String id, Map<String, Object> input) {
        fields(input, "revision", "idempotencyKey");
        return command(id, "REVIEW_REVEAL", input, () -> {
            var row = lockAttempt(id); notFinal(row); revision(row, input);
            if (row.get("status").equals("DRAFT")) store.update("UPDATE review_attempts SET status='REVEALED',revealed_at_ms=?,revision=revision+1,updated_at_ms=? WHERE id=?", clock.millis(), clock.millis(), id);
            return attempt(id);
        });
    }
    public Map<String, Object> skip(String id, Map<String, Object> input) {
        fields(input, "revision", "idempotencyKey");
        return command(id, "REVIEW_SKIP", input, () -> {
            var row = lockAttempt(id); notFinal(row); revision(row, input);
            store.update("UPDATE review_attempts SET status='SKIPPED',revision=revision+1,updated_at_ms=? WHERE id=?", clock.millis(), id);
            return attempt(id);
        });
    }
    public Map<String, Object> submit(String id, Map<String, Object> input) {
        fields(input, "revision", "rating", "reflection", "idempotencyKey");
        String rating = text(input, "rating", 20, true); String reflection = text(input, "reflection", 4000, false);
        return command(id, "REVIEW_SUBMIT", input, () -> {
            var row = lockAttempt(id); notFinal(row); revision(row, input);
            if (!row.get("status").equals("REVEALED")) throw new ApiException(409, "REFERENCE_NOT_REVEALED", "请先核对参考答案，再选择复习评分");
            String cardId = (String) row.get("cardId"); var card = definitions.card(cardId, true); var state = rawState(cardId);
            current(card, state);
            if (!state.get("epoch").equals(row.get("epoch")) || !state.get("revision").equals(row.get("stateRevision"))) throw new ApiException(409, "STALE_REVIEW_STATE", "卡片复习状态已变化，请保留输入并开启新尝试");
            long now = clock.millis(); var scheduled = scheduler.schedule((String) state.get("stateJson"), rating, now);
            int next = ((Number) state.get("revision")).intValue() + 1;
            var payload = new LinkedHashMap<String, Object>(); payload.put("card", json.decode((String) row.get("cardPayload")));
            payload.put("rating", rating); payload.put("source", "USER_SELF_REPORTED"); payload.put("recalledAnswer", row.get("recalledAnswer"));
            payload.put("reflection", reflection); payload.put("revealedAtMs", row.get("revealedAtMs"));
            payload.put("before", json.decode((String) state.get("stateJson"))); payload.put("after", json.decode((String) scheduled.get("stateJson")));
            payload.put("dueAtMs", scheduled.get("dueAtMs")); payload.put("configuration", json.decode(scheduler.configJson())); payload.put("algorithmLog", json.decode((String) scheduled.get("algorithmLog")));
            var event = event(cardId, id, "REVIEW", ((Number) state.get("epoch")).intValue(), next, now, payload);
            store.update("UPDATE review_states SET revision=?,state_json=?,due_at_ms=?,updated_at_ms=? WHERE card_id=?", next, scheduled.get("stateJson"), scheduled.get("dueAtMs"), now, cardId);
            store.update("UPDATE review_attempts SET status='COMMITTED',reflection=?,revision=revision+1,updated_at_ms=? WHERE id=?", reflection, now, id);
            evidence.recordReview(event);
            return attempt(id);
        });
    }
    private Map<String, Object> event(String cardId, String attemptId, String kind, int epoch, int revision, long now, Map<String, Object> payload) {
        String id = AtlasStore.uuid();
        payload = new LinkedHashMap<>(payload); payload.put("configVersion", FsrsScheduler.CONFIG_VERSION);
        store.update("INSERT INTO review_events(id,card_id,attempt_id,kind,epoch,state_revision,occurred_at_ms,algorithm_id,config_hash,payload) VALUES(?,?,?,?,?,?,?,?,?,?)", id, cardId, attemptId, kind, epoch, revision, now, FsrsScheduler.ALGORITHM, scheduler.configHash(), store.encode(payload));
        return eventView(store.one("SELECT * FROM review_events WHERE id=?", id));
    }
    private Map<String, Object> eventView(Map<String, Object> row) { var result = new LinkedHashMap<>(row); result.put("payload", json.decode((String) row.get("payload"))); return result; }
    public Map<String, Object> event(String id) { return eventView(store.one("SELECT * FROM review_events WHERE id=?", id)); }
    public Map<String, Object> history(String cardId, int limit, int offset) {
        page(limit, offset); definitions.card(cardId, false);
        var rows = store.rows("SELECT * FROM review_events WHERE card_id=? ORDER BY occurred_at_ms DESC,epoch DESC,state_revision DESC", cardId);
        return Map.of("items", rows.stream().skip(offset).limit(limit).map(this::eventView).toList(), "total", rows.size(), "limit", limit, "offset", offset);
    }
    public Map<String, Object> queue(boolean dueOnly, String query, String knowledgeId, String tag, int limit, int offset) {
        page(limit, offset); long now = clock.millis(); String term = query.toLowerCase(Locale.ROOT);
        var rows = store.rows("SELECT l.project_id,s.card_id,s.due_at_ms,s.epoch,s.revision,l.title,l.payload,l.knowledge_id FROM review_states s JOIN learning_records l ON l.id=s.card_id WHERE l.deleted_at IS NULL AND s.suspended=FALSE ORDER BY s.due_at_ms,s.card_id");
        var items = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            if (!dev.codeatlas.learning.ProjectScope.matches(row.get("projectId"))) continue;
            var content = json.decode((String) row.remove("payload"));
            if (dueOnly && ((Number) row.get("dueAtMs")).longValue() > now) continue;
            if (!term.isEmpty() && !row.get("title").toString().toLowerCase(Locale.ROOT).contains(term)) continue;
            if (!knowledgeId.isEmpty() && !knowledgeId.equals(row.get("knowledgeId"))) continue;
            if (!tag.isEmpty() && !((List<?>) content.get("tags")).contains(tag)) continue;
            row.put("tags", content.get("tags")); row.put("id", row.get("cardId")); items.add(row);
        }
        return Map.of("items", items.stream().skip(offset).limit(limit).toList(), "total", items.size(), "limit", limit, "offset", offset, "serverNowMs", now);
    }
}
