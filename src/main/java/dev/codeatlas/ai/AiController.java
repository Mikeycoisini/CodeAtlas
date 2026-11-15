package dev.codeatlas.ai;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiClient client;
    private final AiContextService contexts;
    private final AiGenerationService generations;
    public AiController(AiClient client, AiContextService contexts, AiGenerationService generations) { this.client = client; this.contexts = contexts; this.generations = generations; }
    @GetMapping("/provider") public Object provider() { return client.provider(); }
    @PostMapping("/contexts") public Object prepare(@RequestBody Map<String, Object> input) { return contexts.prepare(input); }
    @GetMapping("/contexts/{id}") public Object context(@PathVariable String id) { return contexts.get(id); }
    @PostMapping("/consents") public Object consent(@RequestBody Map<String, Object> input) { return contexts.consent(input); }
    @PostMapping("/generations") public Object create(@RequestBody Map<String, Object> input) { return generations.create(input); }
    @GetMapping("/generations/{id}") public Object generation(@PathVariable String id) { return generations.get(id); }
    @PostMapping("/generations/{id}/cancel") public Object cancel(@PathVariable String id) { return generations.cancel(id); }
}
