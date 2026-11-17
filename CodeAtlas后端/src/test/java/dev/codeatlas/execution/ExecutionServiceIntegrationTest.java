package dev.codeatlas.execution;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.store.AtlasStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${TEST_EXEC_DB_URL:jdbc:h2:mem:execution;MODE=MySQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}",
        "codeatlas.data=target/execution-test-data", "codeatlas.executor.enabled=true",
        "codeatlas.executor.timeout-seconds=30"})
@Import(ExecutionServiceIntegrationTest.FakeConfig.class)
class ExecutionServiceIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired AtlasStore store;
    @Autowired ExecutionService executionService;
    @Autowired FakeDockerRunner docker;

    @BeforeEach void resetDocker() {
        docker.runs.clear();
        docker.stopped.clear();
        docker.next = new DockerRunner.RunResult("cid-default", 0, "", "", false, false, "CLEANED", "removed");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean @Primary FakeDockerRunner fakeDockerRunner() { return new FakeDockerRunner(); }
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        return http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    JsonNode waitJob(String id) throws Exception {
        JsonNode job = null;
        for (int index = 0; index < 200; index++) {
            job = call(HttpMethod.GET, "/executions/" + id, null).getBody();
            assertThat(job).isNotNull();
            if (!Set.of("QUEUED", "RUNNING").contains(job.get("status").asText())) return job;
            Thread.sleep(25);
        }
        throw new AssertionError("execution timeout: " + job);
    }

    @Test void executionUsesImmutableSubmissionAndIsIdempotent() throws Exception {
        var fixture = seed("class Example { int run() { return 1; } }");
        docker.next = new DockerRunner.RunResult("cid-pass", 0, "BUILD SUCCESS", "", false, false, "CLEANED", "removed");
        String key = UUID.randomUUID().toString();
        var body = Map.of("submissionId", fixture.submissionId, "idempotencyKey", key);
        var created = call(HttpMethod.POST, "/executions", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String jobId = created.getBody().get("id").asText();
        var job = waitJob(jobId);
        assertThat(job.get("status").asText()).isEqualTo("PASSED");
        assertThat(job.get("projectId").asText()).isEqualTo(fixture.projectId);
        assertThat(job.get("snapshotId").asText()).isEqualTo(fixture.snapshotId);
        assertThat(job.get("taskId").asText()).isEqualTo(fixture.taskId);
        assertThat(job.get("submissionId").asText()).isEqualTo(fixture.submissionId);
        assertThat(job.get("contentDigest").asText()).isEqualTo(fixture.contentDigest);
        assertThat(docker.runs).hasSize(1);
        var copy = docker.runs.getFirst().workspace().resolve("Example.java");
        assertThat(Files.readString(copy)).contains("return 1");
        Files.writeString(fixture.workspace.resolve("Example.java"), "class Example { int run() { return 9; } }");

        var replay = call(HttpMethod.POST, "/executions", body);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody().get("id").asText()).isEqualTo(jobId);
        assertThat(docker.runs).hasSize(1);
        assertThat(Files.readString(copy)).contains("return 1");

        var conflict = call(HttpMethod.POST, "/executions", Map.of("submissionId", seed("class Example { int run() { return 2; } }").submissionId, "idempotencyKey", key));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(docker.runs).hasSize(1);

        var legacy = call(HttpMethod.POST, "/executions", Map.of("submissionId", fixture.submissionId, "workspace", fixture.workspace.toString(), "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(legacy.getBody().get("code").asText()).isEqualTo("EXECUTOR_LEGACY_REQUEST");
        assertThat(call(HttpMethod.POST, "/executions", Map.of("submissionId", UUID.randomUUID().toString(), "idempotencyKey", UUID.randomUUID().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void timeoutAndBuildFailureAreClassified() throws Exception {
        var timeout = seed("class Example { int run() { return 1; } }");
        docker.next = new DockerRunner.RunResult("cid-timeout", -1, "", "", true, false, "CLEANED", "stopped");
        var timeoutJob = waitJob(call(HttpMethod.POST, "/executions", Map.of("submissionId", timeout.submissionId, "idempotencyKey", UUID.randomUUID().toString())).getBody().get("id").asText());
        assertThat(timeoutJob.get("status").asText()).isEqualTo("TIMEOUT");
        assertThat(timeoutJob.get("resultClass").asText()).isEqualTo("TIMEOUT");
        assertThat(timeoutJob.get("containerId").asText()).isEqualTo("cid-timeout");
        assertThat(timeoutJob.get("cleanupStatus").asText()).isEqualTo("CLEANED");

        var build = seed("class Example { int run() { return 1; } }");
        docker.next = new DockerRunner.RunResult("cid-build", 1, "COMPILATION ERROR: bad source", "", false, false, "CLEANED", "removed");
        var buildJob = waitJob(call(HttpMethod.POST, "/executions", Map.of("submissionId", build.submissionId, "idempotencyKey", UUID.randomUUID().toString())).getBody().get("id").asText());
        assertThat(buildJob.get("status").asText()).isEqualTo("BUILD_ERROR");
        assertThat(buildJob.get("resultClass").asText()).isEqualTo("BUILD_ERROR");

        var testFailure = seed("class Example { int run() { return 1; } }");
        docker.next = new DockerRunner.RunResult("cid-test", 1, "Tests run: 1, Failures: 1, Errors: 0", "", false, false, "CLEANED", "removed");
        var testJob = waitJob(call(HttpMethod.POST, "/executions", Map.of("submissionId", testFailure.submissionId, "idempotencyKey", UUID.randomUUID().toString())).getBody().get("id").asText());
        assertThat(testJob.get("status").asText()).isEqualTo("TEST_FAILED");
        assertThat(testJob.get("resultClass").asText()).isEqualTo("TEST_FAILED");
    }

    @Test void restartRecoveryStopsOnlyTheRecordedContainer() throws Exception {
        var fixture = seed("class Example { int run() { return 1; } }");
        String jobId = UUID.randomUUID().toString();
        store.update("INSERT INTO execution_jobs(id,project_id,snapshot_id,task_id,status,container_id,container_name,created_at) VALUES(?,?,?,?,?,?,?,?)",
                jobId, fixture.projectId, fixture.snapshotId, fixture.taskId, "RUNNING", "cid-recover", "codeatlas-job-" + jobId, AtlasStore.now());
        executionService.recoverInterrupted();
        var job = executionService.job(jobId);
        assertThat(job.get("status")).isEqualTo("INTERRUPTED");
        assertThat(docker.stopped).contains("cid-recover");
    }

    @Test void restartRecoveryResolvesNameWithoutPersistedContainerId() throws Exception {
        var fixture = seed("class Example {}");
        String jobId = UUID.randomUUID().toString();
        store.update("INSERT INTO execution_jobs(id,project_id,snapshot_id,task_id,status,created_at) VALUES(?,?,?,?,?,?)",
                jobId, fixture.projectId, fixture.snapshotId, fixture.taskId, "RUNNING", AtlasStore.now());
        executionService.recoverInterrupted();
        assertThat(executionService.job(jobId).get("cleanupStatus")).isEqualTo("CLEANED");
        assertThat(docker.stopped).contains("cid-recover");
    }

    @Test void tamperedArchiveFailsBeforeDockerStarts() throws Exception {
        var fixture = seed("class Example { int run() { return 1; } }");
        Files.writeString(fixture.archive.resolve("Example.java"), "tampered");
        var response = call(HttpMethod.POST, "/executions", Map.of("submissionId", fixture.submissionId, "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SUBMISSION_CORRUPTED");
        assertThat(docker.runs).isEmpty();
    }

    private Fixture seed(String source) throws Exception {
        String project = UUID.randomUUID().toString(), snapshot = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString(), workspaceId = UUID.randomUUID().toString();
        String submission = UUID.randomUUID().toString();
        store.update("INSERT INTO projects(id,name,source_path,path_hash,created_at) VALUES(?,?,?,?,?)",
                project, "Execution", "target/execution-source-" + project, project, AtlasStore.now());
        store.update("INSERT INTO snapshots(id,project_id,manifest_hash,identity_hash,source_kind,storage_key,policy_version,created_at) VALUES(?,?,?,?,?,?,?,?)",
                snapshot, project, snapshot, snapshot, "NON_GIT", "snapshot-" + snapshot, "test", AtlasStore.now());
        store.update("INSERT INTO training_tasks(id,title,status,revision,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                task, "Execution", "ACTIVE", 1, AtlasStore.now(), AtlasStore.now());
        store.update("INSERT INTO training_task_revisions(task_id,revision,payload,definition_digest,created_at) VALUES(?,?,?,?,?)",
                task, 1, "{}", "0".repeat(64), AtlasStore.now());
        store.update("INSERT INTO practice_workspaces(id,task_id,task_revision,snapshot_id,path_key,status,created_at) VALUES(?,?,?,?,?,?,?)",
                workspaceId, task, 1, snapshot, "workspace-" + workspaceId, "ACTIVE", AtlasStore.now());
        Path practice = Path.of("target/execution-test-data/practice").toAbsolutePath();
        Path workspace = practice.resolve("workspace-" + workspaceId);
        Path archive = practice.resolve("submission-" + submission);
        Files.createDirectories(workspace);
        Files.createDirectories(archive);
        Files.writeString(workspace.resolve("Example.java"), source);
        Files.writeString(archive.resolve("Example.java"), source);
        var manifest = new TreeMap<String, String>();
        manifest.put("Example.java", SnapshotScanner.hash(source.getBytes(StandardCharsets.UTF_8)));
        String digest = SnapshotScanner.hash(store.encode(manifest));
        store.update("INSERT INTO practice_submissions(id,workspace_id,sequence_no,content_digest,manifest_json,diff_json,path_key,submitted_at) VALUES(?,?,?,?,?,?,?,?)",
                submission, workspaceId, 1, digest, store.encode(Map.of("files", manifest)), "{\"items\":[]}", "submission-" + submission, AtlasStore.now());
        return new Fixture(project, snapshot, task, workspaceId, submission, digest, workspace, archive);
    }

    record Fixture(String projectId, String snapshotId, String taskId, String workspaceId, String submissionId,
                   String contentDigest, Path workspace, Path archive) {}

    static class FakeDockerRunner implements DockerRunner {
        volatile RunResult next = new RunResult("cid-default", 0, "", "", false, false, "CLEANED", "removed");
        final List<RunSpec> runs = new CopyOnWriteArrayList<>();
        final List<String> stopped = new CopyOnWriteArrayList<>();

        @Override public boolean available() { return true; }
        @Override public RunResult run(RunSpec spec) { runs.add(spec); return next; }
        @Override public CleanupResult recover(String jobId, String containerName) {
            return stop("cid-recover");
        }
        @Override public CleanupResult stop(String containerId) { stopped.add(containerId); return new CleanupResult("CLEANED", "stopped"); }
    }
}
