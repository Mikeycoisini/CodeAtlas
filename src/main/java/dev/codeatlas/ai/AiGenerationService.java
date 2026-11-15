package dev.codeatlas.ai;

import dev.codeatlas.learning.LearningService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AiGenerationService {
    private final AtlasStore store;
    private final SourceEvidenceService sources;
    private final AiClient client;
    private final AiResultValidator results;
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
        var thread = new Thread(runnable, "codeatlas-explanations"); thread.setDaemon(true); return thread;
    });

    public AiGenerationService(AtlasStore store, SourceEvidenceService sources, AiClient client, AiResultValidator results) { this.store = store; this.sources = sources; this.client = client; this.results = results; }
    @PostConstruct public void recover() {
        store.update("UPDATE ai_generations SET status='FAILED',error_code='PROCESS_INTERRUPTED',message='服务中断，请重新预览并授权',updated_at=? WHERE status IN ('QUEUED','RUNNING')", AtlasStore.now());
    }
    @PreDestroy public void close() throws InterruptedException {
        worker.shutdownNow(); scheduled.forEach(client::cancel); worker.awaitTermination(5, TimeUnit.SECONDS);
    }

    public Map<String, Object> get(String id) {
        var row = store.one("SELECT * FROM ai_generations WHERE id=?", id);
        var result = new LinkedHashMap<>(row);
        result.remove("bodyHash"); result.remove("resultJson");
        result.put("result", row.get("resultJson") == null ? null : sources.decode((String) row.get("resultJson")));
        var context = store.one("SELECT provider_json,context_json FROM ai_contexts WHERE id=?", row.get("contextId"));
        result.put("provider", sources.decode((String) context.get("providerJson")));
        result.put("operation", sources.decode((String) context.get("contextJson")).getOrDefault("operation", "EXPLANATION"));
        return result;
    }

    public Map<String, Object> create(Map<String, Object> input) {
        if (!Set.of("consentId", "idempotencyKey").containsAll(input.keySet())) throw new ApiException(400, "INVALID_REQUEST", "生成请求只接受授权身份与幂等键");
        String key = LearningService.text(input, "idempotencyKey", 100, true);
        String consent = LearningService.text(input, "consentId", 36, true);
        String hash = SnapshotScanner.hash(consent);
        String id;
        try {
            id = store.transaction(() -> {
                var previous = store.rows("SELECT * FROM ai_generations WHERE idempotency_key=?", key);
                if (!previous.isEmpty()) return previous(key, hash);
                var grant = store.one("SELECT * FROM ai_consents WHERE id=? FOR UPDATE", consent);
                if (!"ALLOW".equals(grant.get("decision"))) throw new ApiException(403, "CONSENT_DENIED", "该上下文已拒绝外发");
                var context = store.one("SELECT * FROM ai_contexts WHERE id=?", grant.get("contextId"));
                AiContextService.fresh(context);
                if (!store.rows("SELECT id FROM ai_generations WHERE consent_id=?", consent).isEmpty()) throw new ApiException(409, "CONSENT_ALREADY_USED", "此授权已绑定生成请求，请重新预览");
                String generated = AtlasStore.uuid();
                store.update("INSERT INTO ai_generations(id,context_id,consent_id,idempotency_key,body_hash,status,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED',?,?)",
                        generated, context.get("id"), consent, key, hash, AtlasStore.now(), AtlasStore.now());
                return generated;
            });
        } catch (DuplicateKeyException e) {
            if (store.rows("SELECT id FROM ai_generations WHERE idempotency_key=?", key).isEmpty()) throw new ApiException(409, "CONSENT_ALREADY_USED", "授权已被另一请求使用");
            id = previous(key, hash);
        }
        enqueue(id);
        return get(id);
    }

    private String previous(String key, String hash) {
        var row = store.one("SELECT id,body_hash FROM ai_generations WHERE idempotency_key=?", key);
        if (!hash.equals(row.get("bodyHash"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于不同生成请求");
        return (String) row.get("id");
    }
    private void enqueue(String id) {
        if (!"QUEUED".equals(get(id).get("status")) || !scheduled.add(id)) return;
        try { worker.execute(() -> execute(id)); }
        catch (RejectedExecutionException e) {
            scheduled.remove(id); fail(id, "TASK_CAPACITY_EXCEEDED", "生成队列已满，请稍后重新准备");
            throw new ApiException(503, "TASK_CAPACITY_EXCEEDED", "生成队列已满，请稍后重新准备");
        }
    }
    public Map<String, Object> cancel(String id) {
        store.transaction(() -> {
            var row = store.one("SELECT status FROM ai_generations WHERE id=? FOR UPDATE", id);
            if (Set.of("QUEUED", "RUNNING").contains(row.get("status"))) {
                client.cancel(id);
                store.update("UPDATE ai_generations SET status='CANCELLED',error_code='CANCELLED',message='已取消，外发后的计算可能继续',updated_at=? WHERE id=?", AtlasStore.now(), id);
            }
            return null;
        });
        return get(id);
    }

    private void execute(String id) {
        try {
            if (!"QUEUED".equals(get(id).get("status"))) return;
            var provider = client.provider();
            var context = store.transaction(() -> {
                var row = store.one("SELECT * FROM ai_generations WHERE id=? FOR UPDATE", id);
                if (!"QUEUED".equals(row.get("status"))) return null;
                var grant = store.one("SELECT * FROM ai_consents WHERE id=?", row.get("consentId"));
                if (!"ALLOW".equals(grant.get("decision"))) throw new ApiException(403, "CONSENT_DENIED", "授权已拒绝");
                var prepared = store.one("SELECT * FROM ai_contexts WHERE id=?", row.get("contextId"));
                AiContextService.fresh(prepared);
                if (!prepared.get("providerFingerprint").equals(provider.get("fingerprint"))) throw new ApiException(409, "CONSENT_SCOPE_CHANGED", "模型配置已变化，请重新预览并授权");
                if (!Boolean.TRUE.equals(provider.get("configured"))) throw new ApiException(503, "PROVIDER_NOT_CONFIGURED", "模型密钥未配置");
                if (!SnapshotScanner.hash((String) prepared.get("contextJson")).equals(prepared.get("contextDigest"))) throw new ApiException(409, "CONTEXT_DIGEST_MISMATCH", "上下文摘要不匹配");
                store.update("UPDATE ai_generations SET status='RUNNING',outbound_started_at=?,updated_at=? WHERE id=?", AtlasStore.now(), AtlasStore.now(), id);
                return prepared;
            });
            if (context == null || "CANCELLED".equals(get(id).get("status"))) return;
            var request = Map.<String, Object>of("requestId", id, "contextJson", context.get("contextJson"), "contextDigest", context.get("contextDigest"),
                    "providerFingerprint", context.get("providerFingerprint"), "expiresAt", context.get("expiresAt"), "operation", sources.decode((String) context.get("contextJson")).getOrDefault("operation", "EXPLANATION"));
            var response = client.generate(id, request);
            var result = results.validate(response, (String) context.get("contextJson"));
            store.transaction(() -> {
                var row = store.one("SELECT status FROM ai_generations WHERE id=? FOR UPDATE", id);
                if ("RUNNING".equals(row.get("status"))) store.update("UPDATE ai_generations SET status='SUCCEEDED',result_json=?,message='解释已生成，请核对引用与业务含义',updated_at=? WHERE id=?", store.encode(result), AtlasStore.now(), id);
                return null;
            });
        } catch (ApiException e) { fail(id, e.code, e.getMessage()); }
        catch (Exception e) { fail(id, "GENERATION_ERROR", "生成处理失败，请重新预览后重试"); }
        finally { scheduled.remove(id); client.release(id); }
    }

    private void fail(String id, String code, String message) {
        store.update("UPDATE ai_generations SET status='FAILED',error_code=?,message=?,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')", code, message, AtlasStore.now(), id);
    }

}
