package dev.codeatlas.execution;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Process-backed Docker adapter; every lifecycle action is scoped to the job's own cidfile/container id. */
@Component
public class ProcessDockerRunner implements DockerRunner {
    private static final int MAX_LOG = 65_536;

    @Override
    public boolean available() {
        var result = exec(List.of("docker", "version", "--format", "{{.Server.Version}}"), 5);
        return result.exitCode() == 0;
    }

    @Override
    public RunResult run(RunSpec spec) {
        Process process = null;
        var output = new BoundedBuffer();
        var errors = new BoundedBuffer();
        boolean timedOut = false;
        boolean cancelled = false;
        boolean interrupted = false;
        String containerId = "";
        int exitCode = -1;
        CleanupResult cleanup;
        try {
            Files.createDirectories(spec.cidFile().getParent());
            Files.deleteIfExists(spec.cidFile());
            process = start(command(spec));
            var running = process;
            var outThread = Thread.startVirtualThread(() -> drain(running.getInputStream(), output));
            var errThread = Thread.startVirtualThread(() -> drain(running.getErrorStream(), errors));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(spec.timeoutSeconds());
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (spec.cancelled().getAsBoolean()) { cancelled = true; break; }
                if (System.nanoTime() >= deadline) { timedOut = true; break; }
            }
            containerId = readContainerId(spec.cidFile(), 500);
            if (!timedOut && !cancelled) exitCode = process.exitValue();
            if (!process.isAlive()) {
                outThread.join(1_500);
                errThread.join(1_500);
            }
        } catch (InterruptedException error) {
            interrupted = true;
            cancelled = true;
        } catch (Exception error) {
            byte[] message = ("docker run failed: " + safe(error.getMessage())).getBytes(StandardCharsets.UTF_8);
            errors.write(message, message.length);
        } finally {
            // Stop the client before resolving the name, so it cannot create a container after cleanup.
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    interrupted = true;
                    Thread.interrupted();
                }
            }
            cleanup = recover(spec.jobId(), spec.containerName());
            if (interrupted) Thread.currentThread().interrupt();
        }
        return new RunResult(containerId, exitCode, output.text(), errors.text(), timedOut, cancelled,
                cleanup.status(), cleanup.message());
    }

    Process start(List<String> command) throws java.io.IOException {
        return new ProcessBuilder(command).start();
    }

    @Override
    public CleanupResult recover(String jobId, String containerName) {
        if (!("codeatlas-job-" + jobId).equals(containerName))
            return new CleanupResult("CLEANUP_FAILED", "容器名称与任务不匹配");
        var inspect = exec(List.of("docker", "inspect", "--format",
                "{{.Id}}|{{index .Config.Labels \"codeatlas.job\"}}", containerName), 5);
        if (inspect.exitCode() != 0) {
            if (inspect.output().contains("No such object") || inspect.output().contains("No such container"))
                return new CleanupResult("NOT_CREATED", "任务容器不存在");
            return new CleanupResult("CLEANUP_FAILED", "无法核验任务容器: " + safe(inspect.output()));
        }
        var identity = inspect.output().trim().split("\\|", -1);
        if (identity.length != 2 || !identity[0].matches("[a-fA-F0-9]{64}") || !jobId.equals(identity[1]))
            return new CleanupResult("CLEANUP_FAILED", "容器任务标签不匹配，已拒绝清理");
        return stop(identity[0]);
    }

    List<String> command(RunSpec spec) {
        return new ArrayList<>(List.of("docker", "run",
                "--name", spec.containerName(), "--cidfile", spec.cidFile().toString(),
                "--label", "codeatlas.job=" + spec.jobId(),
                "--network=none", "--cpus=1", "--memory=512m", "--pids-limit=128",
                "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "--tmpfs", "/root/.m2:rw,noexec,nosuid,size=128m",
                "-w", "/workspace", "-v", spec.workspace() + ":/workspace:rw",
                "-u", "65534:65534", spec.image(), "mvn", "-q", "-DskipTests=false", "test"));
    }

    @Override
    public CleanupResult stop(String containerId) {
        if (containerId == null || containerId.isBlank())
            return new CleanupResult("NOT_CREATED", "未生成容器标识，未执行删除");
        exec(List.of("docker", "stop", "--time", "5", containerId), 20);
        var remove = exec(List.of("docker", "rm", "-f", containerId), 20);
        var inspect = exec(List.of("docker", "inspect", "-f", "{{.State.Running}}", containerId), 5);
        boolean gone = inspect.exitCode() != 0
                && (inspect.output().contains("No such object") || inspect.output().contains("No such container"));
        if (remove.exitCode() == 0 && gone) return new CleanupResult("CLEANED", "容器已停止并删除");
        return new CleanupResult("CLEANUP_FAILED", "容器清理未确认: " + safe(remove.output()));
    }

    private String readContainerId(Path cid, long waitMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        do {
            try {
                if (Files.isRegularFile(cid)) {
                    var value = Files.readString(cid, StandardCharsets.UTF_8).trim();
                    if (!value.isBlank()) return value;
                }
            } catch (Exception ignored) { }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return "";
    }

    ExecResult exec(List<String> command, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var running = process;
            var output = new BoundedBuffer();
            var reader = Thread.startVirtualThread(() -> drain(running.getInputStream(), output));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
                return new ExecResult(-1, "timeout");
            reader.join(1_500);
            return new ExecResult(process.exitValue(), output.text());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new ExecResult(-1, "interrupted");
        } catch (Exception error) {
            return new ExecResult(-1, safe(error.getMessage()));
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static void drain(InputStream input, BoundedBuffer output) {
        try (input) {
            var buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, read);
        } catch (Exception ignored) { }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }

    record ExecResult(int exitCode, String output) {}

    private static final class BoundedBuffer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;

        synchronized void write(byte[] buffer, int length) {
            int remaining = MAX_LOG - bytes.size();
            if (remaining <= 0) { truncated = true; return; }
            if (length > remaining) { truncated = true; bytes.write(buffer, 0, remaining); }
            else bytes.write(buffer, 0, length);
        }

        synchronized String text() {
            return bytes.toString(StandardCharsets.UTF_8) + (truncated ? "\n[log truncated]" : "");
        }
    }
}
