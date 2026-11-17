package dev.codeatlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeatlas.github.GitHubFetcher;
import dev.codeatlas.github.GitHubCredentialService;
import dev.codeatlas.github.GitHubSyncService;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.tasks.TaskService;
import dev.codeatlas.web.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${TEST_DB_URL_GITHUB:jdbc:h2:mem:github;MODE=MySQL;DB_CLOSE_DELAY=-1}",
                "spring.datasource.username=${TEST_DB_USER:sa}",
                "spring.datasource.password=${TEST_DB_PASSWORD:}",
                "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}",
                "codeatlas.data=${TEST_DATA_GITHUB:target/test-data-github}"})
class GitHubImportIntegrationTest {
    @TestConfiguration
    static class FakeFetcherConfiguration {
        @Bean
        @Primary
        GitHubFetcher fakeGitHubFetcher(@Value("${codeatlas.data}") String data) {
            return new GitHubFetcher(data, 20) {
                private final Set<String> failOnce = ConcurrentHashMap.newKeySet();
                private int privateFetches;
                {
                    failOnce.add("retry-sample");
                }

                @Override
                public Path fetch(Target target, String ref, String taskId, BooleanSupplier cancelled) {
                    Path owned = Path.of(data).toAbsolutePath().normalize().resolve("github-staging").resolve(taskId);
                    if (target.name().equals("retry-sample") && failOnce.remove(target.name()))
                        throw new FetchException("GITHUB_NETWORK", "无法连接 GitHub，请检查网络");
                    Path checkout = owned.resolve("repo");
                    try {
                        Files.createDirectories(checkout);
                        copy(Path.of("test-fixtures/spring-sample").toAbsolutePath().normalize(), checkout);
                        if (target.name().equals("private-sample")) {
                            Files.writeString(checkout.resolve("PrivateChange" + (++privateFetches) + ".java"), "class PrivateChange {}");
                        }
                        initializeRepository(checkout);
                        return checkout;
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }

                @Override
                public Path fetch(Target target, String ref, String taskId, BooleanSupplier cancelled, String token) {
                    return fetch(target, ref, taskId, cancelled);
                }
            };
        }

        @Bean
        @Primary
        GitHubCredentialService fakeGitHubCredentialService(AtlasStore store, ObjectMapper json,
                                                            @Value("${codeatlas.data}") String data) {
            return new GitHubCredentialService(store, json, data) {
                @Override protected RemoteUser validate(String token) {
                    var value = token == null ? "" : token.trim();
                    if (value.length() < 20 || value.length() > 512 || value.chars().anyMatch(Character::isWhitespace))
                        throw new ApiException(400, "INVALID_GITHUB_CREDENTIAL", "请输入有效的 GitHub 访问令牌");
                    return new RemoteUser("fixture-user", "repo");
                }
            };
        }

        @Bean
        @Primary
        GitHubSyncService fakeGitHubSyncService(AtlasStore store, GitHubCredentialService credentials,
                                                TaskService tasks, ObjectMapper json) {
            return new GitHubSyncService(store, credentials, tasks, json) {
                @Override protected String remoteHead(String repoUrl, String ref, String token) {
                    return "remote-fixture-sha";
                }
            };
        }

        private static void copy(Path source, Path destination) throws IOException {
            try (var paths = Files.walk(source)) {
                for (Path path : paths.toList()) {
                    Path target = destination.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(target);
                    else Files.copy(path, target);
                }
            }
        }

        private static void initializeRepository(Path checkout) throws Exception {
            var fixed = Map.of("GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z", "GIT_COMMITTER_DATE", "2026-01-01T00:00:00Z");
            git(checkout, Map.of(), "init", "--initial-branch=main", "--quiet");
            git(checkout, Map.of(), "config", "user.email", "fixture@example.com");
            git(checkout, Map.of(), "config", "user.name", "Fixture");
            git(checkout, Map.of(), "add", ".");
            git(checkout, fixed, "commit", "--quiet", "-m", "fixture");
        }

        private static void git(Path directory, Map<String, String> environment, String... arguments) throws Exception {
            var command = new ArrayList<String>();
            command.add("git");
            command.add("-c");
            command.add("core.hooksPath=");
            command.addAll(List.of(arguments));
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().putAll(environment);
            var process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new IllegalStateException(output);
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    String token;

    @BeforeEach void session() {
        token = http.getForObject("/api/session", JsonNode.class).get("token").asText();
    }

    JsonNode request(HttpMethod method, String path, Object body) {
        var response = exchange(method, path, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        headers.set("X-CodeAtlas-Token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    JsonNode waitTask(String path) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            var task = request(HttpMethod.GET, path, null);
            if (!List.of("QUEUED", "RUNNING").contains(task.get("status").asText())) return task;
            Thread.sleep(50);
        }
        throw new AssertionError("Task timed out");
    }

    @Test void privateCredentialsImportBindingSyncRotateAndRevokeStaySecretFree() throws Exception {
        String token = "ghp_" + "a".repeat(36);
        var created = request(HttpMethod.POST, "/api/github/credentials", Map.of("label", "fixture-private", "token", token));
        String credentialId = created.get("id").asText();
        assertThat(created.toString()).doesNotContain(token);
        assertThat(created.get("revokedAt").isNull()).isTrue();

        var imported = request(HttpMethod.POST, "/api/github/imports", Map.of("credentialId", credentialId,
                "url", "https://github.com/example/private-sample", "ref", "main"));
        var done = waitTask("/api/tasks/" + imported.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        String snapshot = done.get("snapshotId").asText();
        String project = jdbc.queryForObject("SELECT project_id FROM snapshots WHERE id=?", String.class, snapshot);

        var binding = request(HttpMethod.GET, "/api/github/projects/" + project + "/binding", null);
        assertThat(binding.get("bound").asBoolean()).isTrue();
        assertThat(binding.get("repoUrl").asText()).isEqualTo("https://github.com/example/private-sample");
        assertThat(binding.get("credentialLabel").asText()).isEqualTo("fixture-private");

        var check = request(HttpMethod.POST, "/api/github/projects/" + project + "/check", null);
        assertThat(check.get("remoteCommit").asText()).isEqualTo("remote-fixture-sha");
        assertThat(check.get("changed").asBoolean()).isTrue();

        var sync = request(HttpMethod.POST, "/api/github/projects/" + project + "/sync", null);
        var synced = waitTask("/api/tasks/" + sync.get("id").asText());
        assertThat(synced.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(synced.get("snapshotId").asText()).isNotEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM snapshots WHERE project_id=?", Integer.class, project)).isGreaterThanOrEqualTo(2);

        String rotatedToken = "ghp_" + "b".repeat(36);
        var rotated = request(HttpMethod.POST, "/api/github/credentials/" + credentialId + "/rotate", Map.of("token", rotatedToken));
        assertThat(rotated.toString()).doesNotContain(rotatedToken);
        var revoked = request(HttpMethod.DELETE, "/api/github/credentials/" + credentialId, null);
        assertThat(revoked.get("revokedAt").isNull()).isFalse();
        var listed = request(HttpMethod.GET, "/api/github/credentials", null);
        assertThat(listed.toString()).doesNotContain(token, rotatedToken);

        var afterRevoke = exchange(HttpMethod.POST, "/api/github/imports", Map.of("credentialId", credentialId,
                "url", "https://github.com/example/private-sample", "ref", "main"));
        assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(afterRevoke.getBody().get("code").asText()).isEqualTo("GITHUB_CREDENTIAL_REVOKED");
    }

    @Test void importsPublicRepositoryThroughExistingSnapshotPipeline() throws Exception {
        var imported = request(HttpMethod.POST, "/api/imports/github",
                Map.of("url", "https://github.com/example/sample", "ref", "main"));
        assertThat(imported.get("status").asText()).isEqualTo("QUEUED");
        assertThat(imported.get("sourceType").asText()).isEqualTo("GITHUB");
        assertThat(imported.get("sourceRef").asText()).isEqualTo("main");
        assertThat(imported.get("sourcePath").asText()).isEqualTo("https://github.com/example/sample");
        var done = waitTask("/api/tasks/" + imported.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        String snapshot = done.get("snapshotId").asText();
        assertThat(jdbc.queryForObject("SELECT source_type FROM projects WHERE source_path=?", String.class,
                "https://github.com/example/sample")).isEqualTo("GITHUB");
        assertThat(Path.of("target/test-data-github/github-staging").resolve(imported.get("id").asText())).doesNotExist();

        var indexed = request(HttpMethod.POST, "/api/snapshots/" + snapshot + "/index", Map.of("rebuild", false));
        var result = waitTask("/api/tasks/" + indexed.get("id").asText());
        assertThat(result.get("status").asText()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(request(HttpMethod.GET, "/api/snapshots/" + snapshot + "/symbols?query=pay&kind=METHOD", null)
                .get("total").asInt()).isEqualTo(4);

        var duplicate = request(HttpMethod.POST, "/api/imports/github",
                Map.of("url", "https://github.com/example/sample", "ref", "main"));
        assertThat(waitTask("/api/tasks/" + duplicate.get("id").asText()).get("snapshotId").asText()).isEqualTo(snapshot);
    }

    @Test void retriesGithubImportWithStoredRef() throws Exception {
        var imported = request(HttpMethod.POST, "/api/imports/github",
                Map.of("url", "https://github.com/example/retry-sample", "ref", "develop"));
        var failed = waitTask("/api/tasks/" + imported.get("id").asText());
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asText()).isEqualTo("GITHUB_NETWORK");
        assertThat(failed.get("sourceRef").asText()).isEqualTo("develop");

        var retried = request(HttpMethod.POST, "/api/tasks/" + imported.get("id").asText() + "/retry", Map.of());
        assertThat(retried.get("sourceRef").asText()).isEqualTo("develop");
        var done = waitTask("/api/tasks/" + retried.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("snapshotId").asText()).isNotBlank();
    }

    @Test void rejectsInvalidGithubUrlsAndRefsBeforeCreatingTasks() {
        var badUrl = exchange(HttpMethod.POST, "/api/imports/github", Map.of("url", "http://gitlab.com/owner/repo"));
        assertThat(badUrl.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badUrl.getBody().get("code").asText()).isEqualTo("INVALID_GITHUB_URL");

        var badRef = exchange(HttpMethod.POST, "/api/imports/github",
                Map.of("url", "https://github.com/owner/repo", "ref", "-main"));
        assertThat(badRef.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRef.getBody().get("code").asText()).isEqualTo("INVALID_GITHUB_REF");

        var nested = exchange(HttpMethod.POST, "/api/imports/github",
                Map.of("url", "https://github.com/owner/repo/tree/main"));
        assertThat(nested.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
