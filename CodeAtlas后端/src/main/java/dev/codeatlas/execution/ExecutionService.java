package dev.codeatlas.execution;

import dev.codeatlas.learning.MutationService;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.training.PracticeService;
import dev.codeatlas.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Isolated execution pilot, disabled by default. Execution content is derived from an immutable
 * practice submission; host workspace paths are never accepted from the client.
 */
@Service
public class ExecutionService {
    private static final int MAX_FILES = 20_000;
    private static final long MAX_BYTES = 268_435_456L;
    private static final int MAX_LOG = 65_536;
    private static final String COMMAND = "mvn -q -DskipTests=false test";

    private final AtlasStore store;
    private final PracticeService practice;
    private final MutationService mutations;
    private final DockerRunner runner;
    private final Path practiceRoot;
    private final Path executionsRoot;
    private final boolean enabled;
    private final String image;
    private final int timeoutSeconds;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "codeatlas-executor");
        thread.setDaemon(true);
        return thread;
    });

    public ExecutionService(AtlasStore store, PracticeService practice, MutationService mutations, DockerRunner runner,
                            @Value("${codeatlas.data}") String data,
                            @Value("${codeatlas.executor.enabled:false}") boolean enabled,
                            @Value("${codeatlas.executor.image:maven:3.9-eclipse-temurin-21}") String image,
                            @Value("${codeatlas.executor.timeout-seconds:90}") int timeoutSeconds) {
        this.store = store;
        this.practice = practice;
        this.mutations = mutations;
        this.runner = runner;
        var dataRoot = Path.of(data).toAbsolutePath().normalize();
        this.practiceRoot = dataRoot.resolve("practice").normalize();
        this.executionsRoot = dataRoot.resolve("executions").normalize();
        this.enabled = enabled;
        this.image = image;
        this.timeoutSeconds = Math.max(10, Math.min(timeoutSeconds, 300));
    }

    public Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", enabled);
        result.put("dockerAvailable", runner.available());
        result.put("image", image);
        result.put("network", "none");
        result.put("limits", Map.of("cpus", 1, "memoryMb", 512, "pids", 128, "timeoutSeconds", timeoutSeconds));
        result.put("queuePolicy", "SINGLE_WORKER");
        result.put("maxConcurrency", 1);
        result.put("resultClasses", List.of("PASSED", "TEST_FAILED", "BUILD_ERROR", "TIMEOUT", "CANCELLED", "FAILED", "ERROR"));
        result.put("message", enabled ? "自动隔离执行已配置；仍需完成 PR-05 安全准入后再用于真实训练任务"
                : "自动隔离执行尚未通过安全准入；当前继续使用 USER_REPORTED 外部报告");
        return result;
    }

    public Map<String, Object> execute(String submissionId, String idempotencyKey) {
        if (!enabled) throw new ApiException(503, "EXECUTOR_DISABLED", "自动隔离执行尚未通过安全准入，继续使用 USER_REPORTED 外部报告");
        if (!runner.available()) throw new ApiException(503, "EXECUTOR_UNAVAILABLE", "本机 Docker 不可用，无法启动隔离执行");
        if (submissionId == null || submissionId.isBlank()) throw new ApiException(400, "EXECUTOR_SUBMISSION", "请选择已提交的练习存档");
        // Revalidates the immutable archive against its manifest before a job is created.
        var submission = practice.submission(submissionId);
        var workspaceId = (String) submission.get("workspaceId");
        var workspace = store.one("SELECT * FROM practice_workspaces WHERE id=?", workspaceId);
        var snapshotId = (String) workspace.get("snapshotId");
        var snapshot = store.snapshot(snapshotId);
        var projectId = (String) snapshot.get("projectId");
        var taskId = (String) workspace.get("taskId");
        var contentDigest = (String) submission.get("contentDigest");
        var body = Map.of("submissionId", submissionId, "contentDigest", contentDigest, "image", image,
                "timeoutSeconds", timeoutSeconds, "command", COMMAND);
        var requestDigest = SnapshotScanner.hash(store.encode(new TreeMap<>(body)));
        var created = mutations.once(idempotencyKey, "EXECUTION_CREATE", body,
                () -> createJob(submissionId, contentDigest, projectId, taskId, snapshotId, idempotencyKey, requestDigest));
        var jobId = (String) created.get("id");
        if (claim(jobId)) {
            try {
                worker.submit(() -> runJob(jobId));
            } catch (RuntimeException error) {
                terminal(jobId, "ERROR", "ERROR", null, "", "", "EXECUTOR_QUEUE", "执行队列不可用", "NOT_CREATED", "");
            }
        }
        return view(store.one("SELECT * FROM execution_jobs WHERE id=?", jobId));
    }

    public Map<String, Object> job(String id) { return view(store.one("SELECT * FROM execution_jobs WHERE id=?", id)); }

    public Map<String, Object> cancel(String id) {
        var job = store.one("SELECT * FROM execution_jobs WHERE id=?", id);
        if (terminalStatus(String.valueOf(job.get("status")))) return view(job);
        store.update("UPDATE execution_jobs SET cancel_requested=TRUE WHERE id=?", id);
        if ("QUEUED".equals(job.get("status"))) {
            store.update("UPDATE execution_jobs SET status='CANCELLED',result_class='CANCELLED',finished_at=?,message='排队任务已取消' WHERE id=? AND status='QUEUED'",
                    AtlasStore.now(), id);
        } else if (job.get("containerId") instanceof String containerId && !containerId.isBlank()) {
            var cleanup = runner.stop(containerId);
            store.update("UPDATE execution_jobs SET cleanup_status=?,cleanup_message=? WHERE id=?", cleanup.status(), cleanup.message(), id);
        }
        return view(store.one("SELECT * FROM execution_jobs WHERE id=?", id));
    }

    private Map<String, Object> createJob(String submissionId, String contentDigest, String projectId,
                                          String taskId, String snapshotId, String idempotencyKey, String requestDigest) {
        var id = AtlasStore.uuid();
        store.update("INSERT INTO execution_jobs(id,project_id,snapshot_id,task_id,submission_id,content_digest,idempotency_key,request_digest,image,config_digest,status,command,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, projectId, snapshotId, taskId, submissionId, contentDigest, idempotencyKey, requestDigest, image,
                SnapshotScanner.hash(image + "|" + timeoutSeconds + "|" + COMMAND), "QUEUED", COMMAND, AtlasStore.now());
        return store.one("SELECT * FROM execution_jobs WHERE id=?", id);
    }

    private boolean claim(String jobId) {
        return store.transaction(() -> {
            var row = store.one("SELECT status FROM execution_jobs WHERE id=? FOR UPDATE", jobId);
            if (!"QUEUED".equals(row.get("status"))) return false;
            store.update("UPDATE execution_jobs SET status='RUNNING',started_at=? WHERE id=?", AtlasStore.now(), jobId);
            return true;
        });
    }

    private void runJob(String jobId) {
        var job = store.one("SELECT * FROM execution_jobs WHERE id=?", jobId);
        if (Boolean.TRUE.equals(job.get("cancelRequested"))) {
            terminal(jobId, "CANCELLED", "CANCELLED", null, "", "", null, "任务在启动前已取消", "NOT_CREATED", "");
            return;
        }
        try {
            var submission = practice.submission((String) job.get("submissionId"));
            if (!Objects.equals(job.get("contentDigest"), submission.get("contentDigest")))
                throw new ApiException(409, "SUBMISSION_CHANGED", "提交存档的内容标识已变化");
            var archive = Path.of((String) submission.get("archivePath")).toAbsolutePath().normalize();
            if (!archive.startsWith(practiceRoot) || !archive.getFileName().toString().startsWith("submission-"))
                throw new ApiException(403, "EXECUTOR_ARCHIVE", "提交存档路径无效");
            var workspace = executionsRoot.resolve(jobId).resolve("workspace").normalize();
            resetDirectory(workspace);
            var digest = copyArchive(archive, workspace);
            if (!digest.equals(job.get("contentDigest")))
                throw new ApiException(409, "SUBMISSION_CORRUPTED", "提交存档在复制过程中发生变化");
            store.update("UPDATE execution_jobs SET workspace_path=?,archive_path=? WHERE id=?", workspace.toString(), archive.toString(), jobId);
            var cidFile = executionsRoot.resolve(jobId).resolve("container.cid");
            var spec = new DockerRunner.RunSpec(jobId, "codeatlas-job-" + jobId, image, workspace, cidFile,
                    timeoutSeconds, () -> cancelled(jobId));
            store.update("UPDATE execution_jobs SET container_name=? WHERE id=?", spec.containerName(), jobId);
            var result = runner.run(spec);
            store.update("UPDATE execution_jobs SET container_name=?,container_id=? WHERE id=?",
                    spec.containerName(), result.containerId(), jobId);
            var status = classify(result.exitCode(), result.stdout(), result.stderr(), result.timedOut(), result.cancelled());
            if (cancelled(jobId)) status = "CANCELLED";
            var resultClass = status.equals("PASSED") ? "PASSED" : status;
            terminal(jobId, status, resultClass, result.exitCode(), result.stdout(), result.stderr(), null, "",
                    result.cleanupStatus(), result.cleanupMessage());
        } catch (ApiException error) {
            terminal(jobId, "ERROR", error.code, null, "", "", error.code, error.getMessage(), "NOT_CREATED", "");
        } catch (Exception error) {
            terminal(jobId, "ERROR", "EXECUTION_ERROR", null, "", "", "EXECUTION_ERROR", "隔离执行失败", "NOT_CREATED", "");
        }
    }

    private void terminal(String jobId, String status, String resultClass, Integer exitCode, String stdout,
                          String stderr, String errorCode, String message, String cleanupStatus, String cleanupMessage) {
        store.update("UPDATE execution_jobs SET status=?,result_class=?,exit_code=?,stdout=?,stderr=?,error_code=?,message=?,cleanup_status=?,cleanup_message=?,finished_at=? WHERE id=?",
                status, resultClass, exitCode, clip(stdout), clip(stderr), errorCode, message, cleanupStatus, cleanupMessage, AtlasStore.now(), jobId);
    }

    static String classify(int exitCode, String stdout, String stderr, boolean timedOut, boolean cancelled) {
        if (timedOut) return "TIMEOUT";
        if (cancelled) return "CANCELLED";
        if (exitCode == 0) return "PASSED";
        var output = (stdout == null ? "" : stdout) + "\n" + (stderr == null ? "" : stderr);
        if (output.contains("COMPILATION ERROR") || output.contains("Could not resolve dependencies")
                || output.contains("Could not find or load main class")) return "BUILD_ERROR";
        if (output.contains("There are test failures") || output.matches("(?s).*Tests run:.*Failures: [1-9].*"))
            return "TEST_FAILED";
        return "FAILED";
    }

    private String copyArchive(Path archive, Path target) throws IOException {
        var manifest = new TreeMap<String, String>();
        long[] total = {0};
        int[] count = {0};
        Files.walkFileTree(archive, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                validatePlain(directory, attributes);
                Files.createDirectories(target.resolve(archive.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                validatePlain(file, attributes);
                if (!attributes.isRegularFile()) throw new IOException("EXECUTOR_ARCHIVE 只允许普通文件");
                long size = attributes.size();
                total[0] += size;
                count[0]++;
                if (count[0] > MAX_FILES || total[0] > MAX_BYTES)
                    throw new ApiException(413, "EXECUTOR_WORKSPACE_LIMIT", "提交存档超过隔离执行大小限制");
                var bytes = Files.readAllBytes(file);
                var relative = archive.relativize(file).toString().replace('\\', '/');
                var destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) throw new IOException("EXECUTOR_ARCHIVE 路径越界");
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
                manifest.put(relative, SnapshotScanner.hash(bytes));
                return FileVisitResult.CONTINUE;
            }
        });
        return SnapshotScanner.hash(store.encode(manifest));
    }

    private void validatePlain(Path path, BasicFileAttributes attributes) throws IOException {
        if (attributes.isSymbolicLink() || attributes.isOther()
                || !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("EXECUTOR_BOUNDARY 不允许链接或重解析点");
    }

    private void resetDirectory(Path target) throws IOException {
        if (!target.startsWith(executionsRoot) || !"workspace".equals(target.getFileName().toString()))
            throw new IOException("EXECUTOR_WORKSPACE 目录越界");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) removeOwned(target);
        Files.createDirectories(target);
    }

    private void removeOwned(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("EXECUTOR_BOUNDARY 清理目录包含链接");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean cancelled(String jobId) {
        var rows = store.rows("SELECT cancel_requested FROM execution_jobs WHERE id=?", jobId);
        if (rows.isEmpty()) return true;
        var value = rows.getFirst().get("cancelRequested");
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
    }

    private Map<String, Object> view(Map<String, Object> job) {
        var result = new LinkedHashMap<>(job);
        result.remove("workspacePath");
        result.remove("archivePath");
        result.put("submissionId", job.get("submissionId"));
        return result;
    }

    private static boolean terminalStatus(String status) {
        return Set.of("PASSED", "TEST_FAILED", "BUILD_ERROR", "FAILED", "TIMEOUT", "CANCELLED", "ERROR", "INTERRUPTED").contains(status);
    }

    private static String clip(String value) {
        if (value == null) return "";
        return value.length() <= MAX_LOG ? value : value.substring(0, MAX_LOG) + "\n[log truncated]";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        for (var job : store.rows("SELECT * FROM execution_jobs WHERE status IN ('QUEUED','RUNNING','CREATED')")) {
            String jobId = (String) job.get("id");
            var cleanup = runner.recover(jobId, "codeatlas-job-" + jobId);
            store.update("UPDATE execution_jobs SET status='INTERRUPTED',result_class='INTERRUPTED',error_code='EXECUTOR_RESTART',message='服务重启时执行未完成',cleanup_status=?,cleanup_message=?,finished_at=? WHERE id=?",
                    cleanup.status(), cleanup.message(), AtlasStore.now(), job.get("id"));
        }
    }

    @PreDestroy
    void shutdown() { worker.shutdownNow(); }
}
