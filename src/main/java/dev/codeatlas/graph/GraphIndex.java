package dev.codeatlas.graph;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codeatlas.analysis.JavaAnalyzer;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import java.util.*;

/** Read-only projection of a single published generation, including historical analyzer payloads. */
final class GraphIndex {
    record Source(String projectId, String snapshotId, String generationId, String fileId, String symbolId,
                  String filePath, String fileHash, int startLine, int startColumn, int endLine, int endColumn) {}
    record Node(String id, String kind, String label, String qualifiedName, Source source,
                int memberCount, String packageId, boolean ownershipUnknown) {}
    record Symbol(String id, String fileId, String kind, String name, String qualifiedName, String signature,
                  int startLine, int startColumn, int endLine, int endColumn) {
        boolean type() { return kind.equals("CLASS") || kind.equals("INTERFACE"); }
        boolean callable() { return kind.equals("METHOD") || kind.equals("CONSTRUCTOR"); }
        boolean contains(Symbol other) {
            return fileId.equals(other.fileId) && compare(startLine, startColumn, other.startLine, other.startColumn) <= 0
                    && compare(endLine, endColumn, other.endLine, other.endColumn) >= 0;
        }
        private static int compare(int a, int b, int c, int d) { return a == c ? Integer.compare(b, d) : Integer.compare(a, c); }
    }
    record Relation(String id, String sourceId, String targetId, String targetText, String kind, String status,
                    String reason, String resolver, String fileId, int startLine, int startColumn, int endLine, int endColumn) {}
    final String snapshot;
    final String generation;
    final String project;
    final Map<String, Symbol> symbols = new LinkedHashMap<>();
    final Map<String, String> owners = new HashMap<>();
    final Map<String, String> packages = new HashMap<>();
    final Map<String, Node> packageNodes = new LinkedHashMap<>();
    private final Map<String, Node> typeNodes = new HashMap<>();
    private final Map<String, Map<String, Object>> files = new HashMap<>();

    GraphIndex(AtlasStore store, String snapshot, String generation) {
        this.snapshot = snapshot; this.generation = generation;
        project = (String) store.snapshot(snapshot).get("projectId");
        for (var file : store.files(snapshot)) files.put((String) file.get("id"), file);
        if (generation == null) return;
        store.scanPayloads("SELECT id,payload FROM symbols WHERE generation_id=? ORDER BY id", raw -> {
            var symbol = new Symbol(raw.path("id").asText(), raw.path("fileId").asText(), raw.path("kind").asText(),
                    raw.path("name").asText(), raw.path("qualifiedName").asText(), raw.path("signature").asText(),
                    raw.path("startLine").asInt(), raw.path("startColumn").asInt(1), raw.path("endLine").asInt(), raw.path("endColumn").asInt(1));
            symbols.put(symbol.id, symbol);
        }, generation);
        var byFile = new HashMap<String, List<Symbol>>();
        for (var symbol : symbols.values()) if (symbol.type()) byFile.computeIfAbsent(symbol.fileId, key -> new ArrayList<>()).add(symbol);
        for (var symbol : symbols.values()) {
            var enclosing = byFile.getOrDefault(symbol.fileId, List.of()).stream().filter(type -> type.contains(symbol)).toList();
            var nearest = enclosing.stream().filter(type -> enclosing.stream().noneMatch(inner -> !inner.id.equals(type.id) && type.contains(inner) && inner.contains(symbol))).toList();
            if (symbol.type()) owners.put(symbol.id, symbol.id);
            else if (nearest.size() == 1 && symbol.qualifiedName.equals(nearest.getFirst().qualifiedName + "." + symbol.name)) owners.put(symbol.id, nearest.getFirst().id);
        }
        for (var type : symbols.values()) if (type.type()) {
            var top = byFile.getOrDefault(type.fileId, List.of()).stream().filter(candidate -> candidate.contains(type))
                    .filter(candidate -> byFile.get(type.fileId).stream().noneMatch(outer -> !outer.id.equals(candidate.id) && outer.contains(candidate))).toList();
            String name = top.size() == 1 ? packageName(top.getFirst()) : null;
            String packageId = stable("package", name == null ? "unknown|" + type.fileId : name);
            packages.put(type.id, packageId);
            var previous = packageNodes.get(packageId);
            packageNodes.put(packageId, new Node(packageId, "PACKAGE", name == null ? "归属未知 · " + path(type.fileId) : name.isEmpty() ? "默认包" : name,
                    name == null ? "" : name, null, previous == null ? 1 : previous.memberCount + 1, packageId, name == null));
        }
        var counts = new HashMap<String, Integer>();
        for (var symbol : symbols.values()) if (symbol.callable() && owners.containsKey(symbol.id)) counts.merge(owners.get(symbol.id), 1, Integer::sum);
        for (var symbol : symbols.values()) if (symbol.type()) typeNodes.put(symbol.id, new Node(symbol.id, symbol.kind, symbol.name,
                symbol.qualifiedName, source(symbol), counts.getOrDefault(symbol.id, 0), packages.get(symbol.id), false));
    }
    private String packageName(Symbol top) {
        String suffix = "." + top.name;
        if (top.qualifiedName.equals(top.name)) return "";
        if (!top.qualifiedName.endsWith(suffix)) return null;
        String name = top.qualifiedName.substring(0, top.qualifiedName.length() - suffix.length());
        return name.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*") ? name : null;
    }
    String stable(String prefix, String value) { return JavaAnalyzer.id(generation + "|" + prefix + "|" + value); }
    String path(String file) { return String.valueOf(files.getOrDefault(file, Map.of()).getOrDefault("path", "未知文件")); }
    Symbol symbol(String id) { var symbol = symbols.get(id); if (symbol == null) throw ApiException.missing(); return symbol; }
    Source source(Symbol symbol) { return source(symbol, symbol.fileId, symbol.startLine, symbol.startColumn, symbol.endLine, symbol.endColumn); }
    Source source(Symbol symbol, String fileId, int start, int startColumn, int end, int endColumn) {
        var file = files.get(fileId);
        if (file == null) return null;
        return new Source(project, snapshot, generation, fileId, symbol.id, path(fileId), (String) file.get("sha256"), start, startColumn, end, endColumn);
    }
    Node callableNode(Symbol symbol) { return new Node(symbol.id, symbol.kind, symbol.signature, symbol.qualifiedName, source(symbol), 0, null, !owners.containsKey(symbol.id)); }
    Node typeNode(Symbol symbol) {
        return typeNodes.get(symbol.id);
    }
    Node mapped(String symbolId, String view) {
        var symbol = symbol(symbolId);
        if (view.equals("CALL")) return callableNode(symbol);
        String owner = owners.get(symbolId);
        if (owner == null) return new Node(stable("owner", symbolId), "UNKNOWN_OWNER", "归属未知 · " + symbol.signature, symbol.qualifiedName, source(symbol), 0, null, true);
        if (view.equals("TYPE")) return typeNode(symbol(owner));
        return packageNodes.get(packages.get(owner));
    }
    Node unknown(Relation relation, String view) {
        return new Node(stable(view + "|unknown", relation.sourceId + "|" + relation.targetText + "|" + relation.kind + "|" + relation.status + "|" + relation.reason),
                "UNRESOLVED", relation.targetText, relation.targetText, null, 0, null, true);
    }
    static Relation relation(JsonNode raw) {
        return new Relation(raw.path("id").asText(), raw.path("sourceId").asText(), raw.path("targetId").isTextual() ? raw.get("targetId").asText() : null,
                raw.path("targetText").asText(), raw.path("kind").asText(), raw.path("status").asText(), raw.path("reason").asText(), raw.path("resolver").asText(),
                raw.path("fileId").asText(), raw.path("startLine").asInt(), raw.path("startColumn").asInt(1), raw.path("endLine").asInt(), raw.path("endColumn").asInt(1));
    }
}
