package dev.codeatlas.snapshot;

import dev.codeatlas.analysis.JavaAnalyzer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

public class SnapshotScanner {
    public static final String POLICY = "snapshot-v1";
    private static final Set<String> IGNORED = Set.of(".git", ".idea", ".vscode", "node_modules", "target", "build", "dist", ".gradle", ".m2");
    private final Path storage;
    private final int maxFiles;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    public record CapturedFile(String id, String path, String sha256, long byteSize, long modifiedAt) {}
    public record CapturedSnapshot(Path root, List<CapturedFile> files, String manifestHash, String identityHash,
                                   String sourceKind, String commitHash, String gitStatus) {}

    public SnapshotScanner(Path storage, int maxFiles, long maxFileBytes, long maxTotalBytes) {
        this.storage = storage.toAbsolutePath().normalize();
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public CapturedSnapshot capture(Path source, BooleanSupplier cancelled, Consumer<Integer> progress) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isReadable(root)) throw new IOException("INVALID_PATH 目录不存在或不可读");
        validate(root, root);
        if (storage.startsWith(root) || root.startsWith(storage)) throw new IOException("IMPORT_BOUNDARY 导入目录与数据目录重叠");
        Files.createDirectories(storage);
        var beforeGit = new GitProbe().inspect(root);
        Path temporary = Files.createDirectory(storage.resolve("tmp-" + UUID.randomUUID()));
        boolean retained = false;
        try {
            var files = scan(root, temporary, cancelled, progress);
            var checked = scan(root, null, cancelled, n -> {});
            var afterGit = new GitProbe().inspect(root);
            if (!files.equals(checked) || !beforeGit.equals(afterGit)) throw new IOException("SOURCE_CHANGED 源码在导入过程中发生变化，请重试");
            String manifest = hash(POLICY + "\n" + String.join("\n", files.stream()
                    .map(f -> f.path().length() + ":" + f.path() + ":" + f.byteSize() + ":" + f.sha256()).toList()));
            String identity = hash(manifest + "|" + afterGit.kind() + "|" + afterGit.revision());
            Path published = storage.resolve("snapshot-" + UUID.randomUUID());
            Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
            retained = true;
            return new CapturedSnapshot(published, files, manifest, identity, afterGit.kind(), afterGit.commit(), afterGit.status());
        } finally {
            if (!retained) removeOwnedDirectory(temporary);
        }
    }

    private List<CapturedFile> scan(Path root, Path destination, BooleanSupplier cancelled, Consumer<Integer> progress) throws IOException {
        var files = new ArrayList<CapturedFile>();
        long[] total = {0};
        // Directory path resolution is cached per scan; every file leaf is still revalidated before and after reading.
        var validatedDirectories = new HashSet<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                checkCancelled(cancelled);
                if (!directory.equals(root) && IGNORED.contains(directory.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                validate(root, directory, validatedDirectories);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                checkCancelled(cancelled);
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (IGNORED.contains(name)) return FileVisitResult.CONTINUE;
                if (name.equals(".env") || name.startsWith(".env.") || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")) return FileVisitResult.CONTINUE;
                validate(root, path, validatedDirectories);
                if (!attributes.isRegularFile()) throw new IOException("IMPORT_BOUNDARY 只允许普通文件 " + root.relativize(path));
                if (files.size() >= maxFiles || attributes.size() > maxFileBytes) throw new IOException("IMPORT_LIMIT 文件数量或大小超限");
                byte[] bytes;
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(Math.toIntExact(maxFileBytes + 1));
                }
                validate(root, path, validatedDirectories);
                var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.size() != after.size() || !attributes.lastModifiedTime().equals(after.lastModifiedTime())
                        || !Objects.equals(attributes.fileKey(), after.fileKey())) throw new IOException("SOURCE_CHANGED 文件在复制过程中发生变化");
                if (bytes.length > maxFileBytes || (total[0] += bytes.length) > maxTotalBytes) throw new IOException("IMPORT_LIMIT 内容大小超限");
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (destination != null) {
                    Path target = destination.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                }
                files.add(new CapturedFile(JavaAnalyzer.id(relative), relative, hash(bytes), bytes.length, after.lastModifiedTime().toMillis()));
                progress.accept(files.size());
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(CapturedFile::path));
        return List.copyOf(files);
    }

    public String currentFileHash(Path source, String relative) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        validate(root, root);
        validate(root, file);
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("IMPORT_BOUNDARY 只允许普通文件");
        if (before.size() > maxFileBytes) throw new IOException("IMPORT_LIMIT 文件大小超限");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(Math.toIntExact(maxFileBytes + 1));
        }
        if (bytes.length > maxFileBytes) throw new IOException("IMPORT_LIMIT 文件大小超限");
        validate(root, file);
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()) || bytes.length != after.size())
            throw new IOException("SOURCE_CHANGED 检查期间文件发生变化");
        return hash(bytes);
    }

    private void validate(Path root, Path path) throws IOException {
        validate(root, path, null);
    }

    private void validate(Path root, Path path, java.util.Set<Path> validatedDirectories) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) throw new IOException("IMPORT_BOUNDARY 不允许目录越界");
        var ancestors = new ArrayDeque<Path>();
        for (Path current = absolute; current != null && current.startsWith(root); current = current.getParent()) {
            ancestors.addFirst(current);
        }
        // Check ancestors before the leaf, including when a leaf beneath a junction is missing.
        for (Path current : ancestors) {
            boolean leaf = current.equals(absolute);
            if (!leaf && validatedDirectories != null && validatedDirectories.contains(current)) continue;
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("IMPORT_BOUNDARY 不允许重解析点或特殊文件");
            if (attributes.isDirectory() && validatedDirectories != null) validatedDirectories.add(current);
        }
    }

    public void removeOwnedDirectory(Path directory) throws IOException {
        Path checked = directory.toAbsolutePath().normalize();
        if (!checked.getParent().equals(storage) || !(checked.getFileName().toString().startsWith("tmp-")
                || checked.getFileName().toString().startsWith("snapshot-"))) throw new IOException("IMPORT_BOUNDARY 非快照目录");
        if (!Files.exists(checked)) return;
        Files.walkFileTree(checked, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void checkCancelled(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException(); }
}
