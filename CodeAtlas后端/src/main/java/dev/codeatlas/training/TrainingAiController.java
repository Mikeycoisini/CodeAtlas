package dev.codeatlas.training;

import dev.codeatlas.ai.AiContextService;
import dev.codeatlas.learning.LearningService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static dev.codeatlas.training.TrainingDefinition.*;

@RestController
@RequestMapping("/api/training")
public class TrainingAiController {
    private final TrainingService training;
    private final TrainingDefinition definitions;
    private final AiContextService contexts;
    private final AtlasStore store;
    private final SourceEvidenceService json;
    public TrainingAiController(TrainingService training, TrainingDefinition definitions, AiContextService contexts, AtlasStore store, SourceEvidenceService json) {
        this.training = training; this.definitions = definitions; this.contexts = contexts; this.store = store; this.json = json;
    }
    @PostMapping("/ai/contexts") public Object questions(@RequestBody Map<String, Object> input) {
        fields(input, "sourceIds", "goal", "questionCount", "formats", "depth");
        int count = SourceEvidenceService.integer(input, "questionCount", 3);
        if (count < 1 || count > 10) throw invalid("AI出题数量须为1至10");
        var formats = LearningService.strings(input, "formats", 3, 40);
        if (formats.isEmpty()) formats = List.of("SINGLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER");
        if (!Set.of("SINGLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER").containsAll(formats)) throw invalid("出题格式无效");
        var sources = LearningService.strings(input, "sourceIds", 6, 36); if (sources.isEmpty()) throw invalid("请先关联出题源码");
        var parameters = Map.<String, Object>of("goal", text(input, "goal", 4000, true), "questionCount", count, "formats", formats);
        var request = Map.<String, Object>of("sourceIds", sources, "question", "请根据所选固定源码起草理解题", "depth", input.getOrDefault("depth", "STANDARD"));
        return store.transaction(() -> {
            var context = contexts.prepareTraining(request, "TRAINING_QUESTIONS", parameters);
            store.update("INSERT INTO training_ai_requests(context_id,operation,created_at) VALUES(?,'TRAINING_QUESTIONS',?)", context.get("id"), AtlasStore.now());
            return context;
        });
    }
    @PostMapping("/ai/generations/{id}/task")
    public Object createTask(@PathVariable String id, @RequestBody Map<String, Object> input) {
        fields(input, "idempotencyKey");
        String key = text(input, "idempotencyKey", 100, true);
        var generation = store.one("SELECT * FROM ai_generations WHERE id=? AND status='SUCCEEDED'", id);
        var context = json.decode((String) store.one("SELECT context_json FROM ai_contexts WHERE id=?", generation.get("contextId")).get("contextJson"));
        if (!"TRAINING_QUESTIONS".equals(context.get("operation"))) throw invalid("该生成记录不是出题结果");
        var result = json.decode((String) generation.get("resultJson"));
        var sourceIds = ((List<?>) context.get("sources")).stream().map(source -> map(source).get("sourceId")).toList();
        var body = new LinkedHashMap<String, Object>();
        for (String field : List.of("title", "goal", "constraints", "acceptanceCriteria")) body.put(field, result.get(field));
        body.put("questions", result.get("questions"));
        body.put("sourceIds", sourceIds);
        body.put("generationId", id);
        body.put("status", "DRAFT");
        body.put("tags", List.of("AI"));
        body.put("idempotencyKey", key);
        return training.createTask(body);
    }
    @PostMapping("/attempts/{id}/questions/{questionId}/ai-context") public Object feedback(@PathVariable String id, @PathVariable String questionId, @RequestBody Map<String, Object> input) {
        fields(input, "question", "sourceIds", "depth");
        var attempt = training.rawAttempt(id);
        if (!"SUBMITTED".equals(attempt.get("status"))) throw new ApiException(409, "ATTEMPT_NOT_SUBMITTED", "提交回答后可请求AI辅助反馈");
        var frozen = training.frozen(attempt); var question = new LinkedHashMap<>(definitions.question(frozen, questionId)); question.remove("hints");
        var allowedSources = (List<?>) frozen.get("sourceIds");
        var selected = input.containsKey("sourceIds") ? LearningService.strings(input, "sourceIds", 6, 36) : allowedSources;
        if (selected.isEmpty() || !allowedSources.containsAll(selected)) throw invalid("反馈来源须属于本次冻结任务");
        var answers = (List<?>) json.decode((String) attempt.get("answersPayload")).get("items");
        var results = (List<?>) json.decode((String) attempt.get("resultsPayload")).get("items");
        var answer = answers.stream().map(TrainingDefinition::map).filter(item -> questionId.equals(item.get("questionId"))).findFirst().orElseThrow(() -> invalid("提交记录缺少该题回答"));
        var result = results.stream().map(TrainingDefinition::map).filter(item -> questionId.equals(item.get("questionId"))).findFirst().orElseThrow(() -> invalid("提交记录缺少该题结果"));
        var parameters = new LinkedHashMap<String, Object>();
        for (String field : List.of("taskId", "taskRevision", "definitionDigest", "submissionDigest")) parameters.put(field, attempt.get(field));
        parameters.put("attemptId", id); parameters.put("question", question); parameters.put("answer", answer); parameters.put("resultAtSubmission", result); parameters.put("goal", frozen.get("goal"));
        String message = text(input, "question", 2000, false); if (message.isBlank()) message = "请针对这道题的冻结提交提供源码理解辅助反馈";
        var request = Map.<String, Object>of("sourceIds", selected, "question", message, "depth", input.getOrDefault("depth", "STANDARD"));
        return store.transaction(() -> {
            var context = contexts.prepareTraining(request, "TRAINING_FEEDBACK", parameters);
            store.update("INSERT INTO training_ai_requests(context_id,operation,task_id,task_revision,attempt_id,question_id,created_at) VALUES(?,'TRAINING_FEEDBACK',?,?,?,?,?)",
                    context.get("id"), attempt.get("taskId"), attempt.get("taskRevision"), id, questionId, AtlasStore.now());
            return context;
        });
    }
}
