package dev.codeatlas.learning;

import dev.codeatlas.web.ApiException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LearningController {
    private final LearningService service;
    private final SourceEvidenceService sources;
    public LearningController(LearningService service, SourceEvidenceService sources) { this.service = service; this.sources = sources; }

    @PostMapping("/sources") public Object createSource(@RequestBody Map<String, Object> input) { return sources.create(input); }
    @GetMapping("/sources/{id}") public Object source(@PathVariable String id) { return sources.get(id); }
    @PostMapping("/sources/{id}/check-current") public Object checkCurrent(@PathVariable String id) { return sources.checkCurrent(id); }
    @GetMapping({"/documents", "/knowledge", "/cards"}) public Object list(jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(defaultValue="") String query, @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) {
        return service.list(kind(request), query, limit, offset);
    }
    @PostMapping({"/documents", "/knowledge", "/cards"}) public Object create(jakarta.servlet.http.HttpServletRequest request, @RequestBody Map<String, Object> input) { return service.create(kind(request), input); }
    @GetMapping({"/documents/{id}", "/knowledge/{id}", "/cards/{id}"}) public Object get(jakarta.servlet.http.HttpServletRequest request, @PathVariable String id) { return service.get(kind(request), id); }
    @PutMapping({"/documents/{id}", "/knowledge/{id}", "/cards/{id}"}) public Object update(jakarta.servlet.http.HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> input) { return service.update(kind(request), id, input); }
    @DeleteMapping({"/documents/{id}", "/knowledge/{id}", "/cards/{id}"}) public Object delete(jakarta.servlet.http.HttpServletRequest request, @PathVariable String id) { return service.delete(kind(request), id); }
    private String kind(jakarta.servlet.http.HttpServletRequest request) {
        String segment = request.getRequestURI().split("/")[2];
        return switch (segment) { case "documents" -> "DOCUMENT"; case "knowledge" -> "KNOWLEDGE"; case "cards" -> "CARD"; default -> throw new ApiException(404, "NOT_FOUND", "资料不存在"); };
    }
}
