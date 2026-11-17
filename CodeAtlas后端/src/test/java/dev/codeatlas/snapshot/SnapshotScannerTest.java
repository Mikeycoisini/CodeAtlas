package dev.codeatlas.snapshot;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class SnapshotScannerTest {
    Path temp;
    @BeforeEach void setup() throws Exception {
        // Keep scanner fixtures outside the project worktree so Git discovery does not
        // change the expected NON_GIT result when the workspace itself is a repository.
        temp = Files.createTempDirectory("codeatlas-scanner-").toAbsolutePath();
    }
    @AfterEach void cleanup() throws Exception {
        try (var paths = Files.walk(temp)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    @Test void capturesImmutableBytesAndIgnoresBuildOutput() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("A.java"), "class A {}\n");
        Files.createDirectory(source.resolve("target"));
        Files.writeString(source.resolve("target/ignored.java"), "ignored");
        var scanner = new SnapshotScanner(temp.resolve("storage"), 20_000, 2_097_152, 268_435_456);
        var first = scanner.capture(source, () -> false, n -> {});
        var again = scanner.capture(source, () -> false, n -> {});
        assertThat(first.manifestHash()).isEqualTo(again.manifestHash());
        assertThat(first.files()).hasSize(1);
        assertThat(first.sourceKind()).isEqualTo("NON_GIT");
        Files.writeString(source.resolve("A.java"), "class Changed {}\n");
        var changed = scanner.capture(source, () -> false, n -> {});
        assertThat(changed.manifestHash()).isNotEqualTo(first.manifestHash());
        assertThat(Files.readString(first.root().resolve("A.java"))).isEqualTo("class A {}\n");
    }

    @Test void currentFileCheckHasSizeAndTraversalBoundaries() throws Exception {
        Path source = Files.createDirectory(temp.resolve("check-source"));
        Path file = source.resolve("Example.java");
        Files.writeString(file, "class Example {}");
        var scanner = new SnapshotScanner(temp.resolve("storage"), 20, 100, 1000);
        assertThat(scanner.currentFileHash(source, "Example.java")).isEqualTo(SnapshotScanner.hash("class Example {}"));
        Files.writeString(temp.resolve("outside.java"), "outside");
        assertThatThrownBy(() -> scanner.currentFileHash(source, "../outside.java")).hasMessageContaining("IMPORT_BOUNDARY");
        Files.write(file, new byte[101]);
        assertThatThrownBy(() -> scanner.currentFileHash(source, "Example.java")).hasMessageContaining("IMPORT_LIMIT");
        var tasks = new dev.codeatlas.tasks.TaskService(null, temp.resolve("data").toString(), temp.resolve("different-root").toString(),
                new dev.codeatlas.github.GitHubFetcher(temp.resolve("data").toString(), 20));
        assertThatThrownBy(() -> tasks.currentFileHash(source.toString(), "Example.java")).hasMessageContaining("IMPORT_BOUNDARY");
    }

    @Test void rejectsDataAncestorAndOversizedFiles() throws Exception {
        var scanner = new SnapshotScanner(temp.resolve("storage"), 1, 3, 10);
        assertThatThrownBy(() -> scanner.capture(temp, () -> false, n -> {})).hasMessageContaining("IMPORT_BOUNDARY");
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("A.java"), "class A {}");
        assertThatThrownBy(() -> scanner.capture(source, () -> false, n -> {})).hasMessageContaining("IMPORT_LIMIT");
    }

    @Test void cancelsWithoutPublishingAndRejectsMissingPath() {
        var scanner = new SnapshotScanner(temp.resolve("storage"), 20, 100, 1000);
        assertThatThrownBy(() -> scanner.capture(temp.resolve("missing"), () -> false, n -> {})).hasMessageContaining("INVALID_PATH");
    }

    @Test void detectsChangesAndDoesNotExecuteProjectScripts() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("A.java"), "class A {}");
        Files.writeString(source.resolve("build.cmd"), "echo executed > marker.txt");
        var scanner = new SnapshotScanner(temp.resolve("storage"), 20, 100, 1000);
        assertThatThrownBy(() -> scanner.capture(source, () -> false, n -> {
            try { Files.writeString(source.resolve("A.java"), "class Changed {}"); } catch (Exception e) { throw new RuntimeException(e); }
        })).hasMessageContaining("SOURCE_CHANGED");
        assertThat(source.resolve("marker.txt")).doesNotExist();
        try (var paths = Files.list(temp.resolve("storage"))) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test void cachedDirectoryValidationStillDetectsSubtreeReplacement() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path first = Files.createDirectory(source.resolve("a"));
        Path second = Files.createDirectory(source.resolve("b"));
        Files.writeString(first.resolve("A.java"), "class A {}");
        Files.writeString(second.resolve("B.java"), "class B {}");
        var scanner = new SnapshotScanner(temp.resolve("storage"), 20, 1000, 10000);
        assertThatThrownBy(() -> scanner.capture(source, () -> false, count -> {
            if (count != 1) return;
            try {
                Files.move(first, source.resolve("a-old"));
                Files.createDirectory(first);
                Files.writeString(first.resolve("C.java"), "class C {}");
            } catch (Exception error) { throw new RuntimeException(error); }
        })).hasMessageContaining("SOURCE_CHANGED");
    }

    @Test void capturesStableSubtreeWhileOtherRepositoryFilesChange() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("changing-repo"));
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "scanner@example.com");
        git(repo, "config", "user.name", "Scanner Test");
        Path source = Files.createDirectory(repo.resolve("source"));
        Files.writeString(source.resolve("A.java"), "class A {}");
        Files.writeString(repo.resolve("outside.txt"), "original");
        git(repo, "add", ".");
        git(repo, "commit", "-qm", "baseline");
        var scanner = new SnapshotScanner(temp.resolve("storage"), 20, 1000, 10000);
        var baseline = scanner.capture(source, () -> false, n -> {});
        var captured = scanner.capture(source, () -> false, n -> {
            try {
                Files.writeString(repo.resolve("outside.txt"), "changed");
                Files.writeString(repo.resolve("new-outside.txt"), "untracked");
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        assertThat(captured.manifestHash()).isEqualTo(baseline.manifestHash());
        assertThat(captured.identityHash()).isEqualTo(baseline.identityHash());
        assertThat(captured.sourceKind()).isEqualTo("GIT_CLEAN");
        assertThat(Files.readString(captured.root().resolve("A.java"))).isEqualTo("class A {}");
        assertThatThrownBy(() -> scanner.capture(source, () -> false, n -> {
            try { Files.writeString(source.resolve("A.java"), "class Changed {}"); }
            catch (Exception error) { throw new RuntimeException(error); }
        })).hasMessageContaining("SOURCE_CHANGED");
    }

    @Test void gitStatusIsScopedToTheImportedSubdirectory() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "scanner@example.com");
        git(repo, "config", "user.name", "Scanner Test");
        Files.writeString(repo.resolve("tracked.txt"), "base");
        git(repo, "add", "tracked.txt");
        git(repo, "commit", "-qm", "init");
        Path source = Files.createDirectory(repo.resolve("source"));
        Files.writeString(source.resolve("A.java"), "class A {}");
        Files.writeString(repo.resolve("outside.txt"), "outside");
        Files.writeString(repo.resolve("tracked.txt"), "changed outside the imported subtree");
        var info = new GitProbe().inspect(source);
        assertThat(info.kind()).isEqualTo("GIT_DIRTY");
        assertThat(info.status()).contains("A.java");
        assertThat(info.status()).doesNotContain("outside.txt", "tracked.txt");
    }

    @Test void rejectsWindowsJunctions() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path source = Files.createDirectory(temp.resolve("source"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path link = source.resolve("linked");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        try {
            try (var ignored = Files.newDirectoryStream(link)) {
                // The scanner must reject a readable junction below.
            } catch (java.io.IOException error) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "当前环境无法读取 Windows junction: " + error.getMessage());
            }
            var scanner = new SnapshotScanner(temp.resolve("storage"), 20, 100, 1000);
            assertThatThrownBy(() -> scanner.capture(source, () -> false, n -> {})).hasMessageContaining("IMPORT_BOUNDARY");
            assertThatThrownBy(() -> scanner.currentFileHash(source, "linked/A.java")).hasMessageContaining("IMPORT_BOUNDARY");
        } finally { Files.delete(link); }
    }

    @Test void avoidsRepositoryConfiguredExternalFilters() throws Exception {
        Path source = Files.createDirectory(temp.resolve("git-source"));
        git(source, "init");
        Files.writeString(source.resolve("A.java"), "class A {}");
        git(source, "add", "A.java");
        git(source, "config", "filter.hazard.clean", "echo executed > marker.txt");
        Files.writeString(source.resolve(".gitattributes"), "*.java filter=hazard\n");
        Files.writeString(source.resolve("A.java"), "class Changed {}");
        var scanner = new SnapshotScanner(temp.resolve("storage"), 20, 1000, 10000);
        var result = scanner.capture(source, () -> false, n -> {});
        assertThat(source.resolve("marker.txt")).doesNotExist();
        assertThat(result.sourceKind()).isEqualTo("UNKNOWN");
        assertThat(result.gitStatus()).contains("过滤器");
    }
    private void git(Path source, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "-c", "core.hooksPath=", "-C", source.toString()));
        command.addAll(java.util.List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
    }

    @Test void distinguishesCleanDirtyAndNonGitSnapshots() throws Exception {
        Path source = Files.createDirectory(temp.resolve("git-source"));
        git(source, "init");
        git(source, "config", "user.name", "CodeAtlas Fixture");
        git(source, "config", "user.email", "fixture@example.invalid");
        git(source, "config", "commit.gpgsign", "false");
        Files.writeString(source.resolve("A.java"), "class A {}");
        git(source, "add", "A.java");
        git(source, "commit", "-m", "fixture");
        var scanner = new SnapshotScanner(temp.resolve("storage"), 20, 1000, 10000);
        var clean = scanner.capture(source, () -> false, n -> {});
        assertThat(clean.sourceKind()).as(clean.gitStatus()).isEqualTo("GIT_CLEAN");
        assertThat(clean.commitHash()).hasSize(40);
        Files.writeString(source.resolve("A.java"), "class Changed {}");
        var dirty = scanner.capture(source, () -> false, n -> {});
        assertThat(dirty.sourceKind()).isEqualTo("GIT_DIRTY");
        assertThat(dirty.commitHash()).isEqualTo(clean.commitHash());
        assertThat(dirty.identityHash()).isNotEqualTo(clean.identityHash());
        Path linked = temp.resolve("linked-worktree");
        git(source, "worktree", "add", "--detach", linked.toString());
        var linkedSnapshot = scanner.capture(linked, () -> false, n -> {});
        assertThat(linkedSnapshot.sourceKind()).isEqualTo("GIT_CLEAN");
        assertThat(linkedSnapshot.files()).extracting(SnapshotScanner.CapturedFile::path).containsExactly("A.java");
    }
}
