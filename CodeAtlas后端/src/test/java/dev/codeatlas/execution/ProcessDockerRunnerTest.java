package dev.codeatlas.execution;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessDockerRunnerTest {
    private final ProcessDockerRunner runner = new ProcessDockerRunner();

    @Test void commandUsesJobScopedNameCidfileAndNoShell() {
        var spec = new DockerRunner.RunSpec("job-1", "codeatlas-job-job-1", "maven:test",
                Path.of("workspace"), Path.of("container.cid"), 30, () -> false);
        var command = runner.command(spec);
        assertThat(command).contains("--name", "codeatlas-job-job-1", "--cidfile", "container.cid",
                "--label", "codeatlas.job=job-1", "--network=none", "--pids-limit=128",
                "-v", "workspace:/workspace:rw");
        assertThat(command).doesNotContain("sh", "-c", "/bin/sh");
        assertThat(command).endsWith("mvn", "-q", "-DskipTests=false", "test");
    }

    @Test void cleanupWithoutContainerIdIsExplicitlySkipped() {
        var cleanup = runner.stop("");
        assertThat(cleanup.status()).isEqualTo("NOT_CREATED");
        assertThat(cleanup.message()).contains("未生成容器标识");
    }

    @Test void recoveryRequiresMatchingLabelAndRemovesByImmutableId() {
        var commands = new java.util.ArrayList<java.util.List<String>>();
        var adapter = new ProcessDockerRunner() {
            @Override ExecResult exec(java.util.List<String> command, int timeout) {
                commands.add(command);
                if (command.contains("--format")) return new ExecResult(0, "a".repeat(64) + "|job-1");
                if (command.contains("-f") && command.contains("inspect"))
                    return new ExecResult(1, "Error: No such object");
                return new ExecResult(0, "removed");
            }
        };
        assertThat(adapter.recover("job-1", "codeatlas-job-job-1").status()).isEqualTo("CLEANED");
        assertThat(commands.get(1)).contains("stop", "a".repeat(64));
        assertThat(commands.get(2)).contains("rm", "a".repeat(64));
    }

    @Test void recoveryRefusesForeignContainerAndDaemonErrors() {
        var commands = new java.util.ArrayList<java.util.List<String>>();
        var adapter = new ProcessDockerRunner() {
            @Override ExecResult exec(java.util.List<String> command, int timeout) {
                commands.add(command);
                return new ExecResult(0, "a".repeat(64) + "|another-job");
            }
        };
        assertThat(adapter.recover("job-1", "codeatlas-job-job-1").status()).isEqualTo("CLEANUP_FAILED");
        assertThat(commands).hasSize(1);
        var unavailable = new ProcessDockerRunner() {
            @Override ExecResult exec(java.util.List<String> command, int timeout) {
                return new ExecResult(-1, "Cannot connect to daemon");
            }
        };
        assertThat(unavailable.recover("job-1", "codeatlas-job-job-1").status()).isEqualTo("CLEANUP_FAILED");
    }

    @Test void daemonFailureAfterRemovalDoesNotConfirmCleanup() {
        var adapter = new ProcessDockerRunner() {
            @Override ExecResult exec(java.util.List<String> command, int timeout) {
                if (command.contains("inspect")) return new ExecResult(-1, "daemon disconnected");
                return new ExecResult(0, "removed");
            }
        };
        assertThat(adapter.stop("a".repeat(64)).status()).isEqualTo("CLEANUP_FAILED");
    }

    @Test void startupExceptionStillAttemptsOwnedContainerCleanup(@org.junit.jupiter.api.io.TempDir Path temp) {
        var recovered = new java.util.ArrayList<String>();
        var adapter = new ProcessDockerRunner() {
            @Override Process start(java.util.List<String> command) throws java.io.IOException {
                throw new java.io.IOException("start failed");
            }
            @Override public CleanupResult recover(String jobId, String name) {
                recovered.add(jobId);
                return new CleanupResult("CLEANED", "removed");
            }
        };
        var result = adapter.run(new DockerRunner.RunSpec("job-1", "codeatlas-job-job-1", "test",
                temp, temp.resolve("container.cid"), 30, () -> false));
        assertThat(recovered).containsExactly("job-1");
        assertThat(result.cleanupStatus()).isEqualTo("CLEANED");
        assertThat(result.exitCode()).isEqualTo(-1);
    }

    @Test void interruptionDestroysClientCleansContainerAndRestoresInterrupt(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        var destroyed = new java.util.concurrent.atomic.AtomicBoolean();
        var process = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long time, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                if (time == 250) throw new InterruptedException("shutdown");
                return true;
            }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { destroyed.set(true); }
            @Override public Process destroyForcibly() { destroy(); return this; }
            @Override public boolean isAlive() { return !destroyed.get(); }
            @Override public java.util.stream.Stream<ProcessHandle> descendants() { return java.util.stream.Stream.empty(); }
        };
        var recovered = new java.util.ArrayList<String>();
        var adapter = new ProcessDockerRunner() {
            @Override Process start(java.util.List<String> command) { return process; }
            @Override public CleanupResult recover(String jobId, String name) {
                assertThat(Thread.currentThread().isInterrupted()).isFalse();
                recovered.add(name);
                return new CleanupResult("CLEANED", "removed");
            }
        };
        try {
            var result = adapter.run(new DockerRunner.RunSpec("job-1", "codeatlas-job-job-1", "test",
                    temp, temp.resolve("container.cid"), 30, () -> false));
            assertThat(result.cancelled()).isTrue();
            assertThat(result.cleanupStatus()).isEqualTo("CLEANED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(recovered).containsExactly("codeatlas-job-job-1");
            assertThat(destroyed.get()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test void resultClassificationSeparatesTimeoutBuildAndTestFailures() {
        assertThat(ExecutionService.classify(0, "", "", false, false)).isEqualTo("PASSED");
        assertThat(ExecutionService.classify(1, "COMPILATION ERROR", "", false, false)).isEqualTo("BUILD_ERROR");
        assertThat(ExecutionService.classify(1, "Tests run: 2, Failures: 1, Errors: 0", "", false, false)).isEqualTo("TEST_FAILED");
        assertThat(ExecutionService.classify(-1, "", "", true, false)).isEqualTo("TIMEOUT");
        assertThat(ExecutionService.classify(-1, "", "", false, true)).isEqualTo("CANCELLED");
        assertThat(ExecutionService.classify(2, "unknown failure", "", false, false)).isEqualTo("FAILED");
    }
}
