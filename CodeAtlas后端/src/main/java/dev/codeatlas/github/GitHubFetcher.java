package dev.codeatlas.github;

import dev.codeatlas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Fetches public GitHub repositories through the locally installed Git CLI.
 *
 * <p>The remote URL is restricted to repository roots on github.com. The clone is shallow,
 * uses an isolated HOME and Git configuration, and never executes repository code.</p>
 */
@Component
public class GitHubFetcher {
    public record Target(String url, String cloneUrl, String name) {}

    public static class FetchException extends RuntimeException {
        public final String code;
        public FetchException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final Set<String> HOSTS = Set.of("github.com", "www.github.com");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,198}");
    private static final Set<String> CLEARED_ENVIRONMENT = Set.of(
            "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_ASKPASS", "SSH_ASKPASS", "GIT_SSH",
            "GIT_SSH_COMMAND", "GIT_PROXY_COMMAND", "GIT_EXTERNAL_DIFF", "GIT_PAGER", "GIT_EDITOR",
            "GIT_SEQUENCE_EDITOR", "GIT_TEMPLATE_DIR", "GIT_NAMESPACE", "GIT_CEILING_DIRECTORIES",
            "GIT_DISCOVERY_ACROSS_FILESYSTEM", "GIT_CONFIG", "GIT_CONFIG_PARAMETERS", "GIT_SSL_NO_VERIFY");
    private static final int MAX_OUTPUT = 65_536;

    private final Path staging;
    private final Duration timeout;

    public GitHubFetcher(@Value("${codeatlas.data}") String data,
                         @Value("${codeatlas.github.timeout-seconds:180}") int timeoutSeconds) {
        this.staging = Path.of(data).toAbsolutePath().normalize().resolve("github-staging");
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public Target validate(String rawUrl, String rawRef) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty() || url.length() > 300) throw invalidUrl("请输入 GitHub 仓库地址");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw invalidUrl("仓库地址格式无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw invalidUrl("首版仅支持 https://github.com 公开仓库");
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!HOSTS.contains(host)) throw invalidUrl("首版仅支持 github.com 公开仓库");
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
            throw invalidUrl("仓库地址不能包含凭据、查询参数、片段或非标准端口");
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.toLowerCase(Locale.ROOT).endsWith(".git")) path = path.substring(0, path.length() - 4);
        String[] parts = path.split("/");
        if (parts.length != 3 || !parts[0].isEmpty() || !NAME.matcher(parts[1]).matches() || !NAME.matcher(parts[2]).matches()
                || parts[1].equals(".") || parts[1].equals("..") || parts[2].equals(".") || parts[2].equals(".."))
            throw invalidUrl("请输入仓库根地址，例如 https://github.com/owner/repo");
        String owner = parts[1].toLowerCase(Locale.ROOT);
        String repository = parts[2].toLowerCase(Locale.ROOT);
        String canonical = "https://github.com/" + owner + "/" + repository;
        String ref = rawRef == null ? null : rawRef.trim();
        if (ref != null && ref.isEmpty()) ref = null;
        if (ref != null) validateRef(ref);
        return new Target(canonical, canonical + ".git", parts[2]);
    }

    private static void validateRef(String ref) {
        if (!REF.matcher(ref).matches() || ref.startsWith("-") || ref.endsWith("/") || ref.endsWith(".")
                || ref.contains("..") || ref.contains("//") || ref.endsWith(".lock"))
            throw new ApiException(400, "INVALID_GITHUB_REF", "分支或标签名称无效");
    }

    public Path fetch(Target target, String ref, String taskId, BooleanSupplier cancelled) {
        return fetch(target, ref, taskId, cancelled, null);
    }

    public Path fetch(Target target, String ref, String taskId, BooleanSupplier cancelled, String token) {
        return cloneRepository(target.cloneUrl(), ref, taskId, cancelled, token);
    }

    Path cloneRepository(String cloneUrl, String ref, String taskId, BooleanSupplier cancelled) {
        return cloneRepository(cloneUrl, ref, taskId, cancelled, null);
    }

    Path cloneRepository(String cloneUrl, String ref, String taskId, BooleanSupplier cancelled, String token) {
        if (taskId == null || !taskId.matches("[0-9a-f-]{36}")) {
            throw new FetchException("GITHUB_CLONE_FAILED", "下载任务标识无效");
        }
        Path owned = staging.resolve(taskId).normalize();
        if (!owned.getParent().equals(staging)) throw new FetchException("GITHUB_CLONE_FAILED", "下载目录无效");
        try {
            Files.createDirectories(staging);
            if (Files.exists(owned, LinkOption.NOFOLLOW_LINKS)) throw new FetchException("GITHUB_CLONE_FAILED", "下载目录已存在");
            Files.createDirectory(owned);
            Path home = Files.createDirectory(owned.resolve("home"));
            Files.createDirectory(home.resolve(".config"));
            Files.createFile(home.resolve(".gitconfig"));
            Path template = Files.createDirectory(owned.resolve("template"));
            Path checkout = owned.resolve("repo");
            var command = new ArrayList<>(List.of(
                    "git",
                    "-c", "core.hooksPath=",
                    "-c", "core.fsmonitor=false",
                    "-c", "core.untrackedCache=false",
                    "-c", "core.autocrlf=false",
                    "-c", "core.symlinks=false",
                    "-c", "credential.helper=",
                    "-c", "filter.lfs.smudge=",
                    "-c", "filter.lfs.clean=",
                    "-c", "filter.lfs.required=false",
                    "clone", "--quiet", "--depth", "1", "--single-branch",
                    "--recurse-submodules=no", "--template=" + template));
            if (ref != null) {
                command.add("--branch");
                command.add(ref);
            }
            command.add("--");
            command.add(cloneUrl);
            command.add(checkout.toString());
            run(command, home, cancelled, token);
            return checkout;
        } catch (CancellationException | FetchException error) {
            deleteTree(owned);
            throw error;
        } catch (IOException error) {
            deleteTree(owned);
            if (error.getMessage() != null && error.getMessage().contains("Cannot run program")) {
                throw new FetchException("GITHUB_GIT_UNAVAILABLE", "本机未安装 Git，无法下载仓库");
            }
            throw new FetchException("GITHUB_CLONE_FAILED", "下载仓库失败");
        } catch (Exception error) {
            deleteTree(owned);
            throw new FetchException("GITHUB_CLONE_FAILED", "下载仓库失败");
        }
    }

    public void cleanup(Path checkout) {
        if (checkout == null) return;
        Path owned = checkout.toAbsolutePath().normalize();
        if (!"repo".equals(owned.getFileName() == null ? "" : owned.getFileName().toString())
                || owned.getParent() == null || !staging.equals(owned.getParent().getParent())) return;
        deleteTree(owned.getParent());
    }

    /** Removes staging directories left behind by a process crash before workers start. */
    public void cleanupStale() {
        if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) return;
        try (var children = Files.list(staging)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        && child.getFileName().toString().matches("[0-9a-f-]{36}")) deleteTree(child);
            }
        } catch (IOException ignored) {
        }
    }

    private void run(List<String> command, Path home, BooleanSupplier cancelled) throws IOException, InterruptedException {
        run(command, home, cancelled, null);
    }

    private void run(List<String> command, Path home, BooleanSupplier cancelled, String token) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command);
        var environment = builder.environment();
        for (String name : new ArrayList<>(environment.keySet())) {
            if (name.startsWith("GIT_CONFIG_") || name.startsWith("GIT_TRACE") || name.startsWith("GIT_CURL_")
                    || CLEARED_ENVIRONMENT.contains(name)) environment.remove(name);
        }
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", home.resolve(".gitconfig").toString());
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "never");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        environment.put("LC_ALL", "C");
        environment.put("LANG", "C");
        if (token != null && !token.isBlank()) {
            // Keep the token out of argv and URLs; Git reads this temporary HTTP header from the child environment.
            environment.put("GIT_CONFIG_COUNT", "1");
            environment.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            environment.put("GIT_CONFIG_VALUE_0", "Authorization: Bearer " + token);
        }
        var process = builder.start();
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var outDrain = Thread.startVirtualThread(() -> drain(process.getInputStream(), output));
        var errorDrain = Thread.startVirtualThread(() -> drain(process.getErrorStream(), errors));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    destroy(process);
                    throw new CancellationException();
                }
                if (System.nanoTime() >= deadline) {
                    destroy(process);
                    throw new FetchException("GITHUB_TIMEOUT", "下载仓库超时，请重试或改用更小的仓库");
                }
            }
            outDrain.join(1000);
            errorDrain.join(1000);
            if (process.exitValue() != 0) throw failure(errors.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            destroy(process);
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } finally {
            if (process.isAlive()) destroy(process);
        }
    }

    private static FetchException failure(String stderr) {
        String text = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (text.contains("remote branch") || text.contains("couldn't find remote ref") || text.contains("not found in upstream"))
            return new FetchException("GITHUB_REF_NOT_FOUND", "指定的分支或标签不存在");
        if (text.contains("authentication failed") || text.contains("could not read username")
                || text.contains("terminal prompts disabled") || text.contains("permission denied"))
            return new FetchException("GITHUB_AUTH_REQUIRED", "仓库需要认证或凭据无权限，请检查 GitHub 凭据");
        if (text.contains("repository not found") || text.contains("does not exist") || text.contains("error: 404")
                || (text.contains("fatal: repository") && text.contains("not found")))
            return new FetchException("GITHUB_NOT_FOUND", "仓库不存在或凭据无权访问");
        if (text.contains("could not resolve host") || text.contains("unable to access")
                || text.contains("connection") || text.contains("ssl") || text.contains("timed out"))
            return new FetchException("GITHUB_NETWORK", "无法连接 GitHub，请检查网络");
        return new FetchException("GITHUB_CLONE_FAILED", "下载仓库失败");
    }

    private static void drain(InputStream input, ByteArrayOutputStream buffer) {
        try (input) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                if (buffer.size() < MAX_OUTPUT) buffer.write(chunk, 0, Math.min(read, MAX_OUTPUT - buffer.size()));
            }
        } catch (IOException ignored) {
        }
    }

    private static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static ApiException invalidUrl(String message) {
        return new ApiException(400, "INVALID_GITHUB_URL", message);
    }
}
