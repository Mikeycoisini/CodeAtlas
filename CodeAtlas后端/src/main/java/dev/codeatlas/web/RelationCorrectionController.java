package dev.codeatlas.web;

import dev.codeatlas.relations.RelationCorrectionService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/snapshots/{snapshot}/relations/{relation}/corrections")
public class RelationCorrectionController {
    private final RelationCorrectionService service;
    public RelationCorrectionController(RelationCorrectionService service) { this.service = service; }

    @PostMapping public Object create(@PathVariable String snapshot, @PathVariable String relation,
                                      @RequestBody Map<String, Object> input) {
        return service.create(snapshot, relation, input);
    }

    @GetMapping public Object list(@PathVariable String snapshot, @PathVariable String relation,
                                   @RequestParam String generationId) {
        return service.list(snapshot, relation, generationId);
    }
}
