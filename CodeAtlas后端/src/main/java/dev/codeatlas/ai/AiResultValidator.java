package dev.codeatlas.ai;

import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.training.TrainingDefinition;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class AiResultValidator {
    private final AtlasStore store;
    private final SourceEvidenceService sources;
    private final TrainingDefinition training;
    public AiResultValidator(AtlasStore store, SourceEvidenceService sources, TrainingDefinition training) {
        this.store = store; this.sources = sources; this.training = training;
    }
    public Map<String, Object> validate(Map<String, Object> response, String contextJson) {
        if (!(response.get("result") instanceof Map<?, ?> raw)) throw schemaError();
        var result = sources.decode(store.encode(raw)); var context = sources.decode(contextJson);
        String operation = (String) context.getOrDefault("operation", "EXPLANATION");
        switch (operation) {
            case "EXPLANATION" -> {
                keys(result, "title", "summary", "keyPoints", "explanation", "citations", "uncertainties");
                requiredText(result, "title", 200); requiredText(result, "summary", 4000); requiredText(result, "explanation", 32_000);
                textArray(result.get("keyPoints"));
            }
            case "TRAINING_QUESTIONS" -> validateQuestions(result, context, contextJson);
            case "TRAINING_FEEDBACK" -> {
                keys(result, "strengths", "gaps", "explanation", "suggestedVerdict", "citations", "uncertainties");
                textArray(result.get("strengths")); textArray(result.get("gaps")); requiredText(result, "explanation", 8000);
                requiredText(result, "suggestedVerdict", 20);
                if (!Set.of("CORRECT", "PARTIAL", "INCORRECT", "UNSURE").contains(result.get("suggestedVerdict"))) throw schemaError();
                result.put("source", "AI_ASSISTED");
            }
            case "PRACTICE_FEEDBACK" -> {
                keys(result, "strengths", "gaps", "explanation", "suggestedVerdict", "citations", "uncertainties");
                textArray(result.get("strengths")); textArray(result.get("gaps")); requiredText(result, "explanation", 8000);
                requiredText(result, "suggestedVerdict", 20);
                if (!Set.of("PASS", "PARTIAL", "NEEDS_WORK", "UNSURE").contains(result.get("suggestedVerdict"))) throw schemaError();
                result.put("source", "AI_ASSISTED");
            }
            case "LEARNING_GUIDE" -> validateGuide(result, context);
            default -> throw schemaError();
        }
        textArray(result.get("uncertainties"));
        result.put("citations", citations(result.get("citations"), context));
        var usage = new LinkedHashMap<String, Object>();
        if (response.get("usage") instanceof Map<?, ?> entries) {
            for (String field : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
                Object value = entries.get(field); if (value instanceof Number number && number.longValue() >= 0) usage.put(field, number);
            }
        }
        result.put("usage", usage); return result;
    }
    private void validateGuide(Map<String, Object> result, Map<String, Object> context) {
        keys(result, "summary", "steps", "citations", "uncertainties"); requiredText(result, "summary", 4000);
        if (!(result.get("steps") instanceof List<?> steps) || steps.size() != 6) throw schemaError();
        var expected = new HashSet<Object>();
        var guide = TrainingDefinition.map(context.get("guide"));
        for (Object raw : (List<?>) guide.get("steps")) expected.add(TrainingDefinition.map(raw).get("id"));
        var seen = new HashSet<Object>();
        for (Object raw : steps) {
            if (!(raw instanceof Map<?, ?>)) throw schemaError();
            var step = TrainingDefinition.map(raw); keys(step, "stepId", "reason", "actionAdvice");
            requiredText(step, "stepId", 20); requiredText(step, "reason", 2000); requiredText(step, "actionAdvice", 4000);
            if (!seen.add(step.get("stepId"))) throw schemaError();
        }
        if (!seen.equals(expected)) throw schemaError();
        if (!(result.get("citations") instanceof List<?> citations) || (((List<?>) context.get("sources")).isEmpty() && !citations.isEmpty())) throw schemaError();
        result.put("source", "AI_ASSISTED");
    }
    private void validateQuestions(Map<String, Object> result, Map<String, Object> context, String contextJson) {
        requiredKeys(result, "title", "goal", "constraints", "acceptanceCriteria", "questions", "citations", "uncertainties");
        Object rawQuestions = result.get("questions");
        if (!(rawQuestions instanceof List<?> questions) || questions.isEmpty() || questions.size() > 10) throw schemaError();
        var params = TrainingDefinition.map(context.get("training"));
        var identified = new ArrayList<Map<String, Object>>(); int index = 0;
        for (Object raw : questions) {
            var question = new LinkedHashMap<>(TrainingDefinition.map(raw));
            if (question.containsKey("id")) throw schemaError();
            if (!((List<?>) params.get("formats")).contains(question.get("format"))) throw schemaError();
            question.put("id", UUID.nameUUIDFromBytes((SnapshotScanner.hash(contextJson) + ":" + index++).getBytes(StandardCharsets.UTF_8)).toString());
            identified.add(question);
        }
        result.put("knowledge", knowledge(result.get("knowledge"), context));
        var task = new LinkedHashMap<String, Object>();
        for (String key : List.of("title", "goal", "constraints", "acceptanceCriteria")) task.put(key, result.get(key));
        task.put("status", "ACTIVE"); task.put("questions", identified);
        task.put("sourceIds", ((List<?>) context.get("sources")).stream().map(source -> TrainingDefinition.map(source).get("sourceId")).toList());
        try { result.put("questions", training.normalize(task).get("questions")); }
        catch (ApiException e) { throw schemaError(); }
    }
    private List<Map<String, Object>> knowledge(Object raw, Map<String, Object> context) {
        if (!(raw instanceof List<?> list)) return List.of();
        var allowed = new HashMap<String, Map<?, ?>>();
        if (context.get("sources") instanceof List<?> originals)
            for (Object original : originals) if (original instanceof Map<?, ?> source) allowed.put((String) source.get("sourceId"), source);
        var checked = new ArrayList<Map<String, Object>>();
        for (Object entry : list) {
            if (checked.size() >= 8) break;
            if (!(entry instanceof Map<?, ?>)) continue;
            var output = sources.decode(store.encode(entry));
            String body = clipped(output.get("body"), 16_000);
            String title = clipped(output.get("title"), 200);
            if (title.isBlank() && body.isBlank()) continue;
            if (title.isBlank()) title = "项目知识点 " + (checked.size() + 1);
            var item = new LinkedHashMap<String, Object>();
            item.put("title", title); item.put("body", body);
            item.put("code", "");
            item.put("analysis", clipped(output.get("analysis"), 8_000));
            var source = allowed.get(String.valueOf(output.getOrDefault("sourceId", "")));
            if (source != null) {
                int low = ((Number) source.get("startLine")).intValue();
                int high = ((Number) source.get("endLine")).intValue();
                int start = output.get("startLine") instanceof Number value && value.intValue()>0 ? value.intValue() : low;
                int end = output.get("endLine") instanceof Number value && value.intValue()>0 ? value.intValue() : high;
                item.put("sourceId", source.get("sourceId")); item.put("filePath", source.get("filePath"));
                if(start<low || end>high || end<start) throw schemaError();
                var actual=sources.get((String)source.get("sourceId"));
                String[] lines=((String)actual.get("content")).split("\\n",-1);
                String code=String.join("\n",Arrays.copyOfRange(lines,start-low,end-low+1));
                item.put("code",clipped(code,8_000));
                item.put("startLine",start); item.put("endLine",end);
            } else {
                item.put("sourceId", ""); item.put("filePath", ""); item.put("startLine", 0); item.put("endLine", 0);
            }
            checked.add(item);
        }
        return checked;
    }
    private static String clipped(Object raw, int limit) {
        if (!(raw instanceof String text)) return "";
        String value = text.strip();
        return value.length() > limit ? value.substring(0, limit) : value;
    }
    private List<Map<String, Object>> citations(Object raw, Map<String, Object> context) {
        if (!(raw instanceof List<?> list) || list.size() > 24 || !(context.get("sources") instanceof List<?> originals)) throw schemaError();
        var allowed = new HashMap<String, Map<?, ?>>();
        for (Object original : originals) if (original instanceof Map<?, ?> source) allowed.put((String) source.get("sourceId"), source);
        var checked = new ArrayList<Map<String, Object>>();
        for (Object citation : list) {
            if (!(citation instanceof Map<?, ?> entry)) throw schemaError();
            var output = sources.decode(store.encode(entry));
            if (!Set.of("sourceId", "filePath", "startLine", "endLine", "explanation").containsAll(output.keySet())) throw schemaError();
            requiredText(output, "sourceId", 100); requiredText(output, "filePath", 2048);
            Object startRaw = output.get("startLine"); Object endRaw = output.get("endLine");
            if (!(startRaw instanceof Number start) || !(endRaw instanceof Number end) || start.doubleValue() != start.intValue() || end.doubleValue() != end.intValue()) throw schemaError();
            var source = allowed.get(output.get("sourceId")); String reason = "VERIFIED_SOURCE_RANGE";
            if (source == null) reason = "SOURCE_NOT_IN_CONTEXT";
            else if (!source.get("filePath").equals(output.get("filePath"))) reason = "FILE_PATH_MISMATCH";
            else if (start.intValue() < ((Number) source.get("startLine")).intValue() || end.intValue() > ((Number) source.get("endLine")).intValue() || end.intValue() < start.intValue()) reason = "OUTSIDE_CONTEXT";
            if (reason.equals("VERIFIED_SOURCE_RANGE")) {
                var current = sources.get((String) source.get("sourceId")); if (!source.get("sha256").equals(current.get("sha256"))) reason = "SOURCE_HASH_MISMATCH";
            }
            boolean verified = reason.equals("VERIFIED_SOURCE_RANGE");
            output.put("verificationStatus", verified ? "VERIFIED" : "INVALID"); output.put("verificationReason", reason);
            if (verified) { var metadata = sources.decode(store.encode(source)); metadata.remove("content"); output.put("source", metadata); }
            checked.add(output);
        }
        return checked;
    }
    private void keys(Map<String, Object> input, String... allowed) { if (!input.keySet().equals(Set.of(allowed))) throw schemaError(); }
    private void requiredKeys(Map<String, Object> input, String... required) { if (!input.keySet().containsAll(Set.of(required))) throw schemaError(); }
    private void requiredText(Map<String, Object> input, String field, int limit) {
        if (!(input.get(field) instanceof String text) || text.isBlank() || text.length() > limit) throw schemaError();
    }
    private void textArray(Object input) {
        if (!(input instanceof List<?> list) || list.size() > 20) throw schemaError();
        for (Object item : list) if (!(item instanceof String text) || text.isBlank() || text.length() > 2000) throw schemaError();
    }
    private ApiException schemaError() { return new ApiException(502, "INVALID_RESPONSE_SCHEMA", "AI 服务结果结构无效"); }
}
