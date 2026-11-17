package dev.codeatlas.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ControlFlowBuilderTest {
    private ControlFlowBuilder.Result build(String body) {
        var unit = StaticJavaParser.parse("class X { void m(boolean ok, boolean other, int x) " + body + " }");
        var method = unit.findFirst(MethodDeclaration.class).orElseThrow();
        return ControlFlowBuilder.build("snapshot", "generation", "file", "X.java", "hash",
                1, 200, 180, method.getBody().orElseThrow());
    }

    private Map<String, Object> node(ControlFlowBuilder.Result result, String labelPart) {
        return result.nodes().stream().filter(node -> String.valueOf(node.get("label")).contains(labelPart)).findFirst().orElseThrow();
    }

    private Map<String, Object> nodeById(ControlFlowBuilder.Result result, String id) {
        return result.nodes().stream().filter(node -> id.equals(node.get("id"))).findFirst().orElseThrow();
    }

    private List<String> incomingKinds(ControlFlowBuilder.Result result, String target) {
        return result.edges().stream().filter(edge -> target.equals(edge.get("target")))
                .map(edge -> String.valueOf(nodeById(result, String.valueOf(edge.get("source"))).get("kind"))).toList();
    }

    private boolean hasEdge(ControlFlowBuilder.Result result, String sourceKind, String targetKind, String label) {
        return result.edges().stream().anyMatch(edge -> label.equals(edge.get("label"))
                && sourceKind.equals(nodeById(result, String.valueOf(edge.get("source"))).get("kind"))
                && targetKind.equals(nodeById(result, String.valueOf(edge.get("target"))).get("kind")));
    }

    @Test void ifWithoutElseConnectsFollowingStatementToMerge() {
        var result = build("{ if (ok) { a(); } b(); }");
        var b = node(result, "b();");
        assertThat(incomingKinds(result, String.valueOf(b.get("id")))).contains("MERGE").doesNotContain("DECISION");
        assertThat(hasEdge(result, "DECISION", "MERGE", "FALSE")).isTrue();
    }

    @Test void ifElseBothReturnEndAtReturnNodes() {
        var result = build("{ if (ok) { return; } else { return; } }");
        var end = result.nodes().stream().filter(node -> "END".equals(node.get("kind"))).findFirst().orElseThrow();
        assertThat(incomingKinds(result, String.valueOf(end.get("id")))).containsOnly("RETURN");
    }

    @Test void loopConsumesBreakAndContinueBeforeFollowingStatement() {
        var result = build("{ for (int i = 0; i < 3; i++) { if (i == 1) { break; } if (i == 0) { continue; } a(); } b(); }");
        var b = node(result, "b();");
        assertThat(incomingKinds(result, String.valueOf(b.get("id")))).contains("MERGE").doesNotContain("JUMP");
        var loopEnd = node(result, "循环结束");
        assertThat(result.edges().stream().anyMatch(edge -> "BREAK".equals(edge.get("kind"))
                && String.valueOf(loopEnd.get("id")).equals(edge.get("target")))).isTrue();
        var loop = node(result, "FOR");
        assertThat(hasEdge(result, "JUMP", "LOOP", "CONTINUE")).isTrue();
        assertThat(result.edges().stream().anyMatch(edge -> "LOOP_BACK".equals(edge.get("kind"))
                && String.valueOf(loop.get("id")).equals(edge.get("target")))).isTrue();
    }

    @Test void terminalStatementMarksFollowingStatementsUnreachable() {
        var result = build("{ return; a(); }");
        assertThat(result.warnings()).contains("UNREACHABLE_STATEMENTS");
        assertThat(result.nodes().stream().map(node -> String.valueOf(node.get("label")))).noneMatch(label -> label.contains("a();"));
    }

    @Test void tryFinallyTerminationIsExplicitlyDiagnosed() {
        var result = build("{ try { return; } finally { a(); } }");
        assertThat(result.warnings()).contains("FINALLY_NOT_APPLIED_TO_TERMINATION", "TRY_CATCH_APPROXIMATED");
        assertThat(result.edges().stream().anyMatch(edge -> "RETURN".equals(nodeById(result, String.valueOf(edge.get("source"))).get("kind"))
                && "END".equals(nodeById(result, String.valueOf(edge.get("target"))).get("kind")))).isTrue();
        assertThat(result.edges().stream().anyMatch(edge -> "RETURN".equals(nodeById(result, String.valueOf(edge.get("source"))).get("kind"))
                && "FINALLY".equals(nodeById(result, String.valueOf(edge.get("target"))).get("kind")))).isFalse();
    }

    @Test void switchBreakAndDefaultReachFollowingStatement() {
        var result = build("{ switch (x) { case 1: a(); break; default: b(); } c(); }");
        var c = node(result, "c();");
        assertThat(incomingKinds(result, String.valueOf(c.get("id")))).contains("MERGE").doesNotContain("SWITCH", "JUMP");
        assertThat(hasEdge(result, "JUMP", "MERGE", "BREAK")).isTrue();
        assertThat(result.edges().stream().anyMatch(edge -> "SWITCH".equals(nodeById(result, String.valueOf(edge.get("source"))).get("kind"))
                && "BRANCH".equals(nodeById(result, String.valueOf(edge.get("target"))).get("kind"))
                && "default".equals(edge.get("label")))).isTrue();
    }

    @Test void nestedIfUsesMergeForFollowingStatement() {
        var result = build("{ if (ok) { if (other) { a(); } else { b(); } } c(); }");
        var c = node(result, "c();");
        assertThat(incomingKinds(result, String.valueOf(c.get("id")))).contains("MERGE").doesNotContain("DECISION");
    }

    @Test void emptyMethodStillHasStartAndEnd() {
        var result = build("{}");
        assertThat(result.nodes()).extracting(node -> node.get("kind")).contains("START", "END");
        assertThat(hasEdge(result, "START", "END", "")).isTrue();
    }
}
