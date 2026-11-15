package dev.codeatlas.analysis;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

public class JavaAnalyzer {
    public static final String VERSION = "javaparser-3.27.1-v2";
    public record SourceFile(String id, String path) {}
    public record Symbol(String id, String fileId, String kind, String name, String qualifiedName,
                         String signature, int startLine, int startColumn, int endLine, int endColumn,
                         List<String> annotations) {}
    public record Relation(String sourceId, String targetId, String targetText, String kind, String status,
                           String reason, String fileId, int startLine, int startColumn, int endLine,
                           int endColumn, String resolver) {}
    public record Diagnostic(String fileId, String code, String message, int startLine) {}
    public record Analysis(List<Symbol> symbols, List<Relation> relations, List<Diagnostic> diagnostics,
                           int successfulFiles, int totalFiles) {}
    private record Parsed(SourceFile file, CompilationUnit unit) {}

    public Analysis analyze(Path root, List<SourceFile> files, BooleanSupplier cancelled, Consumer<Integer> progress) {
        return analyze(root, files, cancelled, progress, partial -> {});
    }

    public Analysis analyze(Path root, List<SourceFile> files, BooleanSupplier cancelled, Consumer<Integer> progress, Consumer<Analysis> partial) {
        var symbols = new ArrayList<Symbol>();
        var relations = new ArrayList<Relation>();
        var diagnostics = new ArrayList<Diagnostic>();
        var parsed = new ArrayList<Parsed>();
        var roots = new LinkedHashSet<Path>();
        roots.add(root);
        var configuration = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        var parser = new JavaParser(configuration);
        int count = 0;
        for (var file : files) {
            checkCancelled(cancelled);
            if (!file.path().endsWith(".java")) continue;
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Files.readAllBytes(root.resolve(file.path())))).toString();
                var result = parser.parse(text);
                if (!result.isSuccessful() || result.getResult().isEmpty()) {
                    diagnostics.add(new Diagnostic(file.id(), "PARSE_ERROR", result.getProblems().toString(), 1));
                } else {
                    var unit = result.getResult().orElseThrow();
                    unit.setStorage(root.resolve(file.path()), StandardCharsets.UTF_8);
                    parsed.add(new Parsed(file, unit));
                    Path sourceRoot = root.resolve(file.path()).getParent();
                    int levels = unit.getPackageDeclaration().map(p -> p.getNameAsString().split("\\.").length).orElse(0);
                    for (int i = 0; i < levels && sourceRoot != null; i++) sourceRoot = sourceRoot.getParent();
                    if (sourceRoot != null && sourceRoot.startsWith(root)) roots.add(sourceRoot);
                }
            } catch (Exception e) {
                diagnostics.add(new Diagnostic(file.id(), "READ_OR_ENCODING_ERROR", e.getClass().getSimpleName(), 1));
            }
            var fileDiagnostics = diagnostics.stream().filter(d -> d.fileId().equals(file.id())).toList();
            if (!fileDiagnostics.isEmpty()) partial.accept(new Analysis(List.of(), List.of(), fileDiagnostics, 0, 1));
            progress.accept(++count);
        }
        var solver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        for (Path sourceRoot : roots) solver.add(new JavaParserTypeSolver(sourceRoot, configuration));
        configuration.setSymbolResolver(new JavaSymbolSolver(solver));
        // Attach the resolver to already parsed units as well as units loaded by the type solver.
        var resolver = new JavaSymbolSolver(solver);
        var byNode = new IdentityHashMap<Node, Symbol>();
        var methods = new HashMap<String, List<Symbol>>();
        var types = new HashMap<String, List<Symbol>>();
        for (var item : parsed) {
            checkCancelled(cancelled);
            int beforeSymbols = symbols.size();
            resolver.inject(item.unit());
            for (TypeDeclaration<?> type : item.unit().findAll(TypeDeclaration.class)) {
                String qualified = qualifiedType(type);
                String kind = type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface() ? "INTERFACE" : "CLASS";
                var symbol = symbol(item.file(), type, kind, type.getNameAsString(), qualified, type.getNameAsString());
                symbols.add(symbol);
                byNode.put(type, symbol);
                types.computeIfAbsent(qualified, k -> new ArrayList<>()).add(symbol);
            }
            for (CallableDeclaration<?> method : item.unit().findAll(CallableDeclaration.class)) {
                var owner = method.findAncestor(TypeDeclaration.class);
                if (owner.isEmpty()) continue;
                String signature = method.getNameAsString() + "(" + String.join(",", method.getParameters().stream()
                        .map(p -> p.getTypeAsString() + (p.isVarArgs() ? "..." : "")).toList()) + ")";
                var symbol = symbol(item.file(), method, method instanceof ConstructorDeclaration ? "CONSTRUCTOR" : "METHOD",
                        method.getNameAsString(), qualifiedType(owner.get()) + "." + method.getNameAsString(), signature);
                symbols.add(symbol);
                byNode.put(method, symbol);
                try {
                    String resolved = method instanceof MethodDeclaration m ? m.resolve().getQualifiedSignature()
                            : ((ConstructorDeclaration) method).resolve().getQualifiedSignature();
                    methods.computeIfAbsent(resolved, k -> new ArrayList<>()).add(symbol);
                } catch (RuntimeException ignored) {
                    // Declaration positions remain useful when parameter dependencies cannot be resolved.
                }
            }
            partial.accept(new Analysis(List.copyOf(symbols.subList(beforeSymbols, symbols.size())), List.of(), List.of(), 1, 1));
        }
        for (var item : parsed) {
            checkCancelled(cancelled);
            for (var method : item.unit().findAll(MethodDeclaration.class)) {
                for (var annotation : method.getAnnotations()) {
                    if (springTransactional(annotation, method, item.unit())) {
                        var symbol = byNode.get(method);
                        if (symbol == null) continue;
                        var range = annotation.getRange().orElseThrow();
                        String rule = "SPRING_TRANSACTIONAL_DECLARATION_V1";
                        relations.add(new Relation(symbol.id(), symbol.id(), "Spring 事务代理候选 · " + symbol.signature(),
                                "TRANSACTION_BOUNDARY", "INFERRED", rule + "：Spring @Transactional 声明；运行时仍取决于代理启用、调用路径和事务管理器",
                                symbol.fileId(), range.begin.line, range.begin.column, range.end.line, range.end.column, rule));
                    }
                }
            }
            for (var type : item.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                for (var parent : type.getExtendedTypes()) relations.add(typeRelation(byNode.get(type), parent, "EXTENDS", types));
                for (var parent : type.getImplementedTypes()) relations.add(typeRelation(byNode.get(type), parent, "IMPLEMENTS", types));
            }
            for (var call : item.unit().findAll(MethodCallExpr.class)) {
                var owner = call.findAncestor(CallableDeclaration.class);
                if (owner.isEmpty() || !byNode.containsKey(owner.get())) continue;
                var source = byNode.get(owner.get());
                try {
                    var target = call.resolve();
                    String text = target.getQualifiedSignature();
                    var candidates = methods.getOrDefault(text, List.of());
                    boolean uniqueOwner = types.getOrDefault(target.declaringType().getQualifiedName(), List.of()).size() == 1;
                    Symbol local = uniqueOwner && candidates.size() == 1 ? candidates.getFirst() : null;
                    relations.add(relation(source, local, text, "CALL", local == null ? "UNRESOLVED" : "CONFIRMED",
                            local == null ? "目标位于索引外部或存在重复声明" : "编译期声明目标", call));
                    if (!target.isStatic() && target.accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PRIVATE) {
                        relations.add(relation(source, null, text, "RUNTIME_CALL", "UNRESOLVED",
                                "虚调用的运行时实现取决于实际对象及框架配置", call));
                    }
                } catch (RuntimeException e) {
                    relations.add(relation(source, null, call.toString(), "CALL", "UNRESOLVED", "依赖缺失、歧义或不支持的表达式", call));
                }
            }
        }
        int total = (int) files.stream().filter(f -> f.path().endsWith(".java")).count();
        return new Analysis(symbols, relations, diagnostics, parsed.size(), total);
    }

    private boolean springTransactional(AnnotationExpr annotation, MethodDeclaration method, CompilationUnit unit) {
        String qualified = "org.springframework.transaction.annotation.Transactional";
        String name = annotation.getNameAsString();
        if (!name.equals(qualified) && !name.equals("Transactional")) return false;
        String prefix = name.equals(qualified) ? "org" : "Transactional";
        if (unit.getTypes().stream().anyMatch(type -> type.getNameAsString().equals(prefix))) return false;
        for (Node scope = method; scope != null; scope = scope.getParentNode().orElse(null)) {
            if (!(scope instanceof TypeDeclaration<?> type)) continue;
            if (type.getMembers().stream().anyMatch(member -> member instanceof TypeDeclaration<?> nested && nested.getNameAsString().equals(prefix))) return false;
            if (type instanceof ClassOrInterfaceDeclaration declaration) {
                try {
                    for (var ancestor : declaration.resolve().getAllAncestors()) {
                        var parent = ancestor.getTypeDeclaration();
                        if (parent.isEmpty() || parent.get().hasInternalType(prefix)) return false;
                    }
                } catch (RuntimeException e) {
                    // Unresolved ancestors may introduce a shadowing member type.
                    return false;
                }
            }
        }
        try { return annotation.resolve().getQualifiedName().equals(qualified); }
        catch (RuntimeException e) { /* Explicit syntax remains useful without external Spring dependencies. */ }
        if (name.equals(qualified)) return true;
        var explicit = unit.getImports().stream().filter(item -> !item.isAsterisk() && !item.isStatic()
                && item.getName().getIdentifier().equals("Transactional")).toList();
        return explicit.size() == 1 && explicit.getFirst().getNameAsString().equals(qualified);
    }

    private Relation typeRelation(Symbol source, ClassOrInterfaceType parent, String kind, Map<String, List<Symbol>> types) {
        try {
            String name = parent.resolve().asReferenceType().getQualifiedName();
            var targets = types.getOrDefault(name, List.of());
            Symbol target = targets.size() == 1 ? targets.getFirst() : null;
            return relation(source, target, name, kind, target == null ? "UNRESOLVED" : "CONFIRMED",
                    target == null ? "类型位于索引外部或存在歧义" : "源码类型声明", parent);
        } catch (RuntimeException e) {
            return relation(source, null, parent.asString(), kind, "UNRESOLVED", "无法解析类型依赖", parent);
        }
    }

    private Symbol symbol(SourceFile file, Node node, String kind, String name, String qualified, String signature) {
        var range = node.getRange().orElseThrow();
        List<String> annotations = node instanceof NodeWithAnnotations<?> annotated
                ? annotated.getAnnotations().stream().map(a -> a.getNameAsString()).toList() : List.of();
        return new Symbol(id(file.path() + "|" + qualified + "|" + signature + "|" + range.begin), file.id(), kind,
                name, qualified, signature, range.begin.line, range.begin.column, range.end.line, range.end.column, annotations);
    }

    private Relation relation(Symbol source, Symbol target, String text, String kind, String status, String reason, Node evidence) {
        var range = evidence.getRange().orElseThrow();
        return new Relation(source.id(), target == null ? null : target.id(), text, kind, status, reason, source.fileId(),
                range.begin.line, range.begin.column, range.end.line, range.end.column, "JavaParser/SymbolSolver");
    }

    private String qualifiedType(TypeDeclaration<?> type) {
        return type.getFullyQualifiedName().orElseGet(() -> type.getNameAsString() + "@" + type.getBegin().orElseThrow());
    }

    public static String id(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString(); }
    private void checkCancelled(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException(); }
}
