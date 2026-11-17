package dev.codeatlas.github;

import dev.codeatlas.tasks.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/github")
public class GitHubController {
    public record CredentialRequest(String label, String token) {}
    public record TokenRequest(String token) {}
    public record PrivateImportRequest(String credentialId, String url, String ref) {}
    private final GitHubCredentialService credentials;
    private final GitHubSyncService sync;
    private final TaskService tasks;

    public GitHubController(GitHubCredentialService credentials, GitHubSyncService sync, TaskService tasks) {
        this.credentials = credentials; this.sync = sync; this.tasks = tasks;
    }

    @GetMapping("/credentials") public Object list() { return credentials.list(); }
    @PostMapping("/credentials") public ResponseEntity<?> create(@RequestBody CredentialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentials.create(request.label(), request.token()));
    }
    @DeleteMapping("/credentials/{id}") public Object revoke(@PathVariable String id) { return credentials.revoke(id); }
    @PostMapping("/credentials/{id}/rotate") public Object rotate(@PathVariable String id, @RequestBody TokenRequest request) {
        return credentials.rotate(id, request.token());
    }
    @PostMapping("/imports") public ResponseEntity<?> importPrivate(@RequestBody PrivateImportRequest request) {
        return ResponseEntity.accepted().body(tasks.importGithub(request.url(), request.ref(), request.credentialId()));
    }
    @GetMapping("/projects/{project}/binding") public Object binding(@PathVariable String project) {
        return credentials.binding(project);
    }
    @PostMapping("/projects/{project}/check") public Object check(@PathVariable String project) { return sync.check(project); }
    @PostMapping("/projects/{project}/sync") public ResponseEntity<?> sync(@PathVariable String project) {
        return ResponseEntity.accepted().body(sync.sync(project));
    }
}
