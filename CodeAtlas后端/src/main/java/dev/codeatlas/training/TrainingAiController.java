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
    private final LearningService learning;
    private final TrainingService training;
    private final TrainingDefinition definitions;
    private final AiContextService contexts;
    private final AtlasStore store;
    private final SourceEvidenceService json;
    public TrainingAiController(TrainingService training, TrainingDefinition definitions, AiContextService contexts, AtlasStore store, SourceEvidenceService json, LearningService learning) {
        this.training = training; this.definitions = definitions; this.contexts = contexts; this.store = store; this.json = json; this.learning=learning;
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
    @PostMapping("/ai/generations/{id}/learning-pack")
    public Object adoptPack(@PathVariable String id,@RequestBody Map<String,Object> input) {
        fields(input,"idempotencyKey"); text(input,"idempotencyKey",100,true);
        return store.transaction(() -> {
            // This durable lock serializes all retries, even with a different browser key.
            var generation=store.one("SELECT * FROM ai_generations WHERE id=? AND status='SUCCEEDED' FOR UPDATE",id);
            var context=json.decode((String)store.one("SELECT context_json FROM ai_contexts WHERE id=?",generation.get("contextId")).get("contextJson"));
            if(!"TRAINING_QUESTIONS".equals(context.get("operation"))) throw invalid("该生成记录不是出题结果");
            var sourceIds=((List<?>)context.get("sources")).stream().map(x->map(x).get("sourceId")).toList();
            dev.codeatlas.learning.ProjectScope.owner(store,sourceIds,null);
            var previous=store.rows("SELECT payload FROM learning_pack_adoptions WHERE generation_id=?",id);
            if(!previous.isEmpty()) return json.decode((String)previous.getFirst().get("payload"));
            var result=json.decode((String)generation.get("resultJson"));
            var points=result.get("knowledge") instanceof List<?> list && !list.isEmpty()?list:List.of(Map.of("title",result.get("title"),"body",result.get("goal"),"analysis",result.get("acceptanceCriteria")));
            var knowledgeIds=new ArrayList<String>(); var cardIds=new ArrayList<String>();
            int index=0;
            for(Object raw:points) {
                var point=map(raw); String suffix=id+":"+index++;
                String title=String.valueOf(point.getOrDefault("title",result.get("title")));
                String body=String.valueOf(point.getOrDefault("body",result.get("goal")));
                if(body.isBlank()) body=title;
                String code=String.valueOf(point.getOrDefault("code",""));
                // Never use model-authored text as original source, including old generations.
                String source=String.valueOf(point.getOrDefault("sourceId",sourceIds.getFirst()));
                if(!sourceIds.contains(source)) source=(String)sourceIds.getFirst();
                var evidence=json.get(source);
                code=(String)evidence.get("content");
                if(code.length()>16000) code=code.substring(0,16000);
                String analysis=String.valueOf(point.getOrDefault("analysis",""));
                var knowledge=learning.create("KNOWLEDGE",Map.of("title",title,"body",body,"type","AI_PROJECT","generationId",id,"sourceIds",List.of(source),"tags",List.of("AI"),"idempotencyKey","pack-knowledge:"+suffix));
                knowledgeIds.add((String)knowledge.get("id"));
                var card=new LinkedHashMap<String,Object>();
                card.put("question","请结合源码说明："+title);card.put("answer",body);card.put("explanation",analysis);card.put("codeExample",code);
                card.put("knowledgeId",knowledge.get("id"));card.put("generationId",id);card.put("sourceIds",List.of(source));card.put("tags",List.of("AI"));card.put("idempotencyKey","pack-card:"+suffix);
                cardIds.add((String)learning.create("CARD",card).get("id"));
            }
            var task=map(createTask(id,Map.of("idempotencyKey","pack-task:"+id)));
            var saved=Map.<String,Object>of("trainingId",task.get("id"),"knowledge",knowledgeIds.size(),"cards",cardIds.size(),"knowledgeIds",knowledgeIds,"cardIds",cardIds);
            store.update("INSERT INTO learning_pack_adoptions(generation_id,payload,created_at) VALUES(?,?,?)",id,store.encode(saved),AtlasStore.now());
            return saved;
        });
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
