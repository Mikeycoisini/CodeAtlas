package dev.codeatlas.graph;

import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;
import java.util.*;
import static dev.codeatlas.graph.GraphIndex.*;

@Service
public class GraphQueryService {
    static final Set<String> VIEWS = Set.of("PACKAGE", "TYPE", "CALL");
    static final Set<String> STATUSES = Set.of("CONFIRMED", "INFERRED", "UNRESOLVED");
    static final Set<String> KINDS = Set.of("CALL", "RUNTIME_CALL", "EXTENDS", "IMPLEMENTS", "TRANSACTION_BOUNDARY");
    public record Query(String generationId, String view, String query, String packageId, String focusSymbolId,
                        Set<String> statuses, Set<String> kinds, int offset, int limit, int edgeLimit, boolean includeSelf) {
        public Query {
            if (!VIEWS.contains(view) || query.length() > 200 || offset < 0 || limit < (view.equals("CALL") ? 2 : 1)
                    || limit > 200 || edgeLimit < 1 || edgeLimit > 1000 || !STATUSES.containsAll(statuses) || !KINDS.containsAll(kinds)) invalid();
            if (view.equals("CALL") && (focusSymbolId == null || focusSymbolId.isBlank())) invalid();
            statuses = Set.copyOf(statuses); kinds = Set.copyOf(kinds);
        }
    }
    record Edge(String id, String source, String target, String kind, String status, String reason, int evidenceCount) {}
    private static final class Group {
        final String id, source, target, kind, status;
        final List<Relation> evidence = new ArrayList<>();
        Group(String id, Node source, Node target, Relation relation) {
            this.id = id; this.source = source.id(); this.target = target.id(); kind = relation.kind(); status = relation.status();
        }
        Edge view() { return new Edge(id, source, target, kind, status, evidence.stream().map(Relation::reason).distinct().sorted().limit(3).reduce((a, b) -> a + " · " + b).orElse(""), evidence.size()); }
    }
    private record Projection(GraphIndex index, Map<String, Node> nodes, List<Group> groups) {}
    private final AtlasStore store;
    public GraphQueryService(AtlasStore store) { this.store = store; }
    static void invalid() { throw new ApiException(400, "INVALID_GRAPH_QUERY", "图查询参数无效"); }
    static Set<String> values(String input, Set<String> allowed) {
        if (input == null || input.isBlank()) return allowed;
        if (input.equals("NONE")) return Set.of();
        var result = new LinkedHashSet<>(Arrays.asList(input.split(",", -1)));
        if (!allowed.containsAll(result)) invalid(); return result;
    }
    private Projection project(String snapshot, Query query) {
        String generation = store.generation(snapshot, query.generationId);
        var index = new GraphIndex(store, snapshot, generation);
        var nodes = new LinkedHashMap<String, Node>(); var groups = new LinkedHashMap<String, Group>();
        if (generation == null) return new Projection(index, nodes, List.of());
        if (query.view.equals("CALL")) {
            var center = index.symbol(query.focusSymbolId);
            if (!center.callable()) throw new ApiException(400, "INVALID_GRAPH_FOCUS", "请选择方法或构造器作为调用中心");
            nodes.put(center.id(), index.callableNode(center));
        } else if (query.view.equals("PACKAGE")) nodes.putAll(index.packageNodes);
        else for (var symbol : index.symbols.values()) if (symbol.type()) nodes.put(symbol.id(), index.typeNode(symbol));
        String sql = "SELECT id,payload FROM relations WHERE generation_id=?";
        Object[] args = { generation };
        if (query.view.equals("CALL")) { sql += " AND (source_id=? OR target_id=?)"; args = new Object[]{ generation, query.focusSymbolId, query.focusSymbolId }; }
        store.scanPayloads(sql + " ORDER BY id", raw -> {
            var relation = GraphIndex.relation(raw);
            boolean matches = query.statuses.contains(relation.status()) && query.kinds.contains(relation.kind());
            if (query.view.equals("CALL") && !matches) return;
            var source = index.mapped(relation.sourceId(), query.view);
            var target = relation.targetId() == null ? index.unknown(relation, query.view) : index.mapped(relation.targetId(), query.view);
            nodes.putIfAbsent(source.id(), source); nodes.putIfAbsent(target.id(), target);
            if (!matches || !query.includeSelf && source.id().equals(target.id())) return;
            String key = index.stable(query.view, source.id() + "|" + target.id() + "|" + relation.kind() + "|" + relation.status());
            var group = groups.computeIfAbsent(key, ignored -> new Group(key, source, target, relation));
            group.evidence.add(relation);
        }, args);
        return new Projection(index, nodes, groups.values().stream().sorted(Comparator.comparing(group -> group.id)).toList());
    }
    private boolean matches(Node node, Query query) {
        if (query.view.equals("CALL") && node.id().equals(query.focusSymbolId)) return true;
        if (query.view.equals("TYPE") && !query.packageId.isEmpty() && !query.packageId.equals(node.packageId())) return false;
        String text = node.label() + " " + node.qualifiedName();
        return text.toLowerCase(Locale.ROOT).contains(query.query.toLowerCase(Locale.ROOT));
    }
    public Map<String, Object> query(String snapshot, Query query) {
        var projection = project(snapshot, query); var index = projection.index;
        if (!query.packageId.isEmpty() && !index.packageNodes.containsKey(query.packageId)) throw ApiException.missing();
        var candidates = projection.nodes.values().stream().filter(node -> matches(node, query)).sorted(Comparator.comparing(Node::id)).toList();
        var candidateIds = new HashSet<>(candidates.stream().map(Node::id).toList());
        var page = new ArrayList<Node>();
        int neighborLimit = query.limit;
        if (query.view.equals("CALL") && index.generation != null) {
            page.add(projection.nodes.get(query.focusSymbolId)); neighborLimit--;
            candidates.stream().filter(node -> !node.id().equals(query.focusSymbolId)).skip(query.offset).limit(neighborLimit).forEach(page::add);
        } else candidates.stream().skip(query.offset).limit(query.limit).forEach(page::add);
        var pageIds = new HashSet<>(page.stream().map(Node::id).toList());
        var inScope = projection.groups.stream().filter(group -> candidateIds.contains(group.source) && candidateIds.contains(group.target)).toList();
        var onPage = inScope.stream().filter(group -> pageIds.contains(group.source) && pageIds.contains(group.target)).toList();
        long outsideScope = projection.groups.stream().filter(group -> candidateIds.contains(group.source) != candidateIds.contains(group.target)).mapToLong(group -> group.evidence.size()).sum();
        long outsidePage = inScope.stream().filter(group -> !pageIds.contains(group.source) || !pageIds.contains(group.target)).mapToLong(group -> group.evidence.size()).sum();
        var result = new LinkedHashMap<String, Object>();
        result.put("projectId", index.project); result.put("snapshotId", snapshot); result.put("generationId", index.generation); result.put("view", query.view);
        result.put("nodes", page); result.put("edges", onPage.stream().limit(query.edgeLimit).map(Group::view).toList());
        result.put("totalNodeCount", candidates.size()); result.put("totalEdgeCount", inScope.size());
        result.put("outsideScopeRelationCount", outsideScope); result.put("outsidePageRelationCount", outsidePage);
        result.put("omittedEdgeCount", Math.max(0, onPage.size() - query.edgeLimit));
        result.put("truncated", candidates.size() > page.size() || onPage.size() > query.edgeLimit || outsideScope > 0 || outsidePage > 0);
        result.put("offset", query.offset); result.put("limit", query.limit); result.put("edgeLimit", query.edgeLimit);
        result.put("neighborTotal", query.view.equals("CALL") ? Math.max(0, candidates.size() - 1) : 0); result.put("neighborLimit", neighborLimit);
        result.put("diagnosticCount", index.generation == null ? 0 : store.one("SELECT COUNT(*) AS total FROM diagnostics WHERE generation_id=?", index.generation).get("total"));
        return result;
    }
    public Map<String, Object> evidence(String snapshot, String edgeId, Query query, int offset, int limit) {
        page(offset, limit);
        var all = new Query(query.generationId, query.view, "", "", query.focusSymbolId, STATUSES, KINDS, 0, 80, 300, true);
        var projection = project(snapshot, all);
        var group = projection.groups.stream().filter(item -> item.id.equals(edgeId)).findFirst().orElseThrow(ApiException::missing);
        var items = group.evidence.stream().sorted(Comparator.comparing(Relation::id)).skip(offset).limit(limit).map(relation -> {
            var item = new LinkedHashMap<String, Object>(); item.put("relation", relation);
            item.put("source", projection.index.source(projection.index.symbol(relation.sourceId()), relation.fileId(), relation.startLine(), relation.startColumn(), relation.endLine(), relation.endColumn()));
            item.put("target", relation.targetId() == null ? null : projection.index.source(projection.index.symbol(relation.targetId()))); return item;
        }).toList();
        return Map.of("items", items, "total", group.evidence.size(), "limit", limit, "offset", offset, "generationId", projection.index.generation);
    }
    public Map<String, Object> members(String snapshot, String generation, String typeId, int offset, int limit) {
        page(offset, limit); var index = new GraphIndex(store, snapshot, store.generation(snapshot, generation));
        if (!index.symbol(typeId).type()) invalid();
        var all = index.symbols.values().stream().filter(symbol -> symbol.callable() && typeId.equals(index.owners.get(symbol.id())))
                .sorted(Comparator.comparing(Symbol::signature).thenComparing(Symbol::id)).map(index::callableNode).toList();
        return Map.of("items", all.stream().skip(offset).limit(limit).toList(), "total", all.size(), "limit", limit, "offset", offset, "generationId", index.generation);
    }
    private void page(int offset, int limit) { if (offset < 0 || limit < 1 || limit > 200) invalid(); }
}
