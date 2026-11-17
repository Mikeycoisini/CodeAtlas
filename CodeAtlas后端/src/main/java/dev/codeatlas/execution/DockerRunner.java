package dev.codeatlas.execution;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Small Docker boundary so lifecycle, timeout and cleanup behaviour can be tested without a daemon. */
public interface DockerRunner {
    boolean available();

    RunResult run(RunSpec spec);

    CleanupResult stop(String containerId);

    CleanupResult recover(String jobId, String containerName);

    record RunSpec(String jobId, String containerName, String image, Path workspace, Path cidFile,
                   int timeoutSeconds, BooleanSupplier cancelled) {}

    record RunResult(String containerId, int exitCode, String stdout, String stderr,
                     boolean timedOut, boolean cancelled, String cleanupStatus, String cleanupMessage) {}

    record CleanupResult(String status, String message) {}
}
