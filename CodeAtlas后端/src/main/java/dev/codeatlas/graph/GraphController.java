package dev.codeatlas.graph;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/snapshots/{snapshot}/graph")
public class GraphController {
    private final GraphQueryService service;
    public GraphController(GraphQueryService service) { this.service = service; }
    private int number(Map<String, String> values, String key, int fallback) {
        try { return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback; }
        catch (NumberFormatException error) { GraphQueryService.invalid(); return fallback; }
    }
    private GraphQueryService.Query query(MultiValueMap<String, String> parameters, boolean graphPagination) {
        if (parameters.values().stream().anyMatch(values -> values.size() != 1)) GraphQueryService.invalid();
        var values = parameters.toSingleValueMap();
        if (!Set.of("generationId", "view", "query", "packageId", "focusSymbolId", "statuses", "kinds", "offset", "limit", "edgeLimit", "includeSelf").containsAll(values.keySet())) GraphQueryService.invalid();
        String self = values.getOrDefault("includeSelf", "true");
        if (!self.equals("true") && !self.equals("false")) GraphQueryService.invalid();
        return new GraphQueryService.Query(values.get("generationId"), values.getOrDefault("view", "PACKAGE"), values.getOrDefault("query", ""), values.getOrDefault("packageId", ""),
                values.getOrDefault("focusSymbolId", ""), GraphQueryService.values(values.get("statuses"), GraphQueryService.STATUSES), GraphQueryService.values(values.get("kinds"), GraphQueryService.KINDS),
                graphPagination ? number(values, "offset", 0) : 0, graphPagination ? number(values, "limit", 80) : 80, number(values, "edgeLimit", 300), Boolean.parseBoolean(self));
    }
    @GetMapping public Object graph(@PathVariable String snapshot, @RequestParam MultiValueMap<String, String> parameters) { return service.query(snapshot, query(parameters, true)); }
    @GetMapping("/edges/{edge}/evidence") public Object evidence(@PathVariable String snapshot, @PathVariable String edge, @RequestParam MultiValueMap<String, String> parameters) {
        var request = query(parameters, false);
        return service.evidence(snapshot, edge, request, number(parameters.toSingleValueMap(), "offset", 0), number(parameters.toSingleValueMap(), "limit", 30));
    }
    @GetMapping("/types/{type}/members") public Object members(@PathVariable String snapshot, @PathVariable String type, @RequestParam MultiValueMap<String, String> parameters) {
        var request = query(parameters, true); return service.members(snapshot, request.generationId(), type, request.offset(), request.limit());
    }
}
