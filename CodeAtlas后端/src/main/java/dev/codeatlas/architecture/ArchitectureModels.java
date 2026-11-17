package dev.codeatlas.architecture;

import java.util.List;

/** Stable, renderer-neutral architecture projection shared by the UI and exporters. */
public final class ArchitectureModels {
    private ArchitectureModels() {}

    public static final String SCHEMA_VERSION = "codeatlas-architecture-v1";
    public static final String ANALYZER_VERSION = "architecture-v1";

    public record Evidence(String id, String fileId, String filePath, String symbolId,
                           int startLine, int endLine, String fileHash, String rule) {}

    public record Node(String id, String kind, String name, String responsibility, String technology,
                       String parentId, String status, List<String> evidenceIds, int memberCount) {}

    public record Edge(String id, String source, String target, String kind, String status,
                       String reason, List<String> evidenceIds) {}

    public record Group(String id, String kind, String name, String parentId, List<String> nodeIds) {}

    public record Diagnostic(String code, String message, String fileId, String filePath, int startLine) {}

    public record Coverage(int files, int javaFiles, int configFiles, int modules, int components,
                           int externalServices, int dataStores, int unclassifiedTypes, boolean truncated) {}

    public record Document(String schemaVersion, String projectId, String snapshotId, String generationId,
                           String analyzerVersion, String projectionId, String digest,
                           List<Node> nodes, List<Edge> edges, List<Group> groups,
                           List<Evidence> evidence, List<Diagnostic> diagnostics,
                           Coverage coverage, boolean truncated) {
        public Document withIdentity(String projectionId, String digest) {
            return new Document(schemaVersion, projectId, snapshotId, generationId, analyzerVersion,
                    projectionId, digest, nodes, edges, groups, evidence, diagnostics, coverage, truncated);
        }
    }
}
