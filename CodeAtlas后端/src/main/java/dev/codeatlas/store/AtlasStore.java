package dev.codeatlas.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeatlas.analysis.JavaAnalyzer;
import dev.codeatlas.snapshot.SnapshotScanner.CapturedSnapshot;
import dev.codeatlas.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class AtlasStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public AtlasStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    public <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    public static String uuid() { return UUID.randomUUID().toString(); }
    public static String now() { return Instant.now().toString(); }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (result, index) -> {
            var row = new LinkedHashMap<String, Object>();
            var metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String column = metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);
                String[] parts = column.split("_");
                StringBuilder key = new StringBuilder(parts[0]);
                for (int p = 1; p < parts.length; p++) key.append(Character.toUpperCase(parts[p].charAt(0))).append(parts[p].substring(1));
                row.put(key.toString(), result.getObject(i));
            }
            return row;
        }, args);
    }
    public Map<String, Object> one(String sql, Object... args) {
        var result = rows(sql, args);
        if (result.isEmpty()) throw ApiException.missing();
        return result.getFirst();
    }
    public void update(String sql, Object... args) { jdbc.update(sql, args); }
    public void scanPayloads(String sql, java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> visitor, Object... args) {
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) result -> {
            try {
                var value = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(result.getString("payload"));
                value.put("id", result.getString("id"));
                visitor.accept(value);
            } catch (java.io.IOException error) { throw new IllegalStateException("索引数据格式错误", error); }
        }, args);
    }
    public Map<String, Object> task(String id) { return one("SELECT * FROM tasks WHERE id=?", id); }
    public Map<String, Object> snapshot(String id) { return one("SELECT * FROM snapshots WHERE id=?", id); }
    public List<Map<String, Object>> files(String snapshot) { snapshot(snapshot); return rows("SELECT * FROM snapshot_files WHERE snapshot_id=? ORDER BY path", snapshot); }

    public void recover() {
        update("UPDATE tasks SET status='FAILED',error_code='PROCESS_INTERRUPTED',message='服务中断，请重试',updated_at=? WHERE status IN ('QUEUED','RUNNING')", now());
    }

    public Map<String, Object> createImport(String path, String name) {
        return createImport(path, name, "LOCAL", null);
    }

    public Map<String, Object> createImport(String path, String name, String sourceType, String sourceRef) {
        return createImport(path, name, sourceType, sourceRef, null);
    }

    public Map<String, Object> createImport(String path, String name, String sourceType, String sourceRef, String credentialId) {
        return transaction(() -> {
            String hash = dev.codeatlas.snapshot.SnapshotScanner.hash(path.toLowerCase(Locale.ROOT));
            var projects = rows("SELECT * FROM projects WHERE path_hash=?", hash);
            if (projects.isEmpty()) {
                update("INSERT INTO projects(id,name,source_path,path_hash,source_type,created_at) VALUES(?,?,?,?,?,?)",
                        uuid(), name, path, hash, sourceType, now());
            }
            one("SELECT * FROM projects WHERE path_hash=? FOR UPDATE", hash);
            var active = rows("SELECT * FROM tasks WHERE kind='IMPORT' AND source_path=? AND source_type=? AND status IN ('QUEUED','RUNNING')", path, sourceType);
            if (!active.isEmpty()) return active.getFirst();
            String id = uuid();
            insertTask(id, "IMPORT", path, null, null, sourceType, sourceRef, credentialId);
            return task(id);
        });
    }

    private void insertTask(String id, String kind, String source, String snapshot, String generation) {
        insertTask(id, kind, source, snapshot, generation, "LOCAL", null, null);
    }

    private void insertTask(String id, String kind, String source, String snapshot, String generation, String sourceType, String sourceRef) {
        insertTask(id, kind, source, snapshot, generation, sourceType, sourceRef, null);
    }

    private void insertTask(String id, String kind, String source, String snapshot, String generation, String sourceType, String sourceRef, String credentialId) {
        update("INSERT INTO tasks(id,kind,source_path,source_type,source_ref,credential_id,snapshot_id,generation_id,status,analyzer_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'QUEUED',?,?,?)",
                id, kind, source, sourceType, sourceRef, credentialId, snapshot, generation, JavaAnalyzer.VERSION, now(), now());
    }

    public Map<String, Object> createIndex(String snapshotId, boolean rebuild) {
        return transaction(() -> {
            one("SELECT * FROM snapshots WHERE id=? FOR UPDATE", snapshotId);
            var active = rows("SELECT * FROM tasks WHERE snapshot_id=? AND kind='INDEX' AND status IN ('QUEUED','RUNNING') ORDER BY created_at DESC", snapshotId);
            if (!active.isEmpty()) return active.getFirst();
            if (!rebuild) {
                var previous = rows("SELECT * FROM tasks WHERE snapshot_id=? AND kind='INDEX' AND analyzer_version=? AND status IN ('SUCCEEDED','PARTIAL_SUCCESS') ORDER BY created_at DESC", snapshotId, JavaAnalyzer.VERSION);
                if (!previous.isEmpty()) return previous.getFirst();
            }
            String id = uuid();
            String generation = uuid();
            insertTask(id, "INDEX", null, snapshotId, generation);
            update("INSERT INTO generations(id,snapshot_id,task_id,created_at) VALUES(?,?,?,?)", generation, snapshotId, id, now());
            return task(id);
        });
    }

    public boolean cancelled(String id) {
        var task = task(id);
        return Boolean.TRUE.equals(task.get("cancelRequested")) || "CANCELLED".equals(task.get("status"));
    }
    public Map<String, Object> cancel(String id) {
        return transaction(() -> {
            var task = one("SELECT * FROM tasks WHERE id=? FOR UPDATE", id);
            if (List.of("QUEUED", "RUNNING").contains(task.get("status"))) {
                update("UPDATE tasks SET cancel_requested=TRUE,updated_at=? WHERE id=?", now(), id);
            }
            return task(id);
        });
    }
    public void running(String id, int total) {
        update("UPDATE tasks SET status='RUNNING',total=?,updated_at=? WHERE id=? AND status='QUEUED'", total, now(), id);
    }
    public void progress(String id, int processed) { update("UPDATE tasks SET progress=?,updated_at=? WHERE id=?", processed, now(), id); }
    public void failure(String id, String state, String code, String message) {
        update("UPDATE tasks SET status=?,error_code=?,message=?,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')", state, code, message, now(), id);
    }

    public String completeImport(String taskId, CapturedSnapshot capture) {
        return transaction(() -> {
            var task = one("SELECT * FROM tasks WHERE id=? FOR UPDATE", taskId);
            if (Boolean.TRUE.equals(task.get("cancelRequested"))) return null;
            var project = one("SELECT * FROM projects WHERE source_path=? FOR UPDATE", task.get("sourcePath"));
            var existing = rows("SELECT * FROM snapshots WHERE project_id=? AND identity_hash=?", project.get("id"), capture.identityHash());
            String snapshotId;
            if (!existing.isEmpty()) snapshotId = (String) existing.getFirst().get("id");
            else {
                snapshotId = uuid();
                update("INSERT INTO snapshots(id,project_id,manifest_hash,identity_hash,source_kind,commit_hash,git_status,storage_key,policy_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        snapshotId, project.get("id"), capture.manifestHash(), capture.identityHash(), capture.sourceKind(), capture.commitHash(), capture.gitStatus(),
                        capture.root().getFileName().toString(), dev.codeatlas.snapshot.SnapshotScanner.POLICY, now());
                for (var file : capture.files()) {
                    update("INSERT INTO snapshot_files(snapshot_id,id,path,sha256,byte_size) VALUES(?,?,?,?,?)", snapshotId, file.id(), file.path(), file.sha256(), file.byteSize());
                }
            }
            update("UPDATE tasks SET snapshot_id=?,status='SUCCEEDED',total=?,progress=?,updated_at=? WHERE id=?", snapshotId, capture.files().size(), capture.files().size(), now(), taskId);
            return snapshotId;
        });
    }

    public void saveAnalysis(String taskId, JavaAnalyzer.Analysis analysis) {
        transaction(() -> {
            var task = one("SELECT * FROM tasks WHERE id=? FOR UPDATE", taskId);
            if (Boolean.TRUE.equals(task.get("cancelRequested"))) {
                failure(taskId, "CANCELLED", "CANCELLED", "索引已取消");
                return null;
            }
            String generation = (String) task.get("generationId");
            update("DELETE FROM relations WHERE generation_id=?", generation);
            update("DELETE FROM symbols WHERE generation_id=?", generation);
            update("DELETE FROM diagnostics WHERE generation_id=?", generation);
            for (var symbol : analysis.symbols()) update("INSERT INTO symbols(generation_id,id,file_id,name,payload) VALUES(?,?,?,?,?)",
                    generation, symbol.id(), symbol.fileId(), symbol.name(), encode(symbol));
            for (var relation : analysis.relations()) update("INSERT INTO relations(id,generation_id,source_id,target_id,payload) VALUES(?,?,?,?,?)",
                    uuid(), generation, relation.sourceId(), relation.targetId(), encode(relation));
            for (var diagnostic : analysis.diagnostics()) update("INSERT INTO diagnostics(id,generation_id,file_id,payload) VALUES(?,?,?,?)",
                    uuid(), generation, diagnostic.fileId(), encode(diagnostic));
            String state = analysis.diagnostics().isEmpty() ? "SUCCEEDED" : analysis.successfulFiles() > 0 ? "PARTIAL_SUCCESS" : "FAILED";
            update("UPDATE tasks SET status=?,message=?,progress=?,total=?,updated_at=? WHERE id=?", state,
                    analysis.totalFiles() == 0 ? "未发现 Java 文件" : analysis.symbols().size() + " 个符号，" + analysis.diagnostics().size() + " 个文件诊断",
                    analysis.totalFiles(), analysis.totalFiles(), now(), taskId);
            if (!state.equals("FAILED")) {
                update("UPDATE generations SET published=TRUE WHERE id=?", generation);
                update("UPDATE snapshots SET published_generation=? WHERE id=?", generation, task.get("snapshotId"));
            }
            return null;
        });
    }

    public void appendDraft(String taskId, JavaAnalyzer.Analysis partial) {
        transaction(() -> {
            var task = one("SELECT * FROM tasks WHERE id=? FOR UPDATE", taskId);
            if (Boolean.TRUE.equals(task.get("cancelRequested"))) throw new java.util.concurrent.CancellationException();
            String generation = (String) task.get("generationId");
            for (var symbol : partial.symbols()) update("INSERT INTO symbols(generation_id,id,file_id,name,payload) VALUES(?,?,?,?,?)",
                    generation, symbol.id(), symbol.fileId(), symbol.name(), encode(symbol));
            for (var diagnostic : partial.diagnostics()) update("INSERT INTO diagnostics(id,generation_id,file_id,payload) VALUES(?,?,?,?)",
                    uuid(), generation, diagnostic.fileId(), encode(diagnostic));
            return null;
        });
    }

    public String generation(String snapshot, String requested) {
        var row = snapshot(snapshot);
        String id = requested == null || requested.isBlank() ? (String) row.get("publishedGeneration") : requested;
        if (id != null) one("SELECT id FROM generations WHERE id=? AND snapshot_id=? AND published=TRUE", id, snapshot);
        return id;
    }
    public List<Object> payloads(String sql, Object... args) {
        return jdbc.query(sql, (result, index) -> {
            try { return json.readTree(result.getString("payload")); }
            catch (Exception e) { throw new IllegalStateException("索引数据格式错误", e); }
        }, args);
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
