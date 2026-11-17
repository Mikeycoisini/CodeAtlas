package dev.codeatlas.architecture;

import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static dev.codeatlas.architecture.ArchitectureModels.*;

@Service
public class ArchitectureExportService {
    public static final Set<String> FORMATS = Set.of("ARCHIFY_HTML", "ARCHITECTURE_HTML", "HTML", "SVG");
    public static final Set<String> SCOPES = Set.of("SYSTEM", "APPLICATION", "MODULE");
    public static final Set<String> THEMES = Set.of("light", "dark");
    private static final String OPERATION = "ARCHITECTURE_EXPORT";

    public record ExportRequest(String snapshotId, String generationId, String projectionId, String digest,
                                String format, String scope, String theme, String idempotencyKey) {}
    public record ExportResult(Map<String, Object> export, boolean created) {}
    public record Download(Path path, String name) {}

    private record Basic(String snapshotId, String generationId, String projectionId, String digest,
                         String format, String scope, String theme, String idempotencyKey, String requestDigest) {}
    private record Resolved(Basic basic, String generationId, Document document) {}
    private record Claim(boolean created, String id) {}

    private final AtlasStore store;
    private final ArchitectureService architecture;
    private final ArchitectureHtmlRenderer renderer = new ArchitectureHtmlRenderer();
    private final Path dataRoot;
    private final Path exportsRoot;

    public ArchitectureExportService(AtlasStore store, ArchitectureService architecture, @Value("${codeatlas.data}") String data) {
        this.store = store;
        this.architecture = architecture;
        this.dataRoot = Path.of(data).toAbsolutePath().normalize();
        this.exportsRoot = dataRoot.resolve("exports").normalize();
    }

    public synchronized ExportResult export(ExportRequest request) {
        var basic = basic(request);
        if (basic.idempotencyKey() == null) {
            var resolved = resolve(basic);
            var row = insertRunning(resolved);
            return new ExportResult(view(run(row, resolved)), true);
        }
        var existing = existing(basic);
        if (existing != null) {
            var row = currentRow((String) existing.get("resultId"));
            if (row == null) {
                var resolved = resolve(basic);
                var retry = retryClaim(basic);
                return new ExportResult(retry.created() ? view(run(row(retry.id()), resolved)) : view(row(retry.id())), retry.created());
            }
            var status = String.valueOf(row.get("status"));
            if ("SUCCEEDED".equals(status)) {
                ensureArtifact(row);
                return new ExportResult(view(row), false);
            }
            if ("RUNNING".equals(status) || "QUEUED".equals(status)) return new ExportResult(view(row), false);
            var resolved = resolve(basic);
            var retry = retryClaim(basic);
            return new ExportResult(retry.created() ? view(run(row(retry.id()), resolved)) : view(row(retry.id())), retry.created());
        }
        var legacy = legacyExisting(basic);
        if (legacy != null) {
            ensureArtifact(legacy);
            return new ExportResult(view(legacy), false);
        }
        var resolved = resolve(basic);
        var claim = claim(resolved);
        if (!claim.created()) {
            var row = currentRow(claim.id());
            if (row == null) return new ExportResult(view(run(insertRunning(resolved), resolved)), true);
            if ("SUCCEEDED".equals(String.valueOf(row.get("status")))) {
                ensureArtifact(row);
                return new ExportResult(view(row), false);
            }
            if ("RUNNING".equals(String.valueOf(row.get("status")))) return new ExportResult(view(row), false);
            var retry = retryClaim(basic);
            return new ExportResult(retry.created() ? view(run(row(retry.id()), resolved)) : view(row(retry.id())), retry.created());
        }
        return new ExportResult(view(run(row(claim.id()), resolved)), true);
    }

    private Basic basic(ExportRequest request) {
        var format = request.format() == null || request.format().isBlank() ? "ARCHIFY_HTML" : request.format().trim().toUpperCase(Locale.ROOT);
        if ("ARCHITECTURE_HTML".equals(format)) format = "ARCHIFY_HTML";
        var scope = request.scope() == null || request.scope().isBlank() ? "SYSTEM" : request.scope().trim().toUpperCase(Locale.ROOT);
        var theme = request.theme() == null || request.theme().isBlank() ? "light" : request.theme().trim().toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(format)) throw new ApiException(400, "INVALID_EXPORT_FORMAT", "导出格式无效");
        if (!SCOPES.contains(scope)) throw new ApiException(400, "INVALID_EXPORT_SCOPE", "导出范围无效");
        if (!THEMES.contains(theme)) throw new ApiException(400, "INVALID_EXPORT_THEME", "导出主题无效");
        var snapshot = clean(request.snapshotId());
        if (snapshot == null) throw new ApiException(400, "INVALID_EXPORT", "缺少快照");
        store.snapshot(snapshot);
        var key = clean(request.idempotencyKey());
        if (key != null && key.length() > 100) throw new ApiException(400, "INVALID_EXPORT_KEY", "请求标识超长");
        var generation = clean(request.generationId());
        var projection = clean(request.projectionId());
        var digest = clean(request.digest());
        var canonical = String.join("\n", SCHEMA_VERSION, snapshot, nvl(generation), nvl(projection), nvl(digest), format, scope, theme);
        return new Basic(snapshot, generation, projection, digest, format, scope, theme, key, SnapshotScanner.hash(canonical));
    }

    private Resolved resolve(Basic basic) {
        var generation = store.generation(basic.snapshotId(), basic.generationId());
        if (generation == null) throw new ApiException(409, "NO_GENERATION", "该快照尚无已发布索引，无法导出架构");
        var document = architecture.document(basic.snapshotId(), generation);
        if (basic.projectionId() != null && !basic.projectionId().equals(document.projectionId()))
            throw new ApiException(409, "PROJECTION_STALE", "架构投影已变化，请刷新后重试");
        if (basic.digest() != null && !basic.digest().equals(document.digest()))
            throw new ApiException(409, "PROJECTION_STALE", "架构投影摘要不匹配，请刷新后重试");
        return new Resolved(basic, generation, document);
    }

    private Map<String, Object> existing(Basic basic) {
        var rows = store.rows("SELECT * FROM mutation_requests WHERE idempotency_key=?", basic.idempotencyKey());
        if (rows.isEmpty()) return null;
        verify(rows.getFirst(), basic);
        return rows.getFirst();
    }

    /** Successful rows created before mutation_requests integration remain replayable without rewriting history. */
    private Map<String, Object> legacyExisting(Basic basic) {
        var rows = store.rows("SELECT * FROM architecture_exports WHERE snapshot_id=? AND idempotency_key=? AND status='SUCCEEDED' ORDER BY created_at DESC",
                basic.snapshotId(), basic.idempotencyKey());
        if (rows.isEmpty()) return null;
        for (var row : rows) {
            if (!basic.format().equals(row.get("format")) || !basic.scope().equals(row.get("scope"))
                    || !basic.theme().equals(row.get("theme"))) continue;
            if (basic.generationId() != null && !basic.generationId().equals(row.get("generationId"))) continue;
            if (basic.projectionId() != null && !basic.projectionId().equals(row.get("projectionId"))) continue;
            if (basic.digest() != null && !basic.digest().equals(row.get("projectionDigest"))) continue;
            return row;
        }
        throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "请求标识已用于不同导出内容");
    }

    private Claim claim(Resolved resolved) {
        try {
            return store.transaction(() -> {
                var previous = store.rows("SELECT * FROM mutation_requests WHERE idempotency_key=? FOR UPDATE", resolved.basic().idempotencyKey());
                if (!previous.isEmpty()) {
                    verify(previous.getFirst(), resolved.basic());
                    return new Claim(false, (String) previous.getFirst().get("resultId"));
                }
                var id = AtlasStore.uuid();
                insertExport(id, resolved, "RUNNING");
                store.update("INSERT INTO mutation_requests(idempotency_key,operation,body_hash,result_id,created_at) VALUES(?,?,?,?,?)",
                        resolved.basic().idempotencyKey(), OPERATION, resolved.basic().requestDigest(), id, AtlasStore.now());
                return new Claim(true, id);
            });
        } catch (DuplicateKeyException error) {
            var previous = store.rows("SELECT * FROM mutation_requests WHERE idempotency_key=?", resolved.basic().idempotencyKey());
            if (previous.isEmpty()) throw error;
            verify(previous.getFirst(), resolved.basic());
            return new Claim(false, (String) previous.getFirst().get("resultId"));
        }
    }

    private Claim retryClaim(Basic basic) {
        return store.transaction(() -> {
            var previous = store.one("SELECT * FROM mutation_requests WHERE idempotency_key=? FOR UPDATE", basic.idempotencyKey());
            verify(previous, basic);
            var current = currentRow((String) previous.get("resultId"));
            if (current != null && "SUCCEEDED".equals(String.valueOf(current.get("status"))))
                return new Claim(false, String.valueOf(current.get("id")));
            var resolved = resolve(basic);
            var id = AtlasStore.uuid();
            insertExport(id, resolved, "RUNNING");
            store.update("UPDATE mutation_requests SET result_id=? WHERE idempotency_key=?", id, basic.idempotencyKey());
            return new Claim(true, id);
        });
    }

    private void verify(Map<String, Object> previous, Basic basic) {
        if (!OPERATION.equals(previous.get("operation")) || !basic.requestDigest().equals(previous.get("bodyHash")))
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "请求标识已用于不同导出内容");
    }

    private Map<String, Object> insertRunning(Resolved resolved) {
        var id = AtlasStore.uuid();
        insertExport(id, resolved, "RUNNING");
        return row(id);
    }

    private void insertExport(String id, Resolved resolved, String status) {
        store.update("INSERT INTO architecture_exports(id,project_id,snapshot_id,generation_id,projection_id,projection_digest,format,scope,theme,status,request_digest,idempotency_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, resolved.document().projectId(), resolved.basic().snapshotId(), resolved.generationId(),
                resolved.document().projectionId(), resolved.document().digest(), resolved.basic().format(),
                resolved.basic().scope(), resolved.basic().theme(), status, resolved.basic().requestDigest(),
                resolved.basic().idempotencyKey(), AtlasStore.now(), AtlasStore.now());
    }

    private Map<String, Object> run(Map<String, Object> row, Resolved resolved) {
        var id = String.valueOf(row.get("id"));
        boolean svg = "SVG".equals(resolved.basic().format());
        var name = svg ? "architecture.svg" : "architecture.html";
        var relative = "exports/" + id + "/" + name;
        var directory = exportsRoot.resolve(id).normalize();
        var artifact = directory.resolve(name);
        Path temp = null;
        try {
            if (!directory.startsWith(exportsRoot)) throw new IllegalStateException("export path");
            Files.createDirectories(directory);
            var content = svg
                    ? renderer.renderSvg(resolved.document(), resolved.basic().theme(), resolved.basic().scope())
                    : renderer.render(resolved.document(), resolved.basic().theme(), resolved.basic().scope());
            temp = directory.resolve(name + ".tmp-" + AtlasStore.uuid());
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, artifact, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, artifact, StandardCopyOption.REPLACE_EXISTING);
            }
            var bytes = Files.size(artifact);
            var hash = sha256(Files.readAllBytes(artifact));
            store.update("UPDATE architecture_exports SET status='SUCCEEDED',artifact_path=?,artifact_name=?,artifact_size=?,artifact_sha256=?,updated_at=? WHERE id=?",
                    relative, name, bytes, hash, AtlasStore.now(), id);
            return row(id);
        } catch (Exception error) {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
            try {
                store.update("UPDATE architecture_exports SET status='FAILED',error_code='EXPORT_FAILED',message=?,updated_at=? WHERE id=?",
                        "导出渲染或写入失败", AtlasStore.now(), id);
            } catch (Exception ignored) { }
            throw new ApiException(500, "EXPORT_FAILED", "架构导出失败，请查看历史记录后重试");
        }
    }

    private Map<String, Object> currentRow(String id) {
        var rows = store.rows("SELECT * FROM architecture_exports WHERE id=?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> row(String id) {
        return store.one("SELECT * FROM architecture_exports WHERE id=?", id);
    }

    public Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>(row);
        result.remove("artifactPath");
        result.put("downloadUrl", "/api/architecture/exports/" + row.get("id") + "/download");
        return result;
    }

    public Download download(String id) {
        var row = store.one("SELECT * FROM architecture_exports WHERE id=?", id);
        if (!"SUCCEEDED".equals(row.get("status"))) throw new ApiException(409, "EXPORT_NOT_READY", "导出尚未成功");
        var path = ensureArtifact(row);
        return new Download(path, String.valueOf(row.get("artifactName")));
    }

    public String contentType(Map<String, Object> row) {
        return "SVG".equals(row.get("format")) ? "image/svg+xml" : "text/html;charset=UTF-8";
    }

    private Path ensureArtifact(Map<String, Object> row) {
        var relative = String.valueOf(row.get("artifactPath"));
        var path = dataRoot.resolve(relative).normalize();
        if (!path.startsWith(exportsRoot) || !Files.isRegularFile(path))
            throw new ApiException(410, "EXPORT_ARTIFACT_MISSING", "导出文件不存在或已被清理");
        var expected = row.get("artifactSha256");
        if (expected instanceof String value && !value.isBlank()) {
            try {
                if (!value.equals(sha256(Files.readAllBytes(path))))
                    throw new ApiException(409, "EXPORT_ARTIFACT_TAMPERED", "导出文件校验失败，请重新导出");
            } catch (java.io.IOException error) {
                throw new ApiException(410, "EXPORT_ARTIFACT_MISSING", "导出文件不可读");
            }
        }
        return path;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        for (var row : store.rows("SELECT id,artifact_path FROM architecture_exports WHERE status IN ('RUNNING','QUEUED')")) {
            try {
                var id = String.valueOf(row.get("id"));
                for (var name : List.of("architecture.html", "architecture.svg")) {
                    var artifact = exportsRoot.resolve(id).resolve(name).normalize();
                    if (artifact.startsWith(exportsRoot) && Files.exists(artifact)) Files.deleteIfExists(artifact);
                }
                var relative = row.get("artifactPath");
                if (relative instanceof String value) {
                    var path = dataRoot.resolve(value).normalize();
                    if (path.startsWith(exportsRoot) && Files.exists(path)) Files.deleteIfExists(path);
                }
            } catch (Exception ignored) { }
            store.update("UPDATE architecture_exports SET status='FAILED',error_code='EXPORT_INTERRUPTED',message='导出在服务中断时未完成',updated_at=? WHERE id=?",
                    AtlasStore.now(), row.get("id"));
        }
    }

    private static String clean(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nvl(String value) { return value == null ? "" : value; }

    private static String sha256(byte[] value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value);
            var result = new StringBuilder();
            for (var item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
