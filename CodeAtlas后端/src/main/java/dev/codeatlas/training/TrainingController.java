package dev.codeatlas.training;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/training")
public class TrainingController {
    private final TrainingService training;
    public TrainingController(TrainingService training) { this.training = training; }
    @GetMapping("/tasks") public Object tasks(@RequestParam(defaultValue="") String query, @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) { return training.listTasks(query, status, limit, offset); }
    @PostMapping("/tasks") public Object create(@RequestBody Map<String, Object> input) { return training.createTask(input); }
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id) { return training.task(id, false); }
    @GetMapping("/tasks/{id}/editor") public Object editor(@PathVariable String id) { return training.task(id, true); }
    @GetMapping("/tasks/{id}/revisions/{revision}") public Object version(@PathVariable String id, @PathVariable int revision) { training.task(id, false); return training.taskVersion(id, revision, false); }
    @PutMapping("/tasks/{id}") public Object update(@PathVariable String id, @RequestBody Map<String, Object> input) { return training.updateTask(id, input); }
    @DeleteMapping("/tasks/{id}") public Object delete(@PathVariable String id) { return training.deleteTask(id); }
    @PostMapping("/tasks/{id}/attempts") public Object start(@PathVariable String id, @RequestBody Map<String, Object> input) { return training.start(id, input); }
    @GetMapping("/attempts") public Object attempts(@RequestParam(defaultValue="") String taskId, @RequestParam(defaultValue="30") int limit,
            @RequestParam(defaultValue="0") int offset) { return training.listAttempts(taskId, limit, offset); }
    @GetMapping("/attempts/{id}") public Object attempt(@PathVariable String id) { return training.attempt(id); }
    @PutMapping("/attempts/{id}") public Object save(@PathVariable String id, @RequestBody Map<String, Object> input) { return training.saveAnswers(id, input); }
    @PostMapping("/attempts/{id}/submit") public Object submit(@PathVariable String id, @RequestBody Map<String, Object> input) { return training.complete(id, input, false); }
    @PostMapping("/attempts/{id}/skip") public Object skip(@PathVariable String id, @RequestBody Map<String, Object> input) { return training.complete(id, input, true); }
    @PostMapping("/attempts/{id}/reveal") public Object reveal(@PathVariable String id, @RequestBody Map<String, Object> input) { return training.reveal(id, input); }
    @PostMapping("/attempts/{id}/self-assessments") public Object assess(@PathVariable String id, @RequestBody Map<String, Object> input) { return training.selfAssess(id, input); }
}
