package dev.codeatlas.training;

import dev.codeatlas.learning.LearningService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TrainingDefinition {
    private final SourceEvidenceService sources;
    private final AtlasStore store;
    public TrainingDefinition(SourceEvidenceService sources, AtlasStore store) { this.sources = sources; this.store = store; }

    public Map<String, Object> normalize(Map<String, Object> input) {
        fields(input, "title", "goal", "constraints", "acceptanceCriteria", "status", "tags", "sourceIds", "questions", "generationId", "idempotencyKey", "revision");
        String status = input.containsKey("status") ? text(input, "status", 20, true) : "DRAFT";
        if (!Set.of("DRAFT", "ACTIVE", "ARCHIVED").contains(status)) throw invalid("任务状态无效");
        boolean published = status.equals("ACTIVE");
        var result = new LinkedHashMap<String, Object>();
        result.put("title", text(input, "title", 200, true));
        result.put("goal", text(input, "goal", 4000, published));
        result.put("constraints", text(input, "constraints", 4000, false));
        result.put("acceptanceCriteria", text(input, "acceptanceCriteria", 4000, published));
        result.put("status", status);
        result.put("tags", LearningService.strings(input, "tags", 10, 32));
        List<String> ids = LearningService.strings(input, "sourceIds", 6, 36);
        if (ids.isEmpty()) throw invalid("请选择至少一个固定源码来源");
        result.put("sourceIds", ids);
        var questions = list(input.getOrDefault("questions", List.of()), 10);
        if (published && questions.isEmpty()) throw invalid("发布任务至少需要一道完整题目");
        var normalized = new ArrayList<Map<String, Object>>();
        var questionIds = new HashSet<String>();
        for (Object raw : questions) {
            var question = normalizeQuestion(map(raw), published);
            if (!questionIds.add((String) question.get("id"))) throw invalid("题目ID重复");
            normalized.add(question);
        }
        result.put("questions", normalized);
        result.put("generationId", text(input, "generationId", 36, false));
        return result;
    }

    private Map<String, Object> normalizeQuestion(Map<String, Object> input, boolean published) {
        fields(input, "id", "category", "format", "prompt", "options", "correctAnswer", "referenceExplanation", "hints");
        String id = text(input, "id", 36, true);
        try { if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw invalid("题目ID无效"); }
        catch (IllegalArgumentException e) { throw invalid("题目ID无效"); }
        String category = input.containsKey("category") ? text(input, "category", 40, true) : "CODE_READING";
        String format = input.containsKey("format") ? text(input, "format", 40, true) : "SHORT_ANSWER";
        if (!Set.of("CODE_READING", "CALL_CHAIN", "ERROR_JUDGMENT").contains(category)
                || !Set.of("SINGLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER").contains(format)) throw invalid("题目类别或格式无效");
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id.toLowerCase(Locale.ROOT)); result.put("category", category); result.put("format", format);
        result.put("prompt", text(input, "prompt", 4000, published));
        var options = new ArrayList<Map<String, Object>>();
        var optionIds = new HashSet<String>();
        for (Object raw : list(input.getOrDefault("options", List.of()), 6)) {
            var option = map(raw); fields(option, "id", "text");
            String optionId = text(option, "id", 40, true);
            if (!optionIds.add(optionId)) throw invalid("选项ID重复");
            options.add(Map.of("id", optionId, "text", text(option, "text", 500, published)));
        }
        if (!format.equals("SINGLE_CHOICE") && !options.isEmpty()) throw invalid("当前题型不接受选项");
        Object answer = input.get("correctAnswer");
        if (format.equals("TRUE_FALSE")) {
            if (answer != null && !(answer instanceof Boolean)) throw invalid("判断题参考答案须为布尔值");
            if (published && answer == null) throw invalid("请选择判断题参考答案");
        } else {
            if (answer == null) answer = "";
            if (!(answer instanceof String value) || value.length() > 4000 || (published && value.isBlank())) throw invalid("参考答案无效");
            if (format.equals("SINGLE_CHOICE") && published && (options.size() < 2 || !optionIds.contains(answer))) throw invalid("选择题需要至少两个选项和有效参考答案");
        }
        result.put("options", options); result.put("correctAnswer", answer);
        result.put("referenceExplanation", text(input, "referenceExplanation", 8000, published));
        result.put("hints", LearningService.strings(input, "hints", 5, 1000));
        return result;
    }

    public Map<String, Object> freeze(Map<String, Object> normalized) {
        var result = sources.decode(store.encode(normalized));
        var frozen = new ArrayList<Map<String, Object>>();
        for (Object id : (List<?>) normalized.get("sourceIds")) {
            var source = sources.get((String) id);
            if (!frozen.isEmpty() && (!frozen.getFirst().get("snapshotId").equals(source.get("snapshotId"))
                    || !Objects.equals(frozen.getFirst().get("generationId"), source.get("generationId")))) throw invalid("任务来源须属于同一快照与代次");
            var metadata = new LinkedHashMap<String, Object>();
            for (String name : List.of("id", "projectId", "snapshotId", "generationId", "fileId", "filePath", "sha256", "symbolId", "symbolName", "startLine", "endLine")) metadata.put(name, source.get(name));
            frozen.add(metadata);
        }
        result.put("sources", frozen);
        return result;
    }

    public Map<String, Object> view(Map<String, Object> frozen, boolean editor) {
        var result = sources.decode(store.encode(frozen));
        var visibleSources = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) frozen.get("sources")) {
            var metadata = map(raw);
            try {
                var source = sources.get((String) metadata.get("id")); source.put("availability", "AVAILABLE"); visibleSources.add(source);
            } catch (ApiException e) {
                var unavailable = new LinkedHashMap<>(metadata); unavailable.put("availability", "UNAVAILABLE");
                unavailable.put("availabilityMessage", "历史源码暂不可用，题目与回答已保留"); visibleSources.add(unavailable);
            }
        }
        result.put("sources", visibleSources);
        if (!editor) {
            var visible = new ArrayList<Map<String, Object>>();
            for (var question : questions(result)) {
                var copy = new LinkedHashMap<>(question); copy.put("hintCount", ((List<?>) copy.remove("hints")).size());
                copy.remove("correctAnswer"); copy.remove("referenceExplanation"); visible.add(copy);
            }
            result.put("questions", visible);
        }
        return result;
    }

    public List<Map<String, Object>> answers(Map<String, Object> definition, Object rawAnswers) {
        var supplied = new LinkedHashMap<String, Map<String, Object>>();
        for (Object raw : list(rawAnswers, 10)) {
            var answer = map(raw); fields(answer, "questionId", "value", "skipped");
            String id = text(answer, "questionId", 36, true); question(definition, id);
            if (supplied.put(id, answer) != null) throw invalid("回答题目重复");
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var question : questions(definition)) {
            String id = (String) question.get("id"); var input = supplied.getOrDefault(id, Map.of());
            Object skipped = input.getOrDefault("skipped", false);
            if (!(skipped instanceof Boolean)) throw invalid("跳过标记须为布尔值");
            Object value = Boolean.TRUE.equals(skipped) ? null : input.get("value");
            if (value != null) {
                String format = (String) question.get("format");
                if (format.equals("TRUE_FALSE")) { if (!(value instanceof Boolean)) throw invalid("判断题回答须为布尔值"); }
                else {
                    if (!(value instanceof String text) || text.length() > 4000) throw invalid("回答内容无效或超限");
                    if (format.equals("SINGLE_CHOICE") && !text.isEmpty() && ((List<?>) question.get("options")).stream().noneMatch(option -> map(option).get("id").equals(text))) throw invalid("回答包含无效选项");
                }
            }
            var answer = new LinkedHashMap<String, Object>(); answer.put("questionId", id); answer.put("value", value); answer.put("skipped", skipped); result.add(answer);
        }
        return result;
    }

    public Map<String, Object> question(Map<String, Object> definition, String id) {
        return questions(definition).stream().filter(q -> id.equals(q.get("id"))).findFirst().orElseThrow(() -> invalid("题目不属于当前任务版本"));
    }
    public static List<Map<String, Object>> questions(Map<String, Object> definition) { return ((List<?>) definition.get("questions")).stream().map(TrainingDefinition::map).toList(); }
    @SuppressWarnings("unchecked") public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid("字段须为对象");
        return (Map<String, Object>) value;
    }
    public static List<?> list(Object value, int limit) {
        if (!(value instanceof List<?> values) || values.size() > limit) throw invalid("列表类型或数量无效");
        return values;
    }
    public static void fields(Map<String, Object> input, String... allowed) {
        if (!Set.of(allowed).containsAll(input.keySet())) throw invalid("请求包含不支持的字段");
    }
    public static String text(Map<String, Object> input, String name, int maximum, boolean required) { return LearningService.text(input, name, maximum, required); }
    public static ApiException invalid(String message) { return new ApiException(400, "INVALID_TRAINING_REQUEST", message); }
}
