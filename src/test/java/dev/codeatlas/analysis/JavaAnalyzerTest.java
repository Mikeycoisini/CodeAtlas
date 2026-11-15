package dev.codeatlas.analysis;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JavaAnalyzerTest {
    @Test void infersOnlyUnambiguousSpringTransactionalMethodDeclarations() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/analyzer-temp")), "transactional-").toAbsolutePath();
        Map<String, String> source = new LinkedHashMap<>();
        source.put("p/Tx.java", "package p; import org.springframework.transaction.annotation.Transactional; class Tx {\n @Transactional public void pay() {}\n public void plain() {} }");
        source.put("p/Qualified.java", "package p; class Qualified { @org.springframework.transaction.annotation.Transactional void run() {} }");
        source.put("q/Shadow.java", "package q; import org.springframework.transaction.annotation.Transactional; class Shadow { @interface Transactional {} @Transactional void run() {} }");
        source.put("r/Wildcard.java", "package r; import org.springframework.transaction.annotation.*; class Wildcard { @Transactional void run() {} }");
        source.put("s/Local.java", "package s; @interface Transactional {} class Local { @Transactional void run() {} }");
        source.put("t/Sibling.java", "package t; import org.springframework.transaction.annotation.Transactional; class Service { @Transactional void run() {} } class Other { @interface Transactional {} }");
        source.put("u/Base.java", "package u; class Base { public @interface Transactional {} }");
        source.put("u/Child.java", "package u; import org.springframework.transaction.annotation.Transactional; class Child extends Base { @Transactional void run() {} }");
        var files = new ArrayList<JavaAnalyzer.SourceFile>();
        for (var entry : source.entrySet()) {
            Path path = root.resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, entry.getValue());
            files.add(new JavaAnalyzer.SourceFile(entry.getKey(), entry.getKey()));
        }
        var result = new JavaAnalyzer().analyze(root, files, () -> false, n -> {});
        var inferred = result.relations().stream().filter(r -> r.status().equals("INFERRED")).toList();
        assertThat(inferred).hasSize(3).allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo("TRANSACTION_BOUNDARY");
            assertThat(r.reason()).contains("SPRING_TRANSACTIONAL_DECLARATION_V1", "运行时");
            assertThat(r.resolver()).isEqualTo("SPRING_TRANSACTIONAL_DECLARATION_V1");
            assertThat(r.targetId()).isEqualTo(r.sourceId());
        });
        assertThat(inferred).extracting(JavaAnalyzer.Relation::fileId).containsExactlyInAnyOrder("p/Tx.java", "p/Qualified.java", "t/Sibling.java");
        assertThat(inferred.stream().filter(r -> r.fileId().equals("p/Tx.java")).findFirst().orElseThrow().startLine()).isEqualTo(2);
    }
    @Test void rejectsMalformedUtf8InsteadOfIndexingReplacementCharacters() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/analyzer-temp")), "encoding-").toAbsolutePath();
        Files.write(root.resolve("Bad.java"), new byte[]{'c','l','a','s','s',' ','A',' ','{',' ','/','/',(byte)255,'\n','}'});
        var result = new JavaAnalyzer().analyze(root, List.of(new JavaAnalyzer.SourceFile("bad", "Bad.java")), () -> false, n -> {});
        assertThat(result.symbols()).isEmpty();
        assertThat(result.diagnostics()).extracting(JavaAnalyzer.Diagnostic::code).containsExactly("READ_OR_ENCODING_ERROR");
    }

    @Test void duplicateQualifiedTypesNeverConfirmAnArbitrarilyChosenMethod() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/analyzer-temp")), "duplicate-").toAbsolutePath();
        Files.createDirectories(root.resolve("one/p"));
        Files.createDirectories(root.resolve("two/p"));
        Files.writeString(root.resolve("one/p/A.java"), "package p; public class A { public void foo() {} }");
        Files.writeString(root.resolve("two/p/A.java"), "package p; public class A { public void bar() {} }");
        Files.writeString(root.resolve("one/p/C.java"), "package p; class C { void run(A a) { a.foo(); } }");
        var result = new JavaAnalyzer().analyze(root, List.of(new JavaAnalyzer.SourceFile("a1", "one/p/A.java"),
                new JavaAnalyzer.SourceFile("a2", "two/p/A.java"), new JavaAnalyzer.SourceFile("c", "one/p/C.java")), () -> false, n -> {});
        assertThat(result.relations().stream().filter(r -> r.kind().equals("CALL"))).hasSize(1)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo("UNRESOLVED"));
    }

    @Test
    void preservesOverloadsLocationsAndPartialResults() throws Exception {
        Path root = Path.of("test-fixtures/spring-sample").toAbsolutePath().normalize();
        List<JavaAnalyzer.SourceFile> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile).map(p -> new JavaAnalyzer.SourceFile(
                    root.relativize(p).toString().replace('\\', '/'), root.relativize(p).toString().replace('\\', '/'))).toList();
        }
        var result = new JavaAnalyzer().analyze(root, files, () -> false, count -> {});
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().getFirst().code()).isEqualTo("PARSE_ERROR");
        var overloads = result.symbols().stream().filter(s -> s.qualifiedName().equals("sample.PaymentService.pay")).toList();
        assertThat(overloads).hasSize(2);
        assertThat(overloads).extracting(JavaAnalyzer.Symbol::signature).containsExactlyInAnyOrder("pay(String)", "pay(String,int)");
        assertThat(overloads.stream().filter(s -> s.signature().equals("pay(String)")).findFirst().orElseThrow().startLine()).isEqualTo(6);
        assertThat(overloads.stream().filter(s -> s.signature().equals("pay(String)")).findFirst().orElseThrow().annotations()).contains("Transactional");
        assertThat(result.relations()).anySatisfy(r -> {
            assertThat(r.kind()).isEqualTo("IMPLEMENTS");
            assertThat(r.status()).isEqualTo("CONFIRMED");
        });
        assertThat(result.relations().stream().filter(r -> r.fileId().endsWith("Checkout.java") && r.kind().equals("RUNTIME_CALL")))
                .anySatisfy(r -> assertThat(r.status()).isEqualTo("UNRESOLVED"));
        assertThat(result.relations().stream().filter(r -> r.targetText().contains("audit")))
                .anySatisfy(r -> assertThat(r.status()).isEqualTo("CONFIRMED"));
    }
}
