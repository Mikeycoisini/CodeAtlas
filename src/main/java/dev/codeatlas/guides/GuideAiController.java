package dev.codeatlas.guides;

import dev.codeatlas.ai.AiContextService;
import dev.codeatlas.learning.LearningService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.progress.EvidenceService;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.*;
import static dev.codeatlas.review.ReviewDefinitions.*;

@RestController
@RequestMapping("/api/guides")
public class GuideAiController {
    private final GuideService guides;
    private final EvidenceService evidence;
    private final AiContextService contexts;
    private final AtlasStore store;
    private final SourceEvidenceService json;
    private final Clock clock;
    public GuideAiController(GuideService guides, EvidenceService evidence, AiContextService contexts, AtlasStore store, SourceEvidenceService json, Clock clock) {
        this.guides = guides; this.evidence = evidence; this.contexts = contexts; this.store = store; this.json = json; this.clock = clock;
    }
    @PostMapping("/{id}/ai-context") public Object prepare(@PathVariable String id, @RequestBody Map<String, Object> input) {
        fields(input, "revision", "sourceIds", "knowledgeIds", "depth");
        var sourceIds = LearningService.strings(input, "sourceIds", 6, 36); var knowledgeIds = LearningService.strings(input, "knowledgeIds", 10, 36);
        return store.transaction(() -> {
            store.one("SELECT id FROM guide_runs WHERE id=? FOR UPDATE", id);
            // All evidence writers lock the knowledge record. Acquire selected records in a stable order before snapshot reads.
            for (String knowledgeId : new TreeSet<>(knowledgeIds)) {
                store.one("SELECT id FROM learning_records WHERE id=? AND kind='KNOWLEDGE' AND deleted_at IS NULL FOR UPDATE", knowledgeId);
            }
            var guide = guides.get(id);
            revision(guide, input);
            if (!((List<?>) guide.get("sourceIds")).containsAll(sourceIds) || !((List<?>) guide.get("knowledgeIds")).containsAll(knowledgeIds)) {
                throw new ApiException(400, "GUIDE_SCOPE_MISMATCH", "请选择当前引导已关联的资料");
            }
            var summaries = new ArrayList<Map<String, Object>>();
            for (String knowledgeId : knowledgeIds) {
                var row = store.one("SELECT id,title,payload,revision FROM learning_records WHERE id=? AND kind='KNOWLEDGE' AND deleted_at IS NULL", knowledgeId);
                var content = json.decode((String) row.get("payload"));
                String body = (String) content.get("body");
                summaries.add(Map.of("id", knowledgeId, "title", row.get("title"), "revision", row.get("revision"),
                        "bodyExcerpt", body.substring(0, Math.min(1000, body.length())), "bodyTruncated", body.length() > 1000,
                        "evidenceSummary", evidence.knowledge(knowledgeId).get("assessment")));
            }
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("guideId", id);
            snapshot.put("revision", guide.get("revision"));
            snapshot.put("goal", guide.get("goal"));
            snapshot.put("steps", ((List<?>) guide.get("steps")).stream().map(raw -> {
                @SuppressWarnings("unchecked") var step = (Map<String, Object>) raw;
                return Map.of("id", step.get("id"), "title", step.get("title"), "status", step.get("status"));
            }).toList());
            snapshot.put("knowledge", summaries);
            var request = Map.<String, Object>of("sourceIds", sourceIds, "question", "请为当前学习目标提供六个已有步骤的顺序与学习方法建议", "depth", input.getOrDefault("depth", "STANDARD"));
            var context = contexts.prepareGuide(request, snapshot);
            store.update("INSERT INTO guide_ai_requests(context_id,guide_id,guide_revision,created_at_ms) VALUES(?,?,?,?)", context.get("id"), id, guide.get("revision"), clock.millis());
            return context;
        });
    }
}
