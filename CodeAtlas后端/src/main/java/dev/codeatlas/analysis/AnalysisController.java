package dev.codeatlas.analysis;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/snapshots/{snapshot}")
public class AnalysisController {
    private final FlowService flows;
    private final SequenceService sequences;
    private final UmlService uml;
    public AnalysisController(FlowService flows, SequenceService sequences, UmlService uml) {
        this.flows = flows; this.sequences = sequences; this.uml = uml;
    }

    @GetMapping("/symbols/{symbol}/flow")
    public Object flow(@PathVariable String snapshot, @PathVariable String symbol,
                       @RequestParam(required = false) String generationId,
                       @RequestParam(defaultValue = "180") int limit) {
        return flows.flow(snapshot, generationId, symbol, limit);
    }

    @GetMapping("/symbols/{symbol}/sequence")
    public Object sequence(@PathVariable String snapshot, @PathVariable String symbol,
                           @RequestParam(required = false) String generationId,
                           @RequestParam(defaultValue = "3") int depth,
                           @RequestParam(defaultValue = "120") int limit) {
        return sequences.sequence(snapshot, generationId, symbol, depth, limit);
    }

    @GetMapping("/symbols/{symbol}/uml")
    public Object uml(@PathVariable String snapshot, @PathVariable String symbol,
                      @RequestParam(required = false) String generationId) {
        return uml.uml(snapshot, generationId, symbol);
    }
}
