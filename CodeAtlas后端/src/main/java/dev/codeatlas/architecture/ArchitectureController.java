package dev.codeatlas.architecture;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ArchitectureController {
    private final ArchitectureService service;
    private final ArchitectureExportService exports;
    public ArchitectureController(ArchitectureService service, ArchitectureExportService exports) {
        this.service = service; this.exports = exports;
    }

    @GetMapping("/snapshots/{snapshot}/architecture")
    public Object architecture(@PathVariable String snapshot,
                               @RequestParam(required = false) String generationId,
                               @RequestParam(defaultValue = "SYSTEM") String perspective,
                               @RequestParam(defaultValue = "") String focusId,
                               @RequestParam(defaultValue = "2") int depth,
                               @RequestParam(defaultValue = "80") int limit) {
        return service.query(snapshot, generationId, perspective, focusId, depth, limit);
    }

    @PostMapping("/architecture/exports")
    public ResponseEntity<?> createExport(@RequestBody ArchitectureExportService.ExportRequest request) {
        var result = exports.export(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.export());
    }

    @GetMapping("/architecture/exports/{id}")
    public Object export(@PathVariable String id) {
        return exports.view(exports.row(id));
    }

    @GetMapping("/architecture/exports/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        var file = exports.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(exports.contentType(exports.row(id))))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name()).build().toString())
                .body(new FileSystemResource(file.path()));
    }

}
