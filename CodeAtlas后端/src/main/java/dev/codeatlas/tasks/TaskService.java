package dev.codeatlas.tasks;

import dev.codeatlas.analysis.JavaAnalyzer;
import dev.codeatlas.github.GitHubFetcher;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@Service
public class TaskService {
    private final AtlasStore store;
    private final GitHubFetcher github;
    private final dev.codeatlas.github.GitHubCredentialService credentials;
    private final Path storage;
    private final SnapshotScanner scanner;
    private final List<Path> allowedRoots;
    private FileChannel lockChannel;
    private FileLock instanceLock;
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> { var thread = new Thread(runnable, "codeatlas-indexer"); thread.setDaemon(true); return thread; });

    @org.springframework.beans.factory.annotation.Autowired
    public TaskService(AtlasStore store, @Value("${codeatlas.data}") String data, @Value("${codeatlas.allowed-roots:}") String roots,
                       GitHubFetcher github, dev.codeatlas.github.GitHubCredentialService credentials) {
        this.store = store;
        this.github = github;
        this.credentials = credentials;
        this.storage = Path.of(data).toAbsolutePath().normalize().resolve("snapshots");
        this.scanner = new SnapshotScanner(storage, 20_000, 2_097_152, 268_435_456);
        this.allowedRoots = Arrays.stream(roots.split(";")).filter(s -> !s.isBlank()).map(s -> Path.of(s).toAbsolutePath().normalize()).toList();
    }

    /** Compatibility constructor for focused scanner/import tests that never use private credentials. */
    public TaskService(AtlasStore store, String data, String roots, GitHubFetcher github) {
        this(store, data, roots, github, new dev.codeatlas.github.GitHubCredentialService(store,
                new com.fasterxml.jackson.databind.ObjectMapper(), data));
    }

    @PostConstruct public void recover() throws IOException {
        Files.createDirectories(storage);
        lockChannel = FileChannel.open(storage.getParent().resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        instanceLock = lockChannel.tryLock();
        if (instanceLock == null) throw new IOException("同一数据目录已被另一个 CodeAtlas 进程使用");
        github.cleanupStale();
        store.recover();
        var retained = store.rows("SELECT storage_key FROM snapshots").stream().map(r -> (String) r.get("storageKey")).collect(java.util.stream.Collectors.toSet());
        try (var children = Files.list(storage)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith("tmp-") || (name.startsWith("snapshot-") && !retained.contains(name))) scanner.removeOwnedDirectory(child);
            }
        }
    }
    @PreDestroy public void close() throws Exception {
        workers.shutdownNow();
        workers.awaitTermination(5, TimeUnit.SECONDS);
        if (instanceLock != null) instanceLock.release();
        if (lockChannel != null) lockChannel.close();
    }

    public synchronized Map<String, Object> importProject(String rawPath) {
        Path path;
        try {
            path = Path.of(rawPath);
            if (!path.isAbsolute()) throw new IllegalArgumentException();
            path = path.normalize();
            if (!Files.isDirectory(path) || !Files.isReadable(path)) throw new IllegalArgumentException();
            if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                throw new ApiException(403, "IMPORT_BOUNDARY", "不允许符号链接或重解析目录");
            path = path.toRealPath();
        } catch (ApiException e) { throw e;
        } catch (Exception e) { throw new ApiException(400, "INVALID_PATH", "请输入存在且可读的绝对目录路径"); }
        if (!allowedRoots.isEmpty() && allowedRoots.stream().noneMatch(path::startsWith)) throw new ApiException(403, "IMPORT_NOT_AUTHORIZED", "该目录超出配置的导入范围");
        var task = store.createImport(path.toString(), path.getFileName() == null ? path.toString() : path.getFileName().toString());
        schedule(task);
        return store.task((String) task.get("id"));
    }

    public Map<String, Object> importGithub(String url, String ref) {
        return importGithub(url, ref, null);
    }

    public Map<String, Object> importGithub(String url, String ref, String credentialId) {
        var target = github.validate(url, ref);
        String normalizedRef = ref == null || ref.isBlank() ? null : ref.trim();
        if (credentialId != null && !credentialId.isBlank()) credentials.token(credentialId);
        var task = store.createImport(target.url(), target.name(), "GITHUB", normalizedRef,
                credentialId == null || credentialId.isBlank() ? null : credentialId);
        schedule(task);
        return store.task((String) task.get("id"));
    }

    public String currentFileHash(String sourcePath, String relative) throws IOException {
        Path root = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!allowedRoots.isEmpty() && allowedRoots.stream().noneMatch(root::startsWith))
            throw new IOException("IMPORT_BOUNDARY 当前目录超出授权范围");
        return scanner.currentFileHash(root, relative);
    }

    public Map<String, Object> index(String snapshot, boolean rebuild) {
        var task = store.createIndex(snapshot, rebuild);
        schedule(task);
        return store.task((String) task.get("id"));
    }

    public Map<String, Object> retry(String id) {
        var task = store.task(id);
        if (!List.of("FAILED", "CANCELLED", "PARTIAL_SUCCESS").contains(task.get("status"))) throw new ApiException(409, "TASK_STATE_CONFLICT", "当前任务状态无需重试");
        if (!"IMPORT".equals(task.get("kind"))) return index((String) task.get("snapshotId"), true);
        return "GITHUB".equals(task.get("sourceType"))
                ? importGithub((String) task.get("sourcePath"), (String) task.get("sourceRef"), (String) task.get("credentialId"))
                : importProject((String) task.get("sourcePath"));
    }

    private void schedule(Map<String, Object> task) {
        String id = (String) task.get("id");
        if (!"QUEUED".equals(task.get("status")) || !scheduled.add(id)) return;
        try { workers.execute(() -> execute(id)); }
        catch (RejectedExecutionException e) {
            scheduled.remove(id);
            store.failure(id, "FAILED", "TASK_CAPACITY_EXCEEDED", "任务队列已满，请稍后重试");
            throw new ApiException(503, "TASK_CAPACITY_EXCEEDED", "任务队列已满，请稍后重试");
        }
    }

    private void execute(String id) {
        try {
            if (store.cancelled(id)) throw new CancellationException();
            var task = store.task(id);
            if ("IMPORT".equals(task.get("kind")))
                importSnapshot(id, (String) task.get("sourceType"), (String) task.get("sourcePath"),
                        (String) task.get("sourceRef"), (String) task.get("credentialId"));
            else analyze(id, (String) task.get("snapshotId"));
        } catch (CancellationException e) {
            store.failure(id, "CANCELLED", "CANCELLED", "任务已取消");
        } catch (GitHubFetcher.FetchException e) {
            store.failure(id, "FAILED", e.code, e.getMessage());
        } catch (Exception e) {
            String message = e.getMessage() == null ? "任务失败，请查看诊断后重试" : e.getMessage();
            String code = message.split(" ", 2)[0];
            if (!Set.of("IMPORT_LIMIT", "IMPORT_BOUNDARY", "SOURCE_CHANGED", "INVALID_PATH").contains(code)) code = "TASK_ERROR";
            store.failure(id, "FAILED", code, message);
        } finally { scheduled.remove(id); }
    }

    private void importSnapshot(String id, String sourceType, String source, String sourceRef, String credentialId) throws IOException {
        store.running(id, 0);
        BooleanSupplier cancelled = () -> store.cancelled(id) || Thread.currentThread().isInterrupted();
        Path staging = null;
        SnapshotScanner.CapturedSnapshot capture;
        try {
            Path root;
            if ("GITHUB".equals(sourceType)) {
                var target = github.validate(source, sourceRef);
                var token = credentialId == null || credentialId.isBlank() ? null : credentials.token(credentialId);
                // Preserve the public/fake fetch hook; only private imports use the credential-aware overload.
                staging = token == null ? github.fetch(target, sourceRef, id, cancelled)
                        : github.fetch(target, sourceRef, id, cancelled, token);
                root = staging;
            } else {
                root = Path.of(source);
            }
            capture = scanner.capture(root, cancelled, n -> store.progress(id, n));
        } finally {
            github.cleanup(staging);
        }
        // A lost commit acknowledgement is ambiguous. Startup reconciles unreferenced snapshots.
        boolean retained = true;
        try {
            String snapshot = store.completeImport(id, capture);
            if (snapshot == null) {
                retained = false;
                throw new CancellationException();
            }
            var snapshotRow = store.snapshot(snapshot);
            if (credentialId != null && !credentialId.isBlank() && "GITHUB".equals(sourceType)) {
                credentials.bind((String) snapshotRow.get("projectId"), credentialId, source, sourceRef,
                        (String) snapshotRow.get("commitHash"));
            }
            retained = capture.root().getFileName().toString().equals(snapshotRow.get("storageKey"));
        } finally { if (!retained) scanner.removeOwnedDirectory(capture.root()); }
    }

    private void analyze(String id, String snapshot) {
        var files = store.files(snapshot).stream().map(f -> new JavaAnalyzer.SourceFile((String) f.get("id"), (String) f.get("path"))).toList();
        int total = (int) files.stream().filter(f -> f.path().endsWith(".java")).count();
        store.running(id, total);
        Path root = snapshotRoot(snapshot);
        var analysis = new JavaAnalyzer().analyze(root, files, () -> store.cancelled(id) || Thread.currentThread().isInterrupted(),
                n -> store.progress(id, n), partial -> store.appendDraft(id, partial));
        store.saveAnalysis(id, analysis);
    }

    public Path snapshotRoot(String snapshot) {
        String key = (String) store.snapshot(snapshot).get("storageKey");
        Path root = storage.resolve(key).normalize();
        if (!root.getParent().equals(storage) || !key.startsWith("snapshot-")) throw new ApiException(500, "STORAGE_ERROR", "快照目录无效");
        return root;
    }
}
