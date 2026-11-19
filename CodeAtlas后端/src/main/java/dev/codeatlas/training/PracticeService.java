package dev.codeatlas.training;

import dev.codeatlas.learning.MutationService;
import dev.codeatlas.learning.SourceEvidenceService;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.tasks.TaskService;
import dev.codeatlas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

@Service
public class PracticeService {
    private final AtlasStore store;
    private final MutationService mutations;
    private final SourceEvidenceService json;
    private final TaskService tasks;
    private final Path root;
    public PracticeService(AtlasStore store, MutationService mutations, SourceEvidenceService json, TaskService tasks,
                           @Value("${codeatlas.data}") String data) {
        this.store = store; this.mutations = mutations; this.json = json; this.tasks = tasks;
        this.root = Path.of(data).toAbsolutePath().normalize().resolve("practice");
    }
    private static String required(Map<String, Object> input, String field) {
        if (!(input.get(field) instanceof String value) || value.isBlank()) throw new ApiException(400, "INVALID_REQUEST", field + " 必填");
        return value;
    }
    private static String bounded(Map<String, Object> input, String field, int limit) {
        String value = required(input, field);
        if (value.length() > limit) throw new ApiException(400, "INVALID_REQUEST", field + " 超长");
        return value;
    }
    private static void fields(Map<String, Object> input, String... allowed) {
        if (!Set.of(allowed).containsAll(input.keySet())) throw new ApiException(400, "INVALID_REQUEST", "请求包含未允许的字段");
    }
    private static String time() { return Instant.now().toString(); }
    private Path path(String key) {
        if (!key.matches("(?:workspace|submission)-[0-9a-f-]{36}")) throw new ApiException(500, "STORAGE_ERROR", "练习目录无效");
        Path path = root.resolve(key).normalize();
        if (!path.getParent().equals(root)) throw new ApiException(500, "STORAGE_ERROR", "练习目录越界");
        return path;
    }
    public Map<String, Object> create(Map<String, Object> input) {
        fields(input, "taskId", "snapshotId", "idempotencyKey");
        String taskId = required(input, "taskId"), snapshotId = required(input, "snapshotId");
        return mutations.once(required(input, "idempotencyKey"), "PRACTICE_CREATE", Map.of("taskId", taskId, "snapshotId", snapshotId), () -> {
            var task = store.one("SELECT * FROM training_tasks WHERE id=? AND deleted_at IS NULL FOR UPDATE", taskId);
            if ("ARCHIVED".equals(task.get("status"))) throw new ApiException(409, "TASK_ARCHIVED", "已归档任务不能创建练习副本");
            int revision = ((Number) task.get("revision")).intValue();
            var version = json.decode((String) store.one("SELECT payload FROM training_task_revisions WHERE task_id=? AND revision=?", taskId, revision).get("payload"));
            var sources = (List<?>) version.get("sources");
            if (sources.isEmpty() || sources.stream().map(TrainingDefinition::map).anyMatch(source -> !snapshotId.equals(source.get("snapshotId"))))
                throw new ApiException(400, "SOURCE_MISMATCH", "任务来源与指定快照不匹配");
            store.snapshot(snapshotId);
            String id = AtlasStore.uuid(), key = "workspace-" + id;
            Path destination = path(key), original = tasks.snapshotRoot(snapshotId);
            try {
                Files.createDirectories(root);
                Files.createDirectory(destination);
                for (var file : store.files(snapshotId)) {
                    String relative = (String) file.get("path");
                    Path target = checked(destination, relative);
                    byte[] bytes = read(checked(original, relative));
                    if (!SnapshotScanner.hash(bytes).equals(file.get("sha256"))) throw new ApiException(409, "SNAPSHOT_CORRUPTED", "快照文件校验失败");
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                }
                store.update("INSERT INTO practice_workspaces(id,task_id,task_revision,snapshot_id,path_key,status,created_at) VALUES(?,?,?,?,?,'ACTIVE',?)",
                        id, taskId, revision, snapshotId, key, time());
                return workspace(id);
            } catch (Exception error) {
                try { remove(destination); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw failure(error);
            }
        });
    }
    public Map<String, Object> workspace(String id) {
        var row = new LinkedHashMap<>(store.one("SELECT * FROM practice_workspaces WHERE id=?", id));
        row.put("path", path((String) row.remove("pathKey")).toString());
        row.put("submissions", store.rows("SELECT id,sequence_no,content_digest,submitted_at FROM practice_submissions WHERE workspace_id=? ORDER BY sequence_no", id));
        return row;
    }
    public List<Map<String, Object>> workspaces(String taskId) {
        store.one("SELECT id FROM training_tasks WHERE id=?", taskId);
        return store.rows("SELECT id FROM practice_workspaces WHERE task_id=? ORDER BY created_at DESC,id", taskId).stream()
                .map(row -> workspace((String) row.get("id"))).toList();
    }
    public Map<String, Object> submit(String id, Map<String, Object> input) {
        fields(input, "idempotencyKey");
        return mutations.once(required(input, "idempotencyKey"), "PRACTICE_SUBMIT", Map.of("workspaceId", id), () -> {
            var workspace = store.one("SELECT * FROM practice_workspaces WHERE id=? FOR UPDATE", id);
            if (!"ACTIVE".equals(workspace.get("status"))) throw new ApiException(409, "WORKSPACE_CLOSED", "练习副本已关闭");
            String submissionId = AtlasStore.uuid(), key = "submission-" + submissionId;
            Path destination = path(key);
            try {
                Files.createDirectory(destination);
                var current = capture(path((String) workspace.get("pathKey")), destination);
                var baseline = new TreeMap<String, String>();
                for (var file : store.files((String) workspace.get("snapshotId"))) baseline.put((String) file.get("path"), (String) file.get("sha256"));
                var changes = new ArrayList<Map<String, Object>>();
                var paths = new TreeSet<String>(); paths.addAll(baseline.keySet()); paths.addAll(current.keySet());
                int previewRemaining = 1_000_000;
                for (String relative : paths) {
                    String before = baseline.get(relative), after = current.get(relative);
                    if (Objects.equals(before, after)) continue;
                    var change = new LinkedHashMap<String, Object>(); change.put("path", relative); change.put("beforeSha256", before); change.put("afterSha256", after);
                    byte[] oldBytes = before == null ? new byte[0] : read(checked(tasks.snapshotRoot((String) workspace.get("snapshotId")), relative));
                    byte[] newBytes = after == null ? new byte[0] : read(checked(destination, relative));
                    String diff = textDiff(oldBytes, newBytes);
                    int length = Math.min(previewRemaining, diff.length());
                    change.put("diff", length == diff.length() ? diff : diff.substring(0, length) + "\n[preview truncated; exact files retained]");
                    previewRemaining -= length; changes.add(change);
                }
                String manifest = store.encode(current), digest = SnapshotScanner.hash(manifest);
                var latest = store.rows("SELECT sequence_no FROM practice_submissions WHERE workspace_id=? ORDER BY sequence_no DESC LIMIT 1 FOR UPDATE", id);
                int sequence = latest.isEmpty() ? 1 : ((Number) latest.getFirst().get("sequenceNo")).intValue() + 1;
                store.update("INSERT INTO practice_submissions(id,workspace_id,sequence_no,content_digest,manifest_json,diff_json,path_key,submitted_at) VALUES(?,?,?,?,?,?,?,?)",
                        submissionId, id, sequence, digest, store.encode(Map.of("files", current)), store.encode(Map.of("items", changes)), key, time());
                return submission(submissionId);
            } catch (Exception error) {
                try { remove(destination); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw failure(error);
            }
        });
    }
    public Map<String, Object> submission(String id) {
        var row = new LinkedHashMap<>(store.one("SELECT * FROM practice_submissions WHERE id=?", id));
        var manifest = json.decode((String) row.remove("manifestJson")).get("files");
        try {
            if (!capture(path((String) row.get("pathKey")), null).equals(manifest))
                throw new ApiException(409, "SUBMISSION_CORRUPTED", "提交存档与内容标识不一致");
        } catch (IOException error) { throw failure(error); }
        row.put("manifest", manifest);
        row.put("changes", json.decode((String) row.remove("diffJson")).get("items"));
        row.put("archivePath", path((String) row.remove("pathKey")).toString());
        row.put("testResults", store.rows("SELECT * FROM practice_test_results WHERE submission_id=? ORDER BY reported_at,id", id));
        return row;
    }
    public Map<String, Object> report(String id, Map<String, Object> input) {
        fields(input, "status", "method", "environment", "report", "idempotencyKey");
        String status = required(input, "status");
        if (!Set.of("PASSED", "FAILED", "ERROR", "TIMEOUT").contains(status)) throw new ApiException(400, "INVALID_RESULT", "测试状态无效");
        String method = bounded(input, "method", 200), environment = bounded(input, "environment", 1000), report = bounded(input, "report", 16000);
        var body = Map.of("submissionId", id, "status", status, "method", method, "environment", environment, "report", report);
        return mutations.once(required(input, "idempotencyKey"), "PRACTICE_REPORT", body, () -> {
            submission(id);
            var submission = store.one("SELECT id,content_digest FROM practice_submissions WHERE id=?", id);
            String resultId = AtlasStore.uuid();
            store.update("INSERT INTO practice_test_results(id,submission_id,content_digest,source,status,method,environment,report,reported_at) VALUES(?,?,?,'USER_REPORTED',?,?,?,?,?)",
                    resultId, id, submission.get("contentDigest"), status, method, environment, report, time());
            return store.one("SELECT * FROM practice_test_results WHERE id=?", resultId);
        });
    }
    public Map<String, Object> close(String id, Map<String, Object> input) {
        fields(input, "idempotencyKey");
        var result = mutations.once(required(input, "idempotencyKey"), "PRACTICE_CLOSE", Map.of("workspaceId", id), () -> {
            var workspace = store.one("SELECT * FROM practice_workspaces WHERE id=? FOR UPDATE", id);
            if ("ACTIVE".equals(workspace.get("status"))) store.update("UPDATE practice_workspaces SET status='CLOSED' WHERE id=?", id);
            return workspace(id);
        });
        try { remove(path((String) store.one("SELECT path_key FROM practice_workspaces WHERE id=?", id).get("pathKey"))); }
        catch (IOException error) { throw failure(error); }
        return result;
    }
    private Path checked(Path base, String relative) {
        Path target = base.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !target.startsWith(base) || target.equals(base)) throw new ApiException(403, "PRACTICE_BOUNDARY", "练习路径越界");
        return target;
    }
    private byte[] read(Path file) throws IOException {
        for (Path cursor = file; cursor != null && !cursor.equals(root.getParent()); cursor = cursor.getParent()) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                var attrs = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther() || !cursor.toRealPath().equals(cursor.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                    throw new ApiException(403, "PRACTICE_BOUNDARY", "不允许符号链接或重解析点");
            }
        }
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > 2_097_152) throw new ApiException(413, "PRACTICE_LIMIT", "练习文件超出限制");
        byte[] bytes = Files.readAllBytes(file);
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (bytes.length != after.size() || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey()))
            throw new ApiException(409, "WORKSPACE_CHANGED", "读取时文件发生变化，请重试");
        return bytes;
    }
    private Map<String, String> capture(Path source, Path destination) throws IOException {
        var files = new TreeMap<String, String>(); long[] total = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) throw new ApiException(403, "PRACTICE_BOUNDARY", "练习目录包含链接或特殊文件");
                try { if (!dir.toRealPath().equals(dir.toRealPath(LinkOption.NOFOLLOW_LINKS))) throw new ApiException(403, "PRACTICE_BOUNDARY", "练习目录包含重解析点"); }
                catch (IOException error) { throw failure(error); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw new ApiException(403, "PRACTICE_BOUNDARY", "练习文件包含链接或特殊文件");
                if (files.size() >= 20_000) throw new ApiException(413, "PRACTICE_LIMIT", "练习文件数量超限");
                byte[] bytes = read(file); total[0] += bytes.length;
                if (total[0] > 268_435_456) throw new ApiException(413, "PRACTICE_LIMIT", "练习内容总量超限");
                String relative = source.relativize(file).toString().replace('\\', '/');
                if (destination != null) {
                    Path target = checked(destination, relative); Files.createDirectories(target.getParent()); Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                }
                files.put(relative, SnapshotScanner.hash(bytes)); return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
    private String textDiff(byte[] before, byte[] after) {
        try {
            String left = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(before)).toString();
            String right = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(after)).toString();
            String diff = "--- original\n+++ submission\n-" + left.replace("\n", "\n-") + "\n+" + right.replace("\n", "\n+");
            return diff.length() <= 16000 ? diff : diff.substring(0, 16000) + "\n[diff preview truncated; exact files retained]";
        } catch (CharacterCodingException error) { return "[binary content; compare SHA-256 and retained files]"; }
    }
    private void remove(Path directory) throws IOException {
        if (!directory.getParent().equals(root) || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther() || !dir.toRealPath().equals(dir.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("PRACTICE_BOUNDARY 清理目录包含重解析点");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
    private RuntimeException failure(Exception error) { return error instanceof RuntimeException runtime ? runtime : new ApiException(409, "PRACTICE_IO_ERROR", "练习副本文件不可用: " + error.getMessage()); }
}
