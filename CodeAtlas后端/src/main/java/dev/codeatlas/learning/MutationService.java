package dev.codeatlas.learning;

import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

/** Idempotent new-domain commands retain their exact successful response. */
@Service
public class MutationService {
    private final AtlasStore store;
    private final SourceEvidenceService json;
    public MutationService(AtlasStore store, SourceEvidenceService json) { this.store = store; this.json = json; }
    public Map<String, Object> once(String key, String operation, Object body, Supplier<Map<String, Object>> work) {
        if (key == null || key.isBlank() || key.length() > 100) throw new ApiException(400, "INVALID_REQUEST", "请提供有效请求标识");
        String hash = SnapshotScanner.hash(store.encode(canonical(body)));
        try {
            return store.transaction(() -> {
                var previous = store.rows("SELECT * FROM mutation_requests WHERE idempotency_key=?", key);
                if (!previous.isEmpty()) return restore(previous.getFirst(), operation, hash);
                // Claim the key before domain locks. A concurrent retry waits here without executing the command.
                store.update("INSERT INTO mutation_requests(idempotency_key,operation,body_hash,result_id,created_at) VALUES(?,?,?,?,?)",
                        key, operation, hash, AtlasStore.uuid(), AtlasStore.now());
                var result = work.get();
                store.update("UPDATE mutation_requests SET result_id=?,response_payload=? WHERE idempotency_key=?",
                        result.get("id"), store.encode(result), key);
                return result;
            });
        } catch (DuplicateKeyException error) {
            var previous = store.rows("SELECT * FROM mutation_requests WHERE idempotency_key=?", key);
            if (previous.isEmpty()) throw error;
            return restore(previous.getFirst(), operation, hash);
        }
    }
    private Map<String, Object> restore(Map<String, Object> row, String operation, String hash) {
        if (!operation.equals(row.get("operation")) || !hash.equals(row.get("bodyHash"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "请求标识已用于不同操作或内容");
        return json.decode((String) row.get("responsePayload"));
    }
    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var ordered = new TreeMap<String, Object>(); map.forEach((key, entry) -> ordered.put(key.toString(), canonical(entry))); return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }
}
