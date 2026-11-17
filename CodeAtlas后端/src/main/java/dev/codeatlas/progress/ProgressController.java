package dev.codeatlas.progress;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/progress")
public class ProgressController {
    private final EvidenceService service;
    public ProgressController(EvidenceService service) { this.service = service; }
    @GetMapping("/knowledge") public Object list(@RequestParam(defaultValue="") String query, @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) { return service.list(query, status, limit, offset); }
    @GetMapping("/knowledge/{id}") public Object knowledge(@PathVariable String id) { return service.knowledge(id); }
    @GetMapping("/knowledge/{id}/history") public Object history(@PathVariable String id, @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) { return service.history(id, limit, offset); }
    @PostMapping("/evidence") public Object associate(@RequestBody Map<String, Object> input) { return service.associate(input); }
    @PostMapping("/evidence/{id}/retract") public Object retract(@PathVariable String id, @RequestBody Map<String, Object> input) { return service.retract(id, input); }
    @GetMapping("/mistakes") public Object mistakes(@RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) { return service.mistakes(limit, offset); }
}
