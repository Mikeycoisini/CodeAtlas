package dev.codeatlas.github;

import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.web.ApiException;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class GitHubFetcherTest {
    Path temp;
    GitHubFetcher fetcher;

    @BeforeEach void setup() throws Exception {
        temp = Files.createTempDirectory(Files.createDirectories(Path.of("target/test-temp")), "github-").toAbsolutePath();
        fetcher = new GitHubFetcher(temp.resolve("data").toString(), 20);
    }

    @AfterEach void cleanup() throws Exception {
        deleteTree(temp);
    }

    @Test void acceptsOnlyPublicGithubRepositoryRoots() {
        assertThat(fetcher.validate("https://github.com/spring-projects/spring-petclinic", null).url())
                .isEqualTo("https://github.com/spring-projects/spring-petclinic");
        assertThat(fetcher.validate(" https://www.github.com/owner/repo.git/ ", " ").cloneUrl())
                .isEqualTo("https://github.com/owner/repo.git");
        assertThat(fetcher.validate("https://github.com/Owner/Repo", null).url())
                .isEqualTo("https://github.com/owner/repo");
        for (String invalid : List.of(
                "http://github.com/owner/repo",
                "https://gitlab.com/owner/repo",
                "https://user:pass@github.com/owner/repo",
                "https://github.com/owner/repo?tab=readme",
                "https://github.com/owner/repo#readme",
                "https://github.com/owner/repo/tree/main",
                "https://github.com:8443/owner/repo",
                "git@github.com:owner/repo.git",
                "https://github.com/owner",
                "https://github.com/owner/..",
                "not a url")) {
            assertThatThrownBy(() -> fetcher.validate(invalid, null))
                    .as(invalid).isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.code).isEqualTo("INVALID_GITHUB_URL"));
        }
    }

    @Test void acceptsBranchAndTagNamesAndRejectsOptionLikeRefs() {
        assertThat(fetcher.validate("https://github.com/owner/repo", "main").url()).isNotBlank();
        assertThat(fetcher.validate("https://github.com/owner/repo", "release/1.0").url()).isNotBlank();
        assertThat(fetcher.validate("https://github.com/owner/repo", "v1.0.0").url()).isNotBlank();
        for (String invalid : List.of("-main", "/main", "a..b", "a b", "a\\b", "a~b", "a^b",
                "a:b", "a?b", "a*b", "a[b", "a.lock", "a/", "a.", "a//b", "a@{b")) {
            assertThatThrownBy(() -> fetcher.validate("https://github.com/owner/repo", invalid))
                    .as(invalid).isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.code).isEqualTo("INVALID_GITHUB_REF"));
        }
    }

    @Test void clonesShallowRepositoryAndCapturesItsCommit() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("repository"));
        git(repository, "init", "--initial-branch=main", "--quiet");
        git(repository, "config", "user.email", "test@example.com");
        git(repository, "config", "user.name", "Test");
        Files.writeString(repository.resolve("A.java"), "class A {}\n");
        git(repository, "add", "A.java");
        git(repository, "commit", "--quiet", "-m", "initial");
        String head = git(repository, "rev-parse", "HEAD").trim();

        Path checkout = fetcher.cloneRepository(repository.toUri().toString(), null, "11111111-2222-3333-4444-555555555555", () -> false);
        assertThat(checkout.resolve("A.java")).hasContent("class A {}\n");
        assertThat(git(checkout, "rev-parse", "HEAD").trim()).isEqualTo(head);

        var scanner = new SnapshotScanner(temp.resolve("snapshots"), 20_000, 2_097_152, 268_435_456);
        var capture = scanner.capture(checkout, () -> false, count -> {});
        assertThat(capture.sourceKind()).isEqualTo("GIT_CLEAN");
        assertThat(capture.commitHash()).isEqualTo(head);
        assertThat(capture.files()).extracting(SnapshotScanner.CapturedFile::path).contains("A.java");

        fetcher.cleanup(checkout);
        assertThat(checkout.getParent()).doesNotExist();
    }

    @Test void clonesRequestedTagAndBranch() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("tagged"));
        git(repository, "init", "--initial-branch=main", "--quiet");
        git(repository, "config", "user.email", "test@example.com");
        git(repository, "config", "user.name", "Test");
        Files.writeString(repository.resolve("A.java"), "class A {}\n");
        git(repository, "add", "A.java");
        git(repository, "commit", "--quiet", "-m", "initial");
        git(repository, "tag", "v1");
        Files.writeString(repository.resolve("A.java"), "class Changed {}\n");
        git(repository, "add", "A.java");
        git(repository, "commit", "--quiet", "-m", "second");

        Path tag = fetcher.cloneRepository(repository.toUri().toString(), "v1",
                java.util.UUID.randomUUID().toString(), () -> false);
        assertThat(tag.resolve("A.java")).hasContent("class A {}\n");
        fetcher.cleanup(tag);

        Path branch = fetcher.cloneRepository(repository.toUri().toString(), "main",
                java.util.UUID.randomUUID().toString(), () -> false);
        assertThat(branch.resolve("A.java")).hasContent("class Changed {}\n");
        fetcher.cleanup(branch);
    }

    @Test void removesStaleStagingDirectoriesOnly() throws Exception {
        Path stale = Files.createDirectories(temp.resolve("data/github-staging")
                .resolve("11111111-2222-3333-4444-555555555555/repo"));
        Files.writeString(stale.resolve("A.java"), "class A {}");
        Path keep = Files.createDirectories(temp.resolve("data/github-staging/keep"));
        fetcher.cleanupStale();
        assertThat(stale.getParent()).doesNotExist();
        assertThat(keep).exists();
    }

    @Test void cleansStagingAfterCloneFailure() {
        String task = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String missing = temp.resolve("missing-repository").toUri().toString();
        assertThatThrownBy(() -> fetcher.cloneRepository(missing, null, task, () -> false))
                .isInstanceOf(GitHubFetcher.FetchException.class);
        assertThat(temp.resolve("data/github-staging").resolve(task)).doesNotExist();
    }

    private static String git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }
}
