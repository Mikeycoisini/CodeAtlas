package dev.codeatlas.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.*;

import java.util.*;

/**
 * Bounded static control-flow graph builder for one method body.
 *
 * <p>The graph distinguishes normal exits, return/throw exits and break/continue jumps. Sequential
 * statements are connected to every normal exit of the previous statement, not to its entry.
 * Unsupported Java semantics are reported as diagnostics instead of being rendered as definite
 * edges.</p>
 */
final class ControlFlowBuilder {
    private static final int MAX_EDGES = 360;

    record Result(List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                  List<String> warnings, boolean truncated) {}

    private record Exit(String node, String kind, String label, String jumpLabel) {}
    private record Part(String entry, List<Exit> normal, List<Exit> terminated, List<Exit> jumps) {}

    private final String snapshot, generation, fileId, filePath, fileHash;
    private final int startLine, endLine, nodeLimit;
    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private final List<Map<String, Object>> edges = new ArrayList<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private final Set<String> edgeKeys = new HashSet<>();
    private boolean truncated;

    private ControlFlowBuilder(String snapshot, String generation, String fileId, String filePath, String fileHash,
                              int startLine, int endLine, int nodeLimit) {
        this.snapshot = snapshot; this.generation = generation; this.fileId = fileId; this.filePath = filePath;
        this.fileHash = fileHash; this.startLine = startLine; this.endLine = endLine; this.nodeLimit = nodeLimit;
    }

    static Result build(String snapshot, String generation, String fileId, String filePath, String fileHash,
                        int startLine, int endLine, int nodeLimit, BlockStmt body) {
        return new ControlFlowBuilder(snapshot, generation, fileId, filePath, fileHash, startLine, endLine, nodeLimit)
                .build(body);
    }

    private Result build(BlockStmt body) {
        var start = node("START", "开始", startLine, startLine, "CONFIRMED");
        var part = statements(body == null ? List.of() : body.getStatements(), start, "");
        var end = node("END", "结束", endLine, endLine, "CONFIRMED");
        for (var exit : part.normal()) edge(exit.node(), end, "END", exit.label(), "CONFIRMED");
        for (var exit : part.terminated()) edge(exit.node(), end, "END", exit.kind(), "CONFIRMED");
        for (var exit : part.jumps()) {
            edge(exit.node(), end, "END", exit.kind() + (exit.jumpLabel().isBlank() ? "" : " " + exit.jumpLabel()), "INFERRED");
            warnings.add("UNRESOLVED_JUMP");
        }
        if (part.normal().isEmpty() && part.terminated().isEmpty() && part.jumps().isEmpty())
            edge(part.entry(), end, "END", "", "INFERRED");
        warnings.add("STATIC_FLOW_ONLY");
        warnings.add("ASYNC_AND_DYNAMIC_DISPATCH_NOT_EXPANDED");
        return new Result(List.copyOf(nodes), List.copyOf(edges), List.copyOf(warnings), truncated);
    }

    private Part statements(List<Statement> statements, String current, String label) {
        if (statements.isEmpty())
            return new Part(current, List.of(new Exit(current, "NORMAL", label, "")), List.of(), List.of());
        String entry = null;
        var normal = List.of(new Exit(current, "NORMAL", label, ""));
        var terminated = new ArrayList<Exit>();
        var jumps = new ArrayList<Exit>();
        for (int index = 0; index < statements.size(); index++) {
            if (truncated) break;
            if (normal.isEmpty()) {
                if (index < statements.size()) warnings.add("UNREACHABLE_STATEMENTS");
                break;
            }
            var joined = join(normal);
            var nextLabel = normal.size() == 1 ? normal.getFirst().label() : "";
            var next = statement(statements.get(index), joined, nextLabel);
            if (entry == null) entry = next.entry();
            terminated.addAll(next.terminated());
            jumps.addAll(next.jumps());
            normal = next.normal();
            if (normal.isEmpty() && index + 1 < statements.size()) warnings.add("UNREACHABLE_STATEMENTS");
        }
        return new Part(entry == null ? current : entry, normal, terminated, jumps);
    }

    private String join(List<Exit> exits) {
        if (exits.size() == 1) return exits.getFirst().node();
        var first = exits.getFirst();
        var merge = node("MERGE", "路径汇合", line(first.node()), line(first.node()), "INFERRED");
        for (var exit : exits) edge(exit.node(), merge, "NEXT", exit.label(), "INFERRED");
        return merge;
    }

    private Part statement(Statement statement, String current, String label) {
        if (statement instanceof BlockStmt block) return statements(block.getStatements(), current, label);
        if (statement instanceof ReturnStmt || statement instanceof ThrowStmt) {
            var kind = statement instanceof ReturnStmt ? "RETURN" : "THROW";
            var node = node(kind, clip(statement.toString()), line(statement), endLine(statement), "CONFIRMED");
            edge(current, node, "NEXT", label, "CONFIRMED");
            return new Part(node, List.of(), List.of(new Exit(node, kind, "", "")), List.of());
        }
        if (statement instanceof IfStmt condition) return ifStatement(condition, current, label);
        if (statement instanceof ForStmt loop) return loop("FOR", loop, loop.getBody(), current, label, "");
        if (statement instanceof ForEachStmt loop) return loop("FOR_EACH", loop, loop.getBody(), current, label, "");
        if (statement instanceof WhileStmt loop) return loop("WHILE", loop, loop.getBody(), current, label, "");
        if (statement instanceof DoStmt loop) return loop("DO_WHILE", loop, loop.getBody(), current, label, "");
        if (statement instanceof TryStmt trial) return tryStatement(trial, current, label);
        if (statement instanceof SwitchStmt switching) return switchStatement(switching, current, label, "");
        if (statement instanceof BreakStmt || statement instanceof ContinueStmt) {
            var kind = statement instanceof BreakStmt ? "BREAK" : "CONTINUE";
            var jumpLabel = statement instanceof BreakStmt broken
                    ? broken.getLabel().map(Object::toString).orElse("")
                    : ((ContinueStmt) statement).getLabel().map(Object::toString).orElse("");
            var node = node("JUMP", clip(statement.toString()), line(statement), endLine(statement), "CONFIRMED");
            edge(current, node, "NEXT", label, "CONFIRMED");
            return new Part(node, List.of(), List.of(), List.of(new Exit(node, kind, "", jumpLabel)));
        }
        if (statement instanceof SynchronizedStmt sync) return statements(sync.getBody().getStatements(), current, label);
        if (statement instanceof LabeledStmt labeled) return labeled(labeled, current, label);
        if (statement instanceof EmptyStmt) return new Part(current, List.of(new Exit(current, "NORMAL", label, "")), List.of(), List.of());
        var node = node("STATEMENT", clip(statement.toString()), line(statement), endLine(statement), "CONFIRMED");
        edge(current, node, "NEXT", label, "CONFIRMED");
        return new Part(node, List.of(new Exit(node, "NORMAL", "", "")), List.of(), List.of());
    }

    private Part labeled(LabeledStmt labeled, String current, String label) {
        var name = labeled.getLabel().asString();
        var inner = labeled.getStatement();
        if (inner instanceof ForStmt loop) return loop("FOR", loop, loop.getBody(), current, label, name);
        if (inner instanceof ForEachStmt loop) return loop("FOR_EACH", loop, loop.getBody(), current, label, name);
        if (inner instanceof WhileStmt loop) return loop("WHILE", loop, loop.getBody(), current, label, name);
        if (inner instanceof DoStmt loop) return loop("DO_WHILE", loop, loop.getBody(), current, label, name);
        if (inner instanceof SwitchStmt switching) return switchStatement(switching, current, label, name);
        warnings.add("LABELED_JUMP_APPROXIMATED");
        return statement(inner, current, label);
    }

    private Part ifStatement(IfStmt condition, String current, String label) {
        var decision = node("DECISION", "if (" + clip(condition.getCondition().toString()) + ")", line(condition), endLine(condition), "CONFIRMED");
        edge(current, decision, "NEXT", label, "CONFIRMED");
        var thenPart = statements(thenStatements(condition.getThenStmt()), decision, "TRUE");
        var elsePart = condition.getElseStmt().isPresent()
                ? statements(thenStatements(condition.getElseStmt().get()), decision, "FALSE")
                : new Part(decision, List.of(new Exit(decision, "NORMAL", "FALSE", "")), List.of(), List.of());
        var normal = new ArrayList<Exit>();
        normal.addAll(thenPart.normal()); normal.addAll(elsePart.normal());
        var terminated = new ArrayList<Exit>();
        terminated.addAll(thenPart.terminated()); terminated.addAll(elsePart.terminated());
        var jumps = new ArrayList<Exit>();
        jumps.addAll(thenPart.jumps()); jumps.addAll(elsePart.jumps());
        if (normal.isEmpty()) return new Part(decision, List.of(), terminated, jumps);
        var merge = node("MERGE", "分支汇合", line(condition), endLine(condition), "INFERRED");
        for (var exit : normal) edge(exit.node(), merge, "NEXT", exit.label(), "INFERRED");
        return new Part(decision, List.of(new Exit(merge, "NORMAL", "", "")), terminated, jumps);
    }

    private List<Statement> thenStatements(Statement statement) {
        return statement instanceof BlockStmt block ? block.getStatements() : List.of(statement);
    }

    private Part loop(String kind, Statement loop, Statement body, String current, String label, String jumpLabel) {
        var loopNode = node("LOOP", kind + " " + clip(loop.toString().split("\\{")[0]), line(loop), endLine(loop), "CONFIRMED");
        edge(current, loopNode, "NEXT", label, "CONFIRMED");
        var bodyPart = statements(thenStatements(body), loopNode, "LOOP");
        var terminated = new ArrayList<>(bodyPart.terminated());
        var remainingJumps = new ArrayList<Exit>();
        var after = node("MERGE", "循环结束", line(loop), endLine(loop), "INFERRED");
        for (var exit : bodyPart.normal()) edge(exit.node(), loopNode, "LOOP_BACK", "LOOP", "CONFIRMED");
        for (var jump : bodyPart.jumps()) {
            if (matches(jump, jumpLabel, "BREAK")) edge(jump.node(), after, "BREAK", "BREAK", "CONFIRMED");
            else if (matches(jump, jumpLabel, "CONTINUE")) edge(jump.node(), loopNode, "CONTINUE", "CONTINUE", "CONFIRMED");
            else remainingJumps.add(jump);
        }
        edge(loopNode, after, "EXIT", "EXIT", "INFERRED");
        return new Part(loopNode, List.of(new Exit(after, "NORMAL", "", "")), terminated, remainingJumps);
    }

    private boolean matches(Exit jump, String contextLabel, String kind) {
        if (!kind.equals(jump.kind())) return false;
        return jump.jumpLabel().isBlank() || jump.jumpLabel().equals(contextLabel);
    }

    private Part tryStatement(TryStmt trial, String current, String label) {
        var tryNode = node("TRY", "try", line(trial), endLine(trial), "CONFIRMED");
        edge(current, tryNode, "NEXT", label, "CONFIRMED");
        var tryPart = statements(trial.getTryBlock().getStatements(), tryNode, "TRY");
        var normal = new ArrayList<>(tryPart.normal());
        var terminated = new ArrayList<>(tryPart.terminated());
        var jumps = new ArrayList<>(tryPart.jumps());
        for (var catcher : trial.getCatchClauses()) {
            var catchNode = node("CATCH", "catch (" + clip(catcher.getParameter().toString()) + ")", line(catcher), endLine(catcher), "CONFIRMED");
            edge(tryNode, catchNode, "CATCH", "CATCH", "CONFIRMED");
            var catchPart = statements(catcher.getBody().getStatements(), catchNode, "CATCH");
            normal.addAll(catchPart.normal()); terminated.addAll(catchPart.terminated()); jumps.addAll(catchPart.jumps());
        }
        if (trial.getFinallyBlock().isPresent()) {
            if (!terminated.isEmpty()) warnings.add("FINALLY_NOT_APPLIED_TO_TERMINATION");
            var finallyNode = node("FINALLY", "finally", line(trial), endLine(trial), "CONFIRMED");
            for (var exit : normal) edge(exit.node(), finallyNode, "FINALLY", "FINALLY", "CONFIRMED");
            var finallyPart = statements(trial.getFinallyBlock().get().getStatements(), finallyNode, "FINALLY");
            if (!finallyPart.terminated().isEmpty()) warnings.add("FINALLY_TERMINATION_OVERRIDES");
            normal = new ArrayList<>(finallyPart.normal());
            terminated.addAll(finallyPart.terminated());
            jumps.addAll(finallyPart.jumps());
        }
        warnings.add("TRY_CATCH_APPROXIMATED");
        if (normal.isEmpty()) return new Part(tryNode, List.of(), terminated, jumps);
        var after = node("MERGE", "try 之后", line(trial), endLine(trial), "INFERRED");
        for (var exit : normal) edge(exit.node(), after, "NEXT", exit.label(), "INFERRED");
        return new Part(tryNode, List.of(new Exit(after, "NORMAL", "", "")), terminated, jumps);
    }

    private Part switchStatement(SwitchStmt switching, String current, String label, String jumpLabel) {
        var switchNode = node("SWITCH", "switch (" + clip(switching.getSelector().toString()) + ")", line(switching), endLine(switching), "CONFIRMED");
        edge(current, switchNode, "NEXT", label, "CONFIRMED");
        var entries = switching.getEntries();
        var entryNodes = new ArrayList<String>();
        for (var entry : entries) {
            var entryNode = node("BRANCH", entry.getLabels().isEmpty() ? "default" : "case " + clip(entry.getLabels().toString()),
                    line(entry), endLine(entry), "CONFIRMED");
            entryNodes.add(entryNode);
            edge(switchNode, entryNode, "CASE", entry.getLabels().isEmpty() ? "default" : clip(entry.getLabels().toString()), "CONFIRMED");
        }
        var terminated = new ArrayList<Exit>();
        var remainingJumps = new ArrayList<Exit>();
        var normalExits = new ArrayList<Exit>();
        boolean hasDefault = entries.stream().anyMatch(entry -> entry.getLabels().isEmpty());
        boolean breaksToAfter = false;
        var after = node("MERGE", "switch 之后", line(switching), endLine(switching), "INFERRED");
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.get(index);
            var part = statements(entry.getStatements(), entryNodes.get(index), "CASE");
            terminated.addAll(part.terminated());
            for (var jump : part.jumps()) {
                if (matches(jump, jumpLabel, "BREAK")) {
                    edge(jump.node(), after, "BREAK", "BREAK", "CONFIRMED");
                    breaksToAfter = true;
                } else remainingJumps.add(jump);
            }
            boolean arrow = entry.getType() == SwitchEntry.Type.EXPRESSION || entry.getType() == SwitchEntry.Type.BLOCK;
            if (part.normal().isEmpty()) continue;
            if (!arrow && index + 1 < entries.size()) {
                for (var exit : part.normal()) edge(exit.node(), entryNodes.get(index + 1), "FALLTHROUGH", "FALLTHROUGH", "INFERRED");
            } else {
                normalExits.addAll(part.normal());
            }
        }
        for (var exit : normalExits) edge(exit.node(), after, "NEXT", exit.label(), "INFERRED");
        if (!hasDefault) {
            edge(switchNode, after, "NO_MATCH", "NO_MATCH", "INFERRED");
            warnings.add("SWITCH_NO_DEFAULT");
        }
        warnings.add("SWITCH_APPROXIMATED");
        boolean reachableAfter = !hasDefault || !normalExits.isEmpty() || breaksToAfter;
        return new Part(switchNode, reachableAfter ? List.of(new Exit(after, "NORMAL", "", "")) : List.of(), terminated, remainingJumps);
    }

    private String node(String kind, String label, int start, int end, String status) {
        if (nodes.size() >= nodeLimit) { truncated = true; return nodes.isEmpty() ? "" : (String) nodes.getLast().get("id"); }
        var id = JavaAnalyzer.id(snapshot + "|flow|" + fileId + "|" + start + "|" + end + "|" + kind + "|" + nodes.size());
        var item = new LinkedHashMap<String, Object>();
        item.put("id", id); item.put("kind", kind); item.put("label", label); item.put("status", status);
        item.put("source", Map.of("snapshotId", snapshot, "generationId", generation, "fileId", fileId, "filePath", filePath,
                "fileHash", fileHash, "startLine", Math.max(1, start), "endLine", Math.max(start, end)));
        nodes.add(item);
        return id;
    }

    private void edge(String source, String target, String kind, String label, String status) {
        if (source == null || target == null || source.isBlank() || target.isBlank()) return;
        if (source.equals(target) && !Set.of("FALSE", "LOOP_BACK", "CONTINUE").contains(kind)) return;
        var key = source + "|" + target + "|" + kind + "|" + label;
        if (!edgeKeys.add(key) || edges.size() >= MAX_EDGES) { if (edges.size() >= MAX_EDGES) truncated = true; return; }
        var item = new LinkedHashMap<String, Object>();
        item.put("id", JavaAnalyzer.id(snapshot + "|flow-edge|" + key)); item.put("source", source); item.put("target", target);
        item.put("kind", kind); item.put("label", label); item.put("status", status);
        edges.add(item);
    }

    private int line(Node node) { return node.getRange().map(range -> range.begin.line).orElse(startLine); }
    private int line(String id) {
        for (var node : nodes) if (id.equals(node.get("id"))) return ((Map<?, ?>) node.get("source")).get("startLine") instanceof Integer value ? value : startLine;
        return startLine;
    }
    private int endLine(Node node) { return node.getRange().map(range -> range.end.line).orElse(line(node)); }
    private String clip(String value) { return value == null ? "" : value.length() <= 220 ? value : value.substring(0, 219) + "…"; }
}
