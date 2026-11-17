package dev.codeatlas.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.tasks.TaskService;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Manual private-repository update checks. Automatic scheduling remains opt-in and is not enabled here. */
@Service
public class GitHubSyncService {
    private final AtlasStore store;
    private final GitHubCredentialService credentials;
    private final TaskService tasks;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();

    public GitHubSyncService(AtlasStore store, GitHubCredentialService credentials, TaskService tasks, ObjectMapper json) {
        this.store = store; this.credentials = credentials; this.tasks = tasks; this.json = json;
    }

    public Map<String, Object> check(String projectId) {
        var binding = binding(projectId);
        var token = credentials.token((String) binding.get("credentialId"));
        var remote = remoteHead((String) binding.get("repoUrl"), (String) binding.get("ref"), token);
        var latest = store.rows("SELECT commit_hash FROM snapshots WHERE project_id=? ORDER BY created_at DESC LIMIT 1", projectId);
        String current = latest.isEmpty() ? null : (String) latest.getFirst().get("commitHash");
        store.update("UPDATE github_repo_bindings SET last_checked_at=? WHERE project_id=?", AtlasStore.now(), projectId);
        var result = new LinkedHashMap<String, Object>();
        result.put("projectId", projectId); result.put("repoUrl", binding.get("repoUrl")); result.put("ref", binding.get("ref"));
        result.put("remoteCommit", remote); result.put("snapshotCommit", current); result.put("changed", remote != null && !remote.equals(current));
        result.put("checkedAt", AtlasStore.now()); result.put("automatic", false);
        return result;
    }

    public Map<String, Object> sync(String projectId) {
        var binding = binding(projectId);
        return tasks.importGithub((String) binding.get("repoUrl"), (String) binding.get("ref"), (String) binding.get("credentialId"));
    }

    /** Resolve the project first so "missing" and "unbound" remain distinct before any network call. */
    private Map<String, Object> binding(String projectId) {
        store.one("SELECT id FROM projects WHERE id=?", projectId);
        var binding = credentials.binding(projectId);
        if (binding == null || !Boolean.TRUE.equals(binding.get("bound")))
            throw new ApiException(404, "GITHUB_NOT_BOUND", "当前项目没有绑定私有 GitHub 仓库");
        return binding;
    }

    protected String remoteHead(String repoUrl, String ref, String token) {
        var target = new GitHubFetcher.Target(repoUrl, repoUrl + ".git", repoUrl.substring(repoUrl.lastIndexOf('/') + 1));
        var ownerRepo = target.url().replace("https://github.com/", "");
        var url = "https://api.github.com/repos/" + ownerRepo + "/commits?per_page=1";
        if (ref != null && !ref.isBlank()) url += "&sha=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CodeAtlas")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new ApiException(409, "GITHUB_CREDENTIAL_INVALID", "GitHub 凭据过期或无仓库权限");
            if (response.statusCode() == 404) throw new ApiException(404, "GITHUB_NOT_FOUND", "仓库或分支不存在");
            if (response.statusCode() != 200) throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub 更新检查失败");
            JsonNode body = json.readTree(response.body());
            if (!body.isArray() || body.isEmpty()) throw new ApiException(404, "GITHUB_REF_NOT_FOUND", "未找到指定分支或标签");
            return body.get(0).path("sha").asText(null);
        } catch (ApiException error) { throw error;
        } catch (Exception error) {
            throw new ApiException(503, "GITHUB_UNAVAILABLE", "无法连接 GitHub 检查更新");
        }
    }
}
