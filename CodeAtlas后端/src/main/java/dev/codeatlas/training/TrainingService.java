package dev.codeatlas.training;

import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.function.*;
import static dev.codeatlas.training.TrainingDefinition.*;

@Service
public class TrainingService {
    private final AtlasStore store;
    private final SourceEvidenceService json;
    private final TrainingDefinition definitions;
    public TrainingService(AtlasStore store, SourceEvidenceService json, TrainingDefinition definitions) {
        this.store = store; this.json = json; this.definitions = definitions;
    }
    private static String now() { return new DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(Instant.now()); }

    private Map<String, Object> once(String key, String operation, Object body, Supplier<Map<String, Object>> action, Function<String, Map<String, Object>> restore) {
        String hash = SnapshotScanner.hash(store.encode(body));
        try {
            return store.transaction(() -> {
                var previous = store.rows("SELECT * FROM mutation_requests WHERE idempotency_key=?", key);
                if (!previous.isEmpty()) return restore(previous.getFirst(), operation, hash, restore);
                // Claim the key before taking domain locks so simultaneous retries wait
                // for the first transaction instead of racing to finalize the attempt.
                store.update("INSERT INTO mutation_requests(idempotency_key,operation,body_hash,result_id,created_at) VALUES(?,?,?,?,?)", key, operation, hash, AtlasStore.uuid(), now());
                var result = action.get();
                store.update("UPDATE mutation_requests SET result_id=? WHERE idempotency_key=?", result.get("id"), key);
                return result;
            });
        } catch (DuplicateKeyException e) {
            return restore(store.one("SELECT * FROM mutation_requests WHERE idempotency_key=?", key), operation, hash, restore);
        }
    }
    private Map<String, Object> restore(Map<String, Object> previous, String operation, String hash, Function<String, Map<String, Object>> restore) {
        if (!operation.equals(previous.get("operation")) || !hash.equals(previous.get("bodyHash"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "请求标识已用于不同操作或内容");
        return restore.apply((String) previous.get("resultId"));
    }

    public Map<String, Object> createTask(Map<String, Object> input) {
        return createTask(input, dev.codeatlas.learning.ProjectScope.current());
    }
    public Map<String, Object> createTask(Map<String, Object> input, String projectId) {
        var normalized = definitions.normalize(input);
        String owner = projectId.isBlank() ? null : projectId;
        if (owner != null) store.one("SELECT id FROM projects WHERE id=?", owner);
        for (Object sourceId : (List<?>) normalized.get("sourceIds")) {
            String sourceProject = (String) json.get((String) sourceId).get("projectId");
            if (owner == null) owner = sourceProject;
            else if (!owner.equals(sourceProject)) throw invalid("任务来源不属于当前项目");
        }
        final String taskProject = owner;
        return once(text(input, "idempotencyKey", 100, true), "TRAINING_TASK_CREATE", taskProject == null ? normalized : Map.of("projectId", taskProject, "task", normalized), () -> {
            String id = AtlasStore.uuid(); String time = now();
            store.update("INSERT INTO training_tasks(id,title,status,revision,project_id,created_at,updated_at) VALUES(?,?,?,1,?,?,?)", id, normalized.get("title"), normalized.get("status"), taskProject, time, time);
            saveVersion(id, 1, normalized);
            return task(id, true);
        }, id -> task(id, true));
    }
    public Map<String, Object> updateTask(String id, Map<String, Object> input) {
        var normalized = definitions.normalize(input); int expected = SourceEvidenceService.integer(input, "revision", 0);
        return store.transaction(() -> {
            var row = store.one("SELECT * FROM training_tasks WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
            revision(row, expected);
            if (row.get("projectId") != null) for (Object sourceId : (List<?>) normalized.get("sourceIds"))
                if (!row.get("projectId").equals(json.get((String) sourceId).get("projectId"))) throw invalid("任务来源不属于当前项目");
            int next = expected + 1; saveVersion(id, next, normalized);
            store.update("UPDATE training_tasks SET title=?,status=?,revision=?,updated_at=? WHERE id=?", normalized.get("title"), normalized.get("status"), next, now(), id);
            return task(id, true);
        });
    }
    private void saveVersion(String id, int revision, Map<String, Object> normalized) {
        var frozen = definitions.freeze(normalized);
        String generation = (String) normalized.get("generationId");
        if (!generation.isEmpty()) {
            var row = store.one("SELECT c.context_json,g.result_json FROM ai_generations g JOIN ai_contexts c ON c.id=g.context_id WHERE g.id=? AND g.status='SUCCEEDED'", generation);
            var context = json.decode((String) row.get("contextJson"));
            if (!"TRAINING_QUESTIONS".equals(context.get("operation"))) throw invalid("出题来源的生成用途不匹配");
            var generated = json.decode((String) row.get("resultJson"));
            var origins = (List<?>) generated.get("citations");
            boolean verified = origins.stream().map(TrainingDefinition::map).anyMatch(citation -> "VERIFIED".equals(citation.get("verificationStatus")) && ((List<?>) normalized.get("sourceIds")).contains(citation.get("sourceId")));
            if (!verified) throw invalid("AI出题候选需要至少一个已校验且仍关联的来源");
            var origin = TrainingDefinition.map(((List<?>) context.get("sources")).getFirst());
            var target = TrainingDefinition.map(((List<?>) frozen.get("sources")).getFirst());
            if (!origin.get("snapshotId").equals(target.get("snapshotId")) || !Objects.equals(origin.get("generationId"), target.get("generationId"))) throw invalid("AI候选与任务源码版本不一致");
        }
        String payload = store.encode(frozen);
        store.update("INSERT INTO training_task_revisions(task_id,revision,payload,definition_digest,created_at) VALUES(?,?,?,?,?)", id, revision, payload, SnapshotScanner.hash(payload), now());
        for (Object source : (List<?>) normalized.get("sourceIds")) store.update("INSERT INTO training_task_sources(task_id,task_revision,source_id) VALUES(?,?,?)", id, revision, source);
    }
    public Map<String, Object> task(String id, boolean editor) {
        var row = store.one("SELECT * FROM training_tasks WHERE id=? AND deleted_at IS NULL", id);
        if(!dev.codeatlas.learning.ProjectScope.matches(row.get("projectId"))) throw ApiException.missing();
        var result = taskVersion(id, ((Number) row.get("revision")).intValue(), editor);
        result.put("createdAt", row.get("createdAt")); result.put("updatedAt", row.get("updatedAt")); return result;
    }
    public Map<String, Object> taskVersion(String id, int revision, boolean editor) {
        // A start can wait for an editor and then observe a revision newer than its initial MySQL snapshot.
        var row = store.one("SELECT * FROM training_task_revisions WHERE task_id=? AND revision=? FOR UPDATE", id, revision);
        var result = definitions.view(json.decode((String) row.get("payload")), editor);
        result.put("id", id); result.put("revision", revision); result.put("definitionDigest", row.get("definitionDigest")); return result;
    }
    public Map<String, Object> listTasks(String query, String status, int limit, int offset) {
        return listTasks(query, status, dev.codeatlas.learning.ProjectScope.current(), limit, offset);
    }
    public Map<String, Object> listTasks(String query, String status, String projectId, int limit, int offset) {
        page(limit, offset);
        if (!status.isEmpty() && !Set.of("DRAFT", "ACTIVE", "ARCHIVED").contains(status)) throw invalid("任务状态过滤无效");
        String term = query.toLowerCase(Locale.ROOT);
        var matching = (projectId.isBlank()
                ? store.rows("SELECT t.*,r.payload FROM training_tasks t JOIN training_task_revisions r ON r.task_id=t.id AND r.revision=t.revision WHERE t.deleted_at IS NULL ORDER BY t.updated_at DESC,t.id")
                : store.rows("SELECT t.*,r.payload FROM training_tasks t JOIN training_task_revisions r ON r.task_id=t.id AND r.revision=t.revision WHERE t.deleted_at IS NULL AND t.project_id=? ORDER BY t.updated_at DESC,t.id", projectId))
                .stream().filter(row -> (status.isEmpty() || status.equals(row.get("status"))) && (((String) row.get("title")) + row.get("payload")).toLowerCase(Locale.ROOT).contains(term)).toList();
        var items = matching.stream().skip(offset).limit(limit).map(row -> {
            var definition = json.decode((String) row.get("payload")); var result = new LinkedHashMap<>(row); result.remove("payload");
            result.put("goal", definition.get("goal")); result.put("tags", definition.get("tags")); result.put("questionCount", questions(definition).size()); return result;
        }).toList();
        return Map.of("items", items, "total", matching.size(), "limit", limit, "offset", offset);
    }
    public Map<String, Object> deleteTask(String id) {
        return store.transaction(() -> {
            store.one("SELECT id FROM training_tasks WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
            store.update("UPDATE training_tasks SET deleted_at=?,updated_at=? WHERE id=?", now(), now(), id);
            return Map.of("id", id, "deleted", true);
        });
    }

    public Map<String, Object> start(String taskId, Map<String, Object> input) {
        fields(input, "idempotencyKey");
        return once(text(input, "idempotencyKey", 100, true), "TRAINING_ATTEMPT_START", Map.of("taskId", taskId), () -> {
            var task = store.one("SELECT * FROM training_tasks WHERE id=? AND deleted_at IS NULL FOR UPDATE", taskId);
            if ("ARCHIVED".equals(task.get("status"))) throw new ApiException(409, "TASK_ARCHIVED", "已归档任务不能开始新的尝试");
            var version = store.one("SELECT * FROM training_task_revisions WHERE task_id=? AND revision=? FOR UPDATE", taskId, task.get("revision"));
            if ("DRAFT".equals(task.get("status")) && questions(json.decode((String) version.get("payload"))).isEmpty())
                throw new ApiException(409, "TASK_EMPTY_DRAFT", "草稿还没有题目，请先编辑并添加题目");
            String id = AtlasStore.uuid(); String time = now();
            store.update("INSERT INTO training_attempts(id,task_id,task_revision,definition_digest,revision,status,answers_payload,created_at,updated_at) VALUES(?,?,?,?,1,'DRAFT',?,?,?)",
                    id, taskId, task.get("revision"), version.get("definitionDigest"), store.encode(Map.of("items", List.of())), time, time);
            return attempt(id);
        }, this::attempt);
    }
    public Map<String, Object> attempt(String id) {
        var row = store.one("SELECT * FROM training_attempts WHERE id=?", id);
        var result = new LinkedHashMap<>(row); result.remove("answersPayload"); result.remove("resultsPayload");
        result.put("answers", items((String) row.get("answersPayload"))); result.put("results", items((String) row.get("resultsPayload")));
        result.put("task", taskVersion((String) row.get("taskId"), ((Number) row.get("taskRevision")).intValue(), false));
        result.put("reveals", store.rows("SELECT payload FROM training_attempt_events WHERE attempt_id=? ORDER BY created_at,id", id).stream().map(item -> json.decode((String) item.get("payload"))).toList());
        result.put("selfAssessments", store.rows("SELECT payload FROM training_self_assessments WHERE attempt_id=? ORDER BY created_at,id", id).stream().map(item -> json.decode((String) item.get("payload"))).toList());
        var feedback = store.rows("SELECT g.id,g.status,g.result_json,g.error_code,r.question_id FROM training_ai_requests r JOIN ai_generations g ON g.context_id=r.context_id WHERE r.attempt_id=? AND r.operation='TRAINING_FEEDBACK' ORDER BY g.created_at DESC", id);
        for (var item : feedback) { Object payload = item.remove("resultJson"); item.put("result", payload == null ? null : json.decode((String) payload)); }
        result.put("aiFeedback", feedback); return result;
    }
    public Map<String, Object> listAttempts(String taskId, int limit, int offset) {
        return listAttempts(taskId, dev.codeatlas.learning.ProjectScope.current(), limit, offset);
    }
    public Map<String, Object> listAttempts(String taskId, String projectId, int limit, int offset) {
        page(limit, offset);
        var rows = (projectId.isBlank()
                ? store.rows("SELECT a.id,a.task_id,a.task_revision,a.status,a.created_at,a.submitted_at,r.payload FROM training_attempts a JOIN training_task_revisions r ON r.task_id=a.task_id AND r.revision=a.task_revision ORDER BY a.created_at DESC,a.id")
                : store.rows("SELECT a.id,a.task_id,a.task_revision,a.status,a.created_at,a.submitted_at,r.payload FROM training_attempts a JOIN training_task_revisions r ON r.task_id=a.task_id AND r.revision=a.task_revision JOIN training_tasks t ON t.id=a.task_id WHERE t.project_id=? ORDER BY a.created_at DESC,a.id", projectId))
                .stream().filter(row -> taskId.isEmpty() || taskId.equals(row.get("taskId"))).toList();
        var items = rows.stream().skip(offset).limit(limit).map(row -> { var item = new LinkedHashMap<>(row); item.put("title", json.decode((String) item.remove("payload")).get("title")); return item; }).toList();
        return Map.of("items", items, "total", rows.size(), "limit", limit, "offset", offset);
    }
    public Map<String, Object> saveAnswers(String id, Map<String, Object> input) {
        fields(input, "revision", "answers");
        return store.transaction(() -> {
            var row = lockAttempt(id); draft(row); revision(row, SourceEvidenceService.integer(input, "revision", 0));
            var answers = definitions.answers(frozen(row), input.getOrDefault("answers", List.of()));
            store.update("UPDATE training_attempts SET answers_payload=?,revision=revision+1,updated_at=? WHERE id=?", store.encode(Map.of("items", answers)), now(), id);
            return attempt(id);
        });
    }
    public Map<String, Object> complete(String id, Map<String, Object> input, boolean skipped) {
        fields(input, "revision", "answers", "idempotencyKey");
        var body = new LinkedHashMap<String, Object>(); body.put("id", id); body.put("revision", input.get("revision"));
        body.put("answersProvided", input.containsKey("answers")); body.put("answers", input.get("answers"));
        return once(text(input, "idempotencyKey", 100, true), skipped ? "TRAINING_ATTEMPT_SKIP" : "TRAINING_ATTEMPT_SUBMIT", body, () -> {
            var row = lockAttempt(id); draft(row); revision(row, SourceEvidenceService.integer(input, "revision", 0));
            var definition = frozen(row);
            Object answers = input.containsKey("answers") ? input.get("answers") : items((String) row.get("answersPayload"));
            var normalized = definitions.answers(definition, answers);
            var results = evaluate(id, definition, normalized, skipped);
            String digest = SnapshotScanner.hash(store.encode(Map.of("taskId", row.get("taskId"), "taskRevision", row.get("taskRevision"), "definitionDigest", row.get("definitionDigest"), "answers", normalized)));
            String time = now();
            store.update("UPDATE training_attempts SET status=?,answers_payload=?,results_payload=?,submission_digest=?,revision=revision+1,updated_at=?,submitted_at=? WHERE id=?",
                    skipped ? "SKIPPED" : "SUBMITTED", store.encode(Map.of("items", normalized)), store.encode(Map.of("items", results)), digest, time, time, id);
            return attempt(id);
        }, this::attempt);
    }
    private List<Map<String, Object>> evaluate(String id, Map<String, Object> definition, List<Map<String, Object>> answers, boolean skipped) {
        // Read the latest committed reveals after taking the attempt lock, including under MySQL REPEATABLE READ.
        var events = store.rows("SELECT question_id,kind FROM training_attempt_events WHERE attempt_id=? FOR UPDATE", id);
        var results = new ArrayList<Map<String, Object>>();
        for (var answer : answers) {
            String questionId = (String) answer.get("questionId"); var question = definitions.question(definition, questionId);
            Object value = answer.get("value"); boolean empty = value == null || (value instanceof String text && text.isBlank());
            String verdict;
            if (skipped || Boolean.TRUE.equals(answer.get("skipped"))) verdict = "SKIPPED";
            else if (empty) verdict = "UNANSWERED";
            else if (question.get("format").equals("SHORT_ANSWER")) verdict = "NEEDS_REVIEW";
            else verdict = Objects.equals(value, question.get("correctAnswer")) ? "CORRECT" : "INCORRECT";
            var result = new LinkedHashMap<String, Object>(); result.put("questionId", questionId); result.put("verdict", verdict);
            result.put("source", question.get("format").equals("SHORT_ANSWER") ? "NOT_ASSESSED" : "RULE_BASED");
            result.put("hintViewed", events.stream().anyMatch(event -> questionId.equals(event.get("questionId")) && "HINT".equals(event.get("kind"))));
            result.put("referenceViewed", events.stream().anyMatch(event -> questionId.equals(event.get("questionId")) && "REFERENCE".equals(event.get("kind")))); results.add(result);
        }
        return results;
    }
    public Map<String, Object> reveal(String id, Map<String, Object> input) {
        fields(input, "questionId", "kind", "hintIndex", "idempotencyKey");
        String questionId = text(input, "questionId", 36, true); String kind = text(input, "kind", 20, true);
        int index = SourceEvidenceService.integer(input, "hintIndex", 0);
        var body = Map.of("attemptId", id, "questionId", questionId, "kind", kind, "hintIndex", index);
        return once(text(input, "idempotencyKey", 100, true), "TRAINING_REVEAL", body, () -> {
            var row = lockAttempt(id); var question = definitions.question(frozen(row), questionId);
            Object content; String explanation = "";
            if (kind.equals("HINT")) {
                var hints = (List<?>) question.get("hints"); if (index < 0 || index >= hints.size()) throw invalid("提示序号无效"); content = hints.get(index);
            } else if (kind.equals("REFERENCE")) { content = question.get("correctAnswer"); explanation = (String) question.get("referenceExplanation"); }
            else throw invalid("查看内容类型无效");
            String eventId = AtlasStore.uuid();
            var result = new LinkedHashMap<String, Object>(); result.put("id", eventId); result.putAll(body); result.put("content", content); result.put("explanation", explanation); result.put("createdAt", now());
            store.update("INSERT INTO training_attempt_events(id,attempt_id,question_id,kind,payload,created_at) VALUES(?,?,?,?,?,?)", eventId, id, questionId, kind, store.encode(result), result.get("createdAt"));
            return result;
        }, eventId -> json.decode((String) store.one("SELECT payload FROM training_attempt_events WHERE id=?", eventId).get("payload")));
    }
    public Map<String, Object> selfAssess(String id, Map<String, Object> input) {
        fields(input, "questionId", "rating", "reflection", "idempotencyKey");
        String questionId = text(input, "questionId", 36, true); String rating = text(input, "rating", 30, true);
        if (!Set.of("UNDERSTOOD", "PARTIAL", "NEEDS_REVIEW").contains(rating)) throw invalid("自评选项无效");
        var body = Map.of("attemptId", id, "questionId", questionId, "rating", rating, "reflection", text(input, "reflection", 4000, false));
        return once(text(input, "idempotencyKey", 100, true), "TRAINING_SELF_ASSESS", body, () -> {
            var row = lockAttempt(id);
            if (!"SUBMITTED".equals(row.get("status"))) throw new ApiException(409, "ATTEMPT_NOT_SUBMITTED", "提交后可追加自评");
            if (!"SHORT_ANSWER".equals(definitions.question(frozen(row), questionId).get("format"))) throw invalid("当前题目使用规则判分");
            String eventId = AtlasStore.uuid(); var result = new LinkedHashMap<String, Object>(body);
            result.put("id", eventId); result.put("source", "USER_SELF_REPORTED"); result.put("createdAt", now());
            store.update("INSERT INTO training_self_assessments(id,attempt_id,question_id,payload,created_at) VALUES(?,?,?,?,?)", eventId, id, questionId, store.encode(result), result.get("createdAt"));
            return result;
        }, eventId -> json.decode((String) store.one("SELECT payload FROM training_self_assessments WHERE id=?", eventId).get("payload")));
    }
    public Map<String, Object> frozen(Map<String, Object> attempt) {
        return json.decode((String) store.one("SELECT payload FROM training_task_revisions WHERE task_id=? AND revision=?", attempt.get("taskId"), attempt.get("taskRevision")).get("payload"));
    }
    public Map<String, Object> rawAttempt(String id) { return store.one("SELECT * FROM training_attempts WHERE id=?", id); }
    private Map<String, Object> lockAttempt(String id) { return store.one("SELECT * FROM training_attempts WHERE id=? FOR UPDATE", id); }
    private List<?> items(String payload) { return payload == null ? List.of() : (List<?>) json.decode(payload).get("items"); }
    private static void draft(Map<String, Object> row) {
        if (!"DRAFT".equals(row.get("status"))) throw new ApiException(409, "ATTEMPT_FINALIZED", "此次尝试已结束，重新作答会创建新尝试");
    }
    private static void revision(Map<String, Object> row, int expected) {
        if (expected < 1 || ((Number) row.get("revision")).intValue() != expected) throw new ApiException(409, "REVISION_CONFLICT", "内容已更新，请保留草稿后重新加载");
    }
    private static void page(int limit, int offset) { if (limit < 1 || limit > 100 || offset < 0) throw invalid("分页范围无效"); }
}
