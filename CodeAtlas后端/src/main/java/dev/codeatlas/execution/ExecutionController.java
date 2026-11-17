package dev.codeatlas.execution;

import dev.codeatlas.web.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {
    public record ExecutionRequest(String submissionId, String idempotencyKey,
                                   String projectId, String taskId, String workspace) {}
    private final ExecutionService service;
    public ExecutionController(ExecutionService service) { this.service = service; }

    @GetMapping("/status") public Object status() { return service.status(); }

    @PostMapping public ResponseEntity<?> execute(@RequestBody ExecutionRequest request) {
        if (notBlank(request.projectId()) || notBlank(request.taskId()) || notBlank(request.workspace()))
            throw new ApiException(400, "EXECUTOR_LEGACY_REQUEST", "执行内容由已提交练习存档推导，不再接受宿主机项目、任务或工作区路径");
        return ResponseEntity.accepted().body(service.execute(request.submissionId(), request.idempotencyKey()));
    }

    @GetMapping("/{id}") public Object job(@PathVariable String id) { return service.job(id); }

    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable String id) { return service.cancel(id); }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
