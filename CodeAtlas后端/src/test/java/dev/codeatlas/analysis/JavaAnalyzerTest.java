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

    @Test void extractsFieldsVisibilityAndReturnTypesForClassDiagram() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/analyzer-temp")), "class-diagram-").toAbsolutePath();
        Files.writeString(root.resolve("Box.java"), "package p;\npublic class Box {\n private String name;\n protected int count;\n public static final long LIMIT = 1L;\n public String label() { return name; }\n private void reset() {}\n}");
        var result = new JavaAnalyzer().analyze(root, List.of(new JavaAnalyzer.SourceFile("box", "Box.java")), () -> false, n -> {});
        var type = result.symbols().stream().filter(symbol -> symbol.kind().equals("CLASS")).findFirst().orElseThrow();
        assertThat(type.visibility()).isEqualTo("PUBLIC");
        var fields = result.symbols().stream().filter(symbol -> symbol.kind().equals("FIELD")).toList();
        assertThat(fields).hasSize(3);
        assertThat(fields).extracting(JavaAnalyzer.Symbol::signature).containsExactlyInAnyOrder("name: String", "count: int", "LIMIT: long");
        assertThat(fields).extracting(JavaAnalyzer.Symbol::visibility).containsExactlyInAnyOrder("PRIVATE", "PROTECTED", "PUBLIC");
        var label = result.symbols().stream().filter(symbol -> symbol.kind().equals("METHOD") && symbol.name().equals("label")).findFirst().orElseThrow();
        assertThat(label.visibility()).isEqualTo("PUBLIC"); assertThat(label.typeText()).isEqualTo("String");
        var reset = result.symbols().stream().filter(symbol -> symbol.kind().equals("METHOD") && symbol.name().equals("reset")).findFirst().orElseThrow();
        assertThat(reset.visibility()).isEqualTo("PRIVATE"); assertThat(reset.typeText()).isEqualTo("void");
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

    private static JavaAnalyzer.Symbol symbol(List<JavaAnalyzer.Symbol> symbols, String qualified, String signature) {
        return symbols.stream().filter(s -> s.qualifiedName().equals(qualified) && (signature == null || s.signature().equals(signature)))
                .findFirst().orElseThrow(() -> new AssertionError("missing symbol " + qualified + " " + signature));
    }

    @Test void independentExpectationsCoverFrameworkRolesAndUnresolvedTargets() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/analyzer-temp")), "golden-").toAbsolutePath();
        Map<String, String> source = new LinkedHashMap<>();
        source.put("s/WelcomeController.java", """
                package s;

                import org.springframework.web.bind.annotation.*;

                @RestController
                public class WelcomeController {
                    private final GreetingService service;

                    public WelcomeController(GreetingService service) {
                        this.service = service;
                    }

                    @GetMapping("/welcome")
                    public String welcome() {
                        return service.greet("Ada");
                    }
                }
                """);
        source.put("s/GreetingService.java", """
                package s;

                public interface GreetingService {
                    String greet(String name);
                }
                """);
        source.put("s/FriendlyGreetingService.java", """
                package s;

                public class FriendlyGreetingService implements GreetingService {
                    @Override
                    public String greet(String name) {
                        return "Hello " + name;
                    }
                }
                """);
        source.put("s/FormalGreetingService.java", """
                package s;

                public class FormalGreetingService implements GreetingService {
                    @Override
                    public String greet(String name) {
                        return "Good day " + name;
                    }
                }
                """);
        source.put("s/OwnerRepository.java", """
                package s;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface OwnerRepository extends JpaRepository<Owner, Long> {
                    Owner findByLastName(String lastName);
                }
                """);
        source.put("s/Owner.java", """
                package s;

                public class Owner {
                    private Long id;
                    private String lastName;
                    public Long getId() { return id; }
                    public String getLastName() { return lastName; }
                }
                """);
        source.put("s/UsesOwner.java", """
                package s;

                import org.springframework.transaction.annotation.Transactional;

                public class UsesOwner {
                    private final OwnerRepository repository;

                    public UsesOwner(OwnerRepository repository) {
                        this.repository = repository;
                    }

                    @Transactional
                    public Owner find(String name) {
                        return repository.findByLastName(name);
                    }
                }
                """);
        source.put("s/Overloads.java", """
                package s;

                public class Overloads {
                    public Overloads() {}
                    public Overloads(int a) { this(); }
                    public int calc(int a) { return a; }
                    public int calc(int a, int b) { return a + b; }
                }
                """);
        source.put("s/RemoteClient.java", """
                package s;

                import com.external.MissingType;

                public class RemoteClient {
                    public String fetch(MissingType type) {
                        return type.toString();
                    }
                }
                """);
        source.put("s/Broken.java", """
                package s;

                public class Broken {
                    void x( }
                }
                """);
        var files = new ArrayList<JavaAnalyzer.SourceFile>();
        for (var entry : source.entrySet()) {
            Path path = root.resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, entry.getValue());
            files.add(new JavaAnalyzer.SourceFile(entry.getKey(), entry.getKey()));
        }
        var result = new JavaAnalyzer().analyze(root, files, () -> false, n -> {});
        assertThat(result.totalFiles()).isEqualTo(10);
        assertThat(result.successfulFiles()).isEqualTo(9);
        assertThat(result.diagnostics()).extracting(JavaAnalyzer.Diagnostic::code).contains("PARSE_ERROR");

        var controller = symbol(result.symbols(), "s.WelcomeController", null);
        assertThat(controller.kind()).isEqualTo("CLASS");
        assertThat(controller.startLine()).isEqualTo(5);
        assertThat(controller.annotations()).contains("RestController");
        assertThat(symbol(result.symbols(), "s.WelcomeController.service", null).typeText()).isEqualTo("GreetingService");
        assertThat(symbol(result.symbols(), "s.WelcomeController.WelcomeController", "WelcomeController(GreetingService)").kind()).isEqualTo("CONSTRUCTOR");
        var welcome = symbol(result.symbols(), "s.WelcomeController.welcome", "welcome()");
        assertThat(welcome.startLine()).isEqualTo(13);
        assertThat(welcome.annotations()).contains("GetMapping");
        assertThat(welcome.typeText()).isEqualTo("String");

        assertThat(symbol(result.symbols(), "s.GreetingService", null).kind()).isEqualTo("INTERFACE");
        assertThat(symbol(result.symbols(), "s.FriendlyGreetingService.greet", "greet(String)").typeText()).isEqualTo("String");
        assertThat(symbol(result.symbols(), "s.FormalGreetingService.greet", "greet(String)").typeText()).isEqualTo("String");
        assertThat(symbol(result.symbols(), "s.OwnerRepository", null).kind()).isEqualTo("INTERFACE");
        var findByLastName = symbol(result.symbols(), "s.OwnerRepository.findByLastName", "findByLastName(String)");
        assertThat(findByLastName.startLine()).isEqualTo(6);
        assertThat(findByLastName.typeText()).isEqualTo("Owner");
        var usesOwner = symbol(result.symbols(), "s.UsesOwner.find", "find(String)");
        assertThat(usesOwner.startLine()).isEqualTo(12);
        assertThat(symbol(result.symbols(), "s.UsesOwner.UsesOwner", "UsesOwner(OwnerRepository)").kind()).isEqualTo("CONSTRUCTOR");
        var calcOne = symbol(result.symbols(), "s.Overloads.calc", "calc(int)");
        var calcTwo = symbol(result.symbols(), "s.Overloads.calc", "calc(int,int)");
        assertThat(calcOne.startLine()).isEqualTo(6);
        assertThat(calcTwo.startLine()).isEqualTo(7);
        assertThat(calcOne.id()).isNotEqualTo(calcTwo.id());
        assertThat(symbol(result.symbols(), "s.Overloads.Overloads", "Overloads()").kind()).isEqualTo("CONSTRUCTOR");
        assertThat(symbol(result.symbols(), "s.Overloads.Overloads", "Overloads(int)").kind()).isEqualTo("CONSTRUCTOR");
        assertThat(symbol(result.symbols(), "s.RemoteClient.fetch", "fetch(MissingType)").typeText()).isEqualTo("String");

        var implementations = result.relations().stream().filter(r -> r.kind().equals("IMPLEMENTS") && r.targetText().contains("GreetingService")).toList();
        assertThat(implementations).hasSize(2).allSatisfy(r -> assertThat(r.status()).isEqualTo("CONFIRMED"));
        var missingParent = result.relations().stream().filter(r -> r.kind().equals("EXTENDS") && r.targetText().contains("JpaRepository"))
                .findFirst().orElseThrow();
        assertThat(missingParent.status()).isEqualTo("UNRESOLVED");
        assertThat(missingParent.reason()).containsAnyOf("无法解析类型依赖", "类型位于索引外部");

        var declaredCall = result.relations().stream().filter(r -> r.kind().equals("CALL") && r.targetText().contains("GreetingService.greet"))
                .findFirst().orElseThrow();
        assertThat(declaredCall.status()).isEqualTo("CONFIRMED");
        assertThat(declaredCall.targetId()).isEqualTo(symbol(result.symbols(), "s.GreetingService.greet", "greet(String)").id());
        assertThat(result.relations()).anySatisfy(r -> {
            assertThat(r.kind()).isEqualTo("RUNTIME_CALL");
            assertThat(r.targetText()).contains("GreetingService.greet");
            assertThat(r.status()).isEqualTo("UNRESOLVED");
        });
        var repositoryCall = result.relations().stream().filter(r -> r.kind().equals("CALL") && r.targetText().contains("findByLastName"))
                .findFirst().orElseThrow();
        assertThat(repositoryCall.status()).isEqualTo("CONFIRMED");
        assertThat(repositoryCall.targetId()).isEqualTo(findByLastName.id());
        var dynamicCall = result.relations().stream().filter(r -> r.kind().equals("CALL") && r.targetText().contains("toString"))
                .findFirst().orElseThrow();
        assertThat(dynamicCall.status()).isEqualTo("UNRESOLVED");
        assertThat(dynamicCall.reason()).isNotBlank();

        var transaction = result.relations().stream().filter(r -> r.kind().equals("TRANSACTION_BOUNDARY")).findFirst().orElseThrow();
        assertThat(transaction.status()).isEqualTo("INFERRED");
        assertThat(transaction.startLine()).isEqualTo(12);
        assertThat(transaction.resolver()).isEqualTo("SPRING_TRANSACTIONAL_DECLARATION_V1");
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
