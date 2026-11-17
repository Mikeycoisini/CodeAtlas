package dev.codeatlas.guides;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/guides")
public class GuideController {
    private final GuideService service;
    public GuideController(GuideService service) { this.service = service; }
    @GetMapping public Object list(@RequestParam(defaultValue="") String query, @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) { return service.list(query, status, limit, offset); }
    @PostMapping public Object create(@RequestBody Map<String, Object> input) { return service.create(input); }
    @GetMapping("/{id}") public Object get(@PathVariable String id) { return service.get(id); }
    @PutMapping("/{id}") public Object update(@PathVariable String id, @RequestBody Map<String, Object> input) { return service.update(id, input); }
    @PostMapping("/{id}/actions") public Object action(@PathVariable String id, @RequestBody Map<String, Object> input) { return service.action(id, input); }
    @PostMapping("/{id}/apply-suggestion") public Object applySuggestion(@PathVariable String id, @RequestBody Map<String, Object> input) { return service.applySuggestion(id, input); }
    @GetMapping("/{id}/history") public Object history(@PathVariable String id, @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) { return service.history(id, limit, offset); }
}
