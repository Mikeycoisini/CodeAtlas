package dev.codeatlas.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.*;

/** Static call-tree sequence for one fixed entry. Runtime order is not implied. */
@Service
public class SequenceService {
    private static final int MAX_MESSAGES = 120;
    private final AtlasStore store;

    public SequenceService(AtlasStore store) { this.store = store; }

    private record SymbolInfo(String id, String kind, String name, String qualifiedName, String signature, String fileId, int startLine, int endLine) {}
    private record Relation(String sourceId, String targetId, String targetText, String kind, String status, String fileId,
                            int startLine, int startColumn, int endLine, int endColumn) {}

    public Map<String, Object> sequence(String snapshot, String generationId, String symbolId, int depth, int limit) {
        var generation = store.generation(snapshot, generationId);
        if (generation == null) throw new ApiException(409, "NO_GENERATION", "该快照尚无已发布索引");
        if (depth < 1 || depth > 6 || limit < 1 || limit > 300) throw new ApiException(400, "INVALID_SEQUENCE_QUERY", "depth 或 limit 超出范围");
        var symbols = new LinkedHashMap<String, SymbolInfo>();
        store.scanPayloads("SELECT id,payload FROM symbols WHERE generation_id=?", raw -> {
            var info = new SymbolInfo(raw.path("id").asText(), raw.path("kind").asText(), raw.path("name").asText(),
                    raw.path("qualifiedName").asText(), raw.path("signature").asText(), raw.path("fileId").asText(),
                    raw.path("startLine").asInt(), raw.path("endLine").asInt());
            symbols.put(info.id(), info);
        }, generation);
        var entry = symbols.get(symbolId);
        if (entry == null) throw ApiException.missing();
        if (!entry.kind().equals("METHOD") && !entry.kind().equals("CONSTRUCTOR"))
            throw new ApiException(400, "INVALID_SEQUENCE_FOCUS", "请选择方法或构造器作为时序入口");
        var owner = new LinkedHashMap<String, String>();
        for (var symbol : symbols.values()) {
            if (symbol.kind().equals("CLASS") || symbol.kind().equals("INTERFACE")) owner.put(symbol.id(), symbol.id());
            else if (symbol.qualifiedName().contains(".")) {
                var candidate = symbol.qualifiedName().substring(0, symbol.qualifiedName().lastIndexOf('.'));
                for (var type : symbols.values()) if ((type.kind().equals("CLASS") || type.kind().equals("INTERFACE")) && type.qualifiedName().equals(candidate)) {
                    owner.put(symbol.id(), type.id()); break;
                }
            }
        }
        var relations = new ArrayList<Relation>();
        store.scanPayloads("SELECT id,payload FROM relations WHERE generation_id=?", raw -> {
            var kind = raw.path("kind").asText();
            if (!kind.equals("CALL") && !kind.equals("RUNTIME_CALL")) return;
            relations.add(new Relation(raw.path("sourceId").asText(),
                    raw.path("targetId").isTextual() ? raw.path("targetId").asText() : null,
                    raw.path("targetText").asText(), kind, raw.path("status").asText(), raw.path("fileId").asText(),
                    raw.path("startLine").asInt(), raw.path("startColumn").asInt(1), raw.path("endLine").asInt(), raw.path("endColumn").asInt(1)));
        }, generation);
        relations.sort(Comparator.comparing(Relation::sourceId).thenComparingInt(Relation::startLine).thenComparingInt(Relation::startColumn));
        var bySource = new LinkedHashMap<String, List<Relation>>();
        for (var relation : relations) bySource.computeIfAbsent(relation.sourceId(), ignored -> new ArrayList<>()).add(relation);
        var files = new LinkedHashMap<String, Map<String, Object>>();
        for (var file : store.files(snapshot)) files.put((String) file.get("id"), file);
        var participants = new LinkedHashMap<String, Map<String, Object>>();
        var messages = new ArrayList<Map<String, Object>>();
        participant(participants, entry, owner.get(entry.id()), symbols);
        var truncated = new boolean[]{false};
        visit(entry, 1, depth, new LinkedHashSet<>(List.of(entry.id())), new int[]{0}, limit, symbols, owner, bySource, files, participants, messages, truncated);
        var result = new LinkedHashMap<String, Object>();
        result.put("snapshotId", snapshot); result.put("generationId", generation);
        result.put("entry", Map.of("id", entry.id(), "name", entry.name(), "signature", entry.signature(), "fileId", entry.fileId(), "startLine", entry.startLine()));
        result.put("participants", new ArrayList<>(participants.values()));
        result.put("messages", messages);
        result.put("truncated", truncated[0]);
        result.put("warnings", List.of("STATIC_SEQUENCE_ONLY", "RUNTIME_ORDER_NOT_VERIFIED"));
        result.put("mode", "STATIC");
        return result;
    }

    private void visit(SymbolInfo from, int level, int maxDepth, Set<String> stack, int[] order, int limit,
                       Map<String, SymbolInfo> symbols, Map<String, String> owner, Map<String, List<Relation>> bySource,
                       Map<String, Map<String, Object>> files, Map<String, Map<String, Object>> participants,
                       List<Map<String, Object>> messages, boolean[] truncated) {
        if (level > maxDepth) return;
        for (var relation : bySource.getOrDefault(from.id(), List.of())) {
            if (messages.size() >= limit || messages.size() >= MAX_MESSAGES) { truncated[0] = true; return; }
            var target = relation.targetId() == null ? null : symbols.get(relation.targetId());
            var toOwner = target == null ? "unresolved|" + relation.targetText() : owner.get(target.id());
            var fromParticipant = participant(participants, from, owner.get(from.id()), symbols);
            var toParticipant = target == null ? unresolvedParticipant(participants, relation.targetText()) : participant(participants, target, toOwner, symbols);
            var message = new LinkedHashMap<String, Object>();
            message.put("id", JavaAnalyzer.id("sequence|" + from.id() + "|" + relation.kind() + "|" + relation.startLine() + "|" + messages.size()));
            message.put("order", ++order[0]); message.put("level", level);
            message.put("from", fromParticipant); message.put("to", toParticipant);
            message.put("label", target == null ? relation.targetText() : target.name() + (target.signature().isBlank() ? "" : " · " + target.signature()));
            message.put("kind", relation.kind()); message.put("status", relation.status());
            message.put("cycle", target != null && stack.contains(target.id()));
            var file = files.getOrDefault(relation.fileId(), Map.of());
            message.put("evidence", Map.of("fileId", relation.fileId(), "filePath", file.getOrDefault("path", ""),
                    "fileHash", file.getOrDefault("sha256", ""), "startLine", relation.startLine(), "startColumn", relation.startColumn(),
                    "endLine", relation.endLine(), "endColumn", relation.endColumn(), "sourceId", from.id(),
                    "targetId", target == null ? "" : target.id()));
            messages.add(message);
            if (target != null && !stack.contains(target.id()) && level < maxDepth && (target.kind().equals("METHOD") || target.kind().equals("CONSTRUCTOR"))) {
                var next = new LinkedHashSet<>(stack); next.add(target.id());
                visit(target, level + 1, maxDepth, next, order, limit, symbols, owner, bySource, files, participants, messages, truncated);
            }
        }
    }

    private String participant(Map<String, Map<String, Object>> participants, SymbolInfo symbol, String ownerId, Map<String, SymbolInfo> symbols) {
        var id = ownerId == null ? "unknown" : ownerId;
        var name = ownerId == null ? "归属未知" : symbols.containsKey(ownerId) ? symbols.get(ownerId).name() : ownerId;
        if (!participants.containsKey(id)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", id); item.put("name", name); item.put("kind", "TYPE");
            item.put("source", Map.of("fileId", symbol.fileId(), "startLine", symbol.startLine(), "endLine", symbol.endLine()));
            participants.put(id, item);
        }
        return id;
    }

    private String unresolvedParticipant(Map<String, Map<String, Object>> participants, String text) {
        var id = "unresolved|" + text;
        if (!participants.containsKey(id)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", id); item.put("name", "未解析 · " + text); item.put("kind", "UNKNOWN");
            participants.put(id, item);
        }
        return id;
    }
}
