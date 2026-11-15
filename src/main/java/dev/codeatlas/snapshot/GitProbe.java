package dev.codeatlas.snapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class GitProbe {
    record GitInfo(String kind, String commit, String status, String revision) {}

    GitInfo inspect(Path root) {
        try {
            var inside = run(root, "rev-parse", "--is-inside-work-tree");
            if (inside.exit() != 0) {
                if (inside.error().contains("not a git repository")) return new GitInfo("NON_GIT", null, "", "NON_GIT");
                return new GitInfo("UNKNOWN", null, "Git 检测失败", "UNKNOWN");
            }
            var head = run(root, "rev-parse", "--verify", "HEAD");
            var filters = run(root, "config", "--null", "--name-only", "--get-regexp", "^filter\\..*\\.(clean|process)$");
            if (filters.exit() > 1) return new GitInfo("UNKNOWN", null, "Git 配置读取失败", "UNKNOWN");
            var tracked = run(root, "ls-files", "--stage", "-z");
            if (tracked.exit() != 0 || Arrays.stream(tracked.text().split("\u0000")).anyMatch(entry -> entry.startsWith("160000 ")))
                return new GitInfo("UNKNOWN", head.exit() == 0 ? head.text().trim() : null, "子模块或索引状态无法安全核对", "SUBMODULE_OR_INDEX_ERROR");
            if (filters.exit() == 0 && !filters.text().isBlank()) {
                var names = run(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard");
                var attributes = runInput(root, names.text(), "check-attr", "-z", "--stdin", "filter");
                if (names.exit() != 0 || attributes.exit() != 0) return new GitInfo("UNKNOWN", null, "Git 属性读取失败", "UNKNOWN");
                String[] fields = attributes.text().split("\u0000");
                for (int i = 2; i < fields.length; i += 3) {
                    if (!Set.of("unspecified", "unset").contains(fields[i]))
                        return new GitInfo("UNKNOWN", head.exit() == 0 ? head.text().trim() : null, "检测到外部过滤器，已跳过工作区状态命令", "FILTERS_PRESENT");
                }
            }
            var status = run(root, "status", "--porcelain=v1", "--untracked-files=all");
            var prefix = run(root, "rev-parse", "--show-prefix");
            if (status.exit() != 0 || prefix.exit() != 0 || !status.error().isBlank()) return new GitInfo("UNKNOWN", null, "Git 状态读取失败或存在警告", "UNKNOWN");
            String commit = head.exit() == 0 ? head.text().trim() : null;
            String kind = commit != null && status.text().isBlank() ? "GIT_CLEAN" : "GIT_DIRTY";
            return new GitInfo(kind, commit, status.text(), String.valueOf(commit) + "|" + prefix.text() + "|" + status.text());
        } catch (Exception e) {
            return new GitInfo("UNKNOWN", null, "Git 不可用或超时", "UNKNOWN");
        }
    }

    private record Output(int exit, String text, String error) {}
    private Output run(Path root, String... arguments) throws Exception {
        return runInput(root, "", arguments);
    }
    private Output runInput(Path root, String inputText, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.fsmonitor=false",
                "-c", "core.untrackedCache=false", "-c", "core.hooksPath=", "-c", "core.excludesFile=",
                "-c", "core.attributesFile=", "-C", root.toString()));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("LC_ALL", "C");
        var process = builder.start();
        // Drain concurrently to avoid blocking on a large working tree status.
        var output = new java.io.ByteArrayOutputStream();
        var errors = new java.io.ByteArrayOutputStream();
        Thread drain = Thread.startVirtualThread(() -> {
            try (var input = process.getInputStream()) { output.write(input.readNBytes(2_097_153)); } catch (Exception ignored) {}
        });
        Thread errorDrain = Thread.startVirtualThread(() -> {
            try (var input = process.getErrorStream()) { errors.write(input.readNBytes(2_097_153)); } catch (Exception ignored) {}
        });
        try (var input = process.getOutputStream()) { input.write(inputText.getBytes(StandardCharsets.UTF_8)); }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            drain.join(1000);
            errorDrain.join(1000);
            throw new IllegalStateException("GIT_TIMEOUT");
        }
        drain.join(1000);
        errorDrain.join(1000);
        if (output.size() > 2_097_152 || errors.size() > 2_097_152) throw new IllegalStateException("GIT_OUTPUT_LIMIT");
        return new Output(process.exitValue(), output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }
}
