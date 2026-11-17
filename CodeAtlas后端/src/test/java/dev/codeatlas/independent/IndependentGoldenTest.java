package dev.codeatlas.independent;

import dev.codeatlas.analysis.JavaAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndependentGoldenTest {
    @TempDir Path root;
    @Test void checksMultiModuleDeclarationsAndMalformedFileIsolation() throws Exception {
        Map<String,String> files = new LinkedHashMap<>();
        files.put("one/src/p/Api.java", "package p;\npublic interface Api<T> { T run(T x); }\n");
        files.put("one/src/p/Base.java", "package p;\npublic abstract class Base { public abstract void go(); }\n");
        files.put("one/src/p/Worker.java", "package p;\n@Deprecated public class Worker extends Base implements Api<String> {\n public String run(String x) { return x; }\n public int run(int x) { return x; }\n public void go() {}\n class Inner { void nested() {} }\n}\n");
        files.put("two/src/q/Worker.java", "package q;\npublic class Worker { void other() {} }\n");
        files.put("two/src/q/Caller.java", "package q;\nimport p.Worker;\nclass Caller { void call(Worker w) { w.run(1); } }\n");
        files.put("two/src/q/Missing.java", "package q;\nclass Missing { Unknown field; void f(){ field.nope(); } }\n");
        files.put("two/src/q/Broken.java", "package q; class Broken { void f( }\n");
        var inputs = new ArrayList<JavaAnalyzer.SourceFile>();
        for (var e:files.entrySet()) { Path p=root.resolve(e.getKey()); Files.createDirectories(p.getParent()); Files.writeString(p,e.getValue()); inputs.add(new JavaAnalyzer.SourceFile(e.getKey(),e.getKey())); }
        var a=new JavaAnalyzer().analyze(root,inputs,()->false,n->{});
        assertEquals(7,a.totalFiles()); assertEquals(6,a.successfulFiles());
        assertEquals("PARSE_ERROR",a.diagnostics().getFirst().code());
        assertTrue(a.symbols().stream().anyMatch(s->s.qualifiedName().equals("p.Api") && s.kind().equals("INTERFACE") && s.startLine()==2));
        assertTrue(a.symbols().stream().anyMatch(s->s.qualifiedName().equals("p.Worker") && s.annotations().contains("Deprecated") && s.startLine()==2));
        assertTrue(a.symbols().stream().anyMatch(s->s.qualifiedName().equals("p.Worker.Inner.nested") && s.startLine()==6));
        assertEquals(Set.of("run(String)","run(int)"),a.symbols().stream().filter(s->s.qualifiedName().equals("p.Worker.run")).map(JavaAnalyzer.Symbol::signature).collect(java.util.stream.Collectors.toSet()));
        assertTrue(a.symbols().stream().anyMatch(s->s.qualifiedName().equals("q.Worker")));
        assertTrue(a.relations().stream().anyMatch(r->r.kind().equals("EXTENDS") && r.status().equals("CONFIRMED")));
        assertTrue(a.relations().stream().anyMatch(r->r.kind().equals("IMPLEMENTS") && r.status().equals("CONFIRMED")));
        assertTrue(a.relations().stream().anyMatch(r->r.fileId().endsWith("Missing.java") && r.status().equals("UNRESOLVED")));
        assertTrue(a.relations().stream().anyMatch(r->r.fileId().endsWith("Caller.java") && r.kind().equals("CALL") && r.status().equals("CONFIRMED") && r.startLine()==3));
    }
}
