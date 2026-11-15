package dev.codeatlas.training;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {
    private final PracticeService practice;
    public PracticeController(PracticeService practice) { this.practice = practice; }
    @PostMapping("/workspaces") public Object create(@RequestBody Map<String, Object> input) { return practice.create(input); }
    @GetMapping("/workspaces") public Object workspaces(@RequestParam String taskId) { return practice.workspaces(taskId); }
    @GetMapping("/workspaces/{id}") public Object workspace(@PathVariable String id) { return practice.workspace(id); }
    @PostMapping("/workspaces/{id}/close") public Object close(@PathVariable String id, @RequestBody Map<String, Object> input) { return practice.close(id, input); }
    @PostMapping("/workspaces/{id}/submissions") public Object submit(@PathVariable String id, @RequestBody Map<String, Object> input) { return practice.submit(id, input); }
    @GetMapping("/submissions/{id}") public Object submission(@PathVariable String id) { return practice.submission(id); }
    @PostMapping("/submissions/{id}/test-results") public Object report(@PathVariable String id, @RequestBody Map<String, Object> input) { return practice.report(id, input); }
}
