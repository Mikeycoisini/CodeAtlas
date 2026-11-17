package dev.codeatlas.review;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service) { this.service = service; }
    @GetMapping("/cards") public Object queue(@RequestParam(defaultValue="true") boolean dueOnly, @RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="") String knowledgeId, @RequestParam(defaultValue="") String tag, @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) { return service.queue(dueOnly, query, knowledgeId, tag, limit, offset); }
    @GetMapping("/cards/{id}/state") public Object state(@PathVariable String id) { return service.state(id); }
    @GetMapping("/events/{id}") public Object event(@PathVariable String id) { return service.event(id); }
    @PostMapping("/cards/{id}/enroll") public Object enroll(@PathVariable String id, @RequestBody Map<String,Object> input) { return service.enroll(id, input); }
    @PostMapping({"/cards/{id}/suspend", "/cards/{id}/resume", "/cards/{id}/reset"}) public Object manage(@PathVariable String id, @RequestBody Map<String,Object> input, jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI(); return service.manage(id, path.substring(path.lastIndexOf('/') + 1), input);
    }
    @GetMapping("/cards/{id}/history") public Object history(@PathVariable String id, @RequestParam(defaultValue="30") int limit, @RequestParam(defaultValue="0") int offset) { return service.history(id, limit, offset); }
    @PostMapping("/cards/{id}/attempts") public Object start(@PathVariable String id, @RequestBody Map<String,Object> input) { return service.start(id, input); }
    @GetMapping("/attempts/{id}") public Object attempt(@PathVariable String id) { return service.attempt(id); }
    @PutMapping("/attempts/{id}") public Object save(@PathVariable String id, @RequestBody Map<String,Object> input) { return service.save(id, input); }
    @PostMapping("/attempts/{id}/reveal") public Object reveal(@PathVariable String id, @RequestBody Map<String,Object> input) { return service.reveal(id, input); }
    @PostMapping("/attempts/{id}/submit") public Object submit(@PathVariable String id, @RequestBody Map<String,Object> input) { return service.submit(id, input); }
    @PostMapping("/attempts/{id}/skip") public Object skip(@PathVariable String id, @RequestBody Map<String,Object> input) { return service.skip(id, input); }
}
