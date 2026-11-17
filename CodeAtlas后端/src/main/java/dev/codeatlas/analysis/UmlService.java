package dev.codeatlas.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.nodeTypes.modifiers.NodeWithAccessModifiers;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.tasks.TaskService;
import dev.codeatlas.web.ApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Source-backed UML detail for one fixed type, independent of the 50-member graph cap. */
@Service
public class UmlService {
    private final AtlasStore store;
    private final TaskService tasks;

    public UmlService(AtlasStore store, TaskService tasks) { this.store = store; this.tasks = tasks; }

    private record SymbolInfo(String id, String kind, String name, String qualifiedName, String fileId, int startLine, int endLine) {}

    public Map<String, Object> uml(String snapshot, String generationId, String typeId) {
        var generation = store.generation(snapshot, generationId);
        if (generation == null) throw new ApiException(409, "NO_GENERATION", "该快照尚无已发布索引");
        var symbols = new LinkedHashMap<String, SymbolInfo>();
        store.scanPayloads("SELECT id,payload FROM symbols WHERE generation_id=?", raw -> {
            var kind = raw.path("kind").asText();
            if (kind.equals("CLASS") || kind.equals("INTERFACE")) {
                var info = new SymbolInfo(raw.path("id").asText(), kind, raw.path("name").asText(), raw.path("qualifiedName").asText(),
                        raw.path("fileId").asText(), raw.path("startLine").asInt(), raw.path("endLine").asInt());
                symbols.put(info.id(), info);
            }
        }, generation);
        var type = symbols.get(typeId);
        if (type == null) throw ApiException.missing();
        var file = store.one("SELECT * FROM snapshot_files WHERE snapshot_id=? AND id=?", snapshot, type.fileId());
        var path = (String) file.get("path");
        try {
            var source = Files.readString(tasks.snapshotRoot(snapshot).resolve(path), StandardCharsets.UTF_8);
            var unit = StaticJavaParser.parse(source);
            var declaration = unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(item -> item.getNameAsString().equals(type.name()) && contains(item, type.startLine(), type.endLine()))
                    .findFirst().orElse(null);
            if (declaration == null) throw new ApiException(400, "UML_SOURCE_MISSING", "无法在当前固定快照中定位类型声明");
            var fields = new ArrayList<Map<String, Object>>();
            var methods = new ArrayList<Map<String, Object>>();
            for (var field : declaration.getFields()) {
                for (var variable : field.getVariables()) {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("id", JavaAnalyzer.id("uml|field|" + type.id() + "|" + variable.getNameAsString() + "|" + line(variable)));
                    item.put("name", variable.getNameAsString()); item.put("typeText", variable.getTypeAsString());
                    item.put("visibility", visibility(field)); item.put("modifiers", field.getModifiers().stream().map(modifier -> modifier.getKeyword().toString()).toList());
                    item.put("annotations", field.getAnnotations().stream().map(annotation -> annotation.getNameAsString()).toList());
                    item.put("source", source(file, symbol(type), line(variable), endLine(variable)));
                    fields.add(item);
                }
            }
            for (var method : declaration.getMethods()) {
                var item = new LinkedHashMap<String, Object>();
                item.put("id", JavaAnalyzer.id("uml|method|" + type.id() + "|" + method.getSignature().asString() + "|" + line(method)));
                item.put("name", method.getNameAsString()); item.put("signature", method.getSignature().asString());
                item.put("returnType", method.getTypeAsString()); item.put("visibility", visibility(method));
                item.put("modifiers", method.getModifiers().stream().map(modifier -> modifier.getKeyword().toString()).toList());
                item.put("annotations", method.getAnnotations().stream().map(annotation -> annotation.getNameAsString()).toList());
                item.put("source", source(file, symbol(type), line(method), endLine(method)));
                methods.add(item);
            }
            for (var constructor : declaration.getConstructors()) {
                var item = new LinkedHashMap<String, Object>();
                item.put("id", JavaAnalyzer.id("uml|constructor|" + type.id() + "|" + constructor.getSignature().asString() + "|" + line(constructor)));
                item.put("name", constructor.getNameAsString()); item.put("signature", constructor.getSignature().asString());
                item.put("returnType", ""); item.put("visibility", visibility(constructor));
                item.put("modifiers", constructor.getModifiers().stream().map(modifier -> modifier.getKeyword().toString()).toList());
                item.put("annotations", constructor.getAnnotations().stream().map(annotation -> annotation.getNameAsString()).toList());
                item.put("source", source(file, symbol(type), line(constructor), endLine(constructor)));
                methods.add(item);
            }
            var associations = new ArrayList<Map<String, Object>>();
            for (var field : declaration.getFields()) for (var variable : field.getVariables())
                associations.addAll(associations(variable.getNameAsString(), variable.getType(), field, symbols, generation, file));
            var relations = new ArrayList<Map<String, Object>>();
            store.scanPayloads("SELECT id,payload FROM relations WHERE generation_id=? AND source_id=?", raw -> {
                var kind = raw.path("kind").asText();
                if (!kind.equals("EXTENDS") && !kind.equals("IMPLEMENTS")) return;
                var item = new LinkedHashMap<String, Object>();
                item.put("kind", kind); item.put("status", raw.path("status").asText());
                item.put("targetId", raw.path("targetId").isTextual() ? raw.path("targetId").asText() : "");
                item.put("targetText", raw.path("targetText").asText());
                item.put("source", Map.of("fileId", raw.path("fileId").asText(), "startLine", raw.path("startLine").asInt(),
                        "endLine", raw.path("endLine").asInt(), "startColumn", raw.path("startColumn").asInt(1), "endColumn", raw.path("endColumn").asInt(1)));
                relations.add(item);
            }, generation, type.id());
            var result = new LinkedHashMap<String, Object>();
            result.put("snapshotId", snapshot); result.put("generationId", generation);
            result.put("type", Map.of("id", type.id(), "name", type.name(), "qualifiedName", type.qualifiedName(), "kind", type.kind(),
                    "source", source(file, symbol(type), type.startLine(), type.endLine())));
            result.put("fields", fields); result.put("methods", methods); result.put("associations", associations);
            result.put("relations", relations);
            result.put("totalFields", fields.size()); result.put("totalMethods", methods.size());
            result.put("truncated", fields.size() > 80 || methods.size() > 80);
            result.put("warnings", List.of("UML_ASSOCIATIONS_ARE_STATIC_CANDIDATES", "COMPOSITION_LIFECYCLE_UNKNOWN"));
            return result;
        } catch (ApiException error) { throw error;
        } catch (Exception error) {
            throw new ApiException(422, "UML_PARSE_ERROR", "当前类型的源码无法生成 UML 详情");
        }
    }

    private boolean contains(com.github.javaparser.ast.Node node, int start, int end) {
        return node.getRange().map(range -> range.begin.line >= Math.max(start, 1) && range.end.line <= Math.max(end, start)).orElse(false);
    }

    private List<Map<String, Object>> associations(String fieldName, com.github.javaparser.ast.type.Type type,
                                                   FieldDeclaration field, Map<String, SymbolInfo> symbols, String generation, Map<String, Object> file) {
        var result = new ArrayList<Map<String, Object>>();
        var raw = type.asString();
        var multiplicity = "1";
        var kind = "ASSOCIATION";
        com.github.javaparser.ast.type.Type targetType = type;
        if (type instanceof ArrayType array) { targetType = array.getComponentType(); multiplicity = "0..*"; kind = "AGGREGATION_CANDIDATE"; }
        else if (type instanceof ClassOrInterfaceType classType) {
            var name = classType.getNameAsString();
            if (Set.of("List", "Set", "Collection", "Iterable", "Queue", "Deque").contains(name) && classType.getTypeArguments().isPresent()) {
                targetType = classType.getTypeArguments().get().get(0); multiplicity = "0..*"; kind = "AGGREGATION_CANDIDATE";
            } else if (name.equals("Optional") && classType.getTypeArguments().isPresent()) {
                targetType = classType.getTypeArguments().get().get(0); multiplicity = "0..1";
            } else if (name.equals("Map") && classType.getTypeArguments().isPresent() && classType.getTypeArguments().get().size() > 1) {
                targetType = classType.getTypeArguments().get().get(1); multiplicity = "0..*"; kind = "AGGREGATION_CANDIDATE";
            }
        }
        var targetName = targetType instanceof ClassOrInterfaceType value ? value.getNameAsString() : targetType.asString();
        if (targetType.isPrimitiveType() || Set.of("String", "Integer", "Long", "Boolean", "Double", "Float", "BigDecimal", "LocalDate", "LocalDateTime", "Instant").contains(targetName)) return result;
        var matches = symbols.values().stream().filter(candidate -> candidate.name().equals(targetName)).toList();
        var item = new LinkedHashMap<String, Object>();
        item.put("id", JavaAnalyzer.id("uml|association|" + generation + "|" + fieldName + "|" + raw));
        item.put("field", fieldName); item.put("targetName", targetName); item.put("targetId", matches.size() == 1 ? matches.getFirst().id() : "");
        item.put("multiplicity", multiplicity); item.put("kind", kind);
        item.put("status", matches.size() == 1 ? "INFERRED" : "UNRESOLVED");
        item.put("composition", "UNKNOWN");
        item.put("reason", matches.size() == 1 ? "字段类型可解析；生命周期不足以证明组合关系" : "字段类型在固定代次中不唯一或缺失；保留未知");
        item.put("source", source(file, null, line(field), endLine(field)));
        result.add(item);
        return result;
    }

    private Map<String, Object> source(Map<String, Object> file, Map<String, Object> symbol, int start, int end) {
        var item = new LinkedHashMap<String, Object>();
        item.put("fileId", file.get("id")); item.put("filePath", file.get("path")); item.put("fileHash", file.get("sha256"));
        item.put("snapshotId", ""); item.put("startLine", start); item.put("endLine", end); item.put("symbolId", symbol == null ? "" : symbol.get("id"));
        return item;
    }

    private Map<String, Object> symbol(SymbolInfo type) {
        return Map.of("id", type.id());
    }

    private int line(com.github.javaparser.ast.Node node) { return node.getRange().map(range -> range.begin.line).orElse(1); }
    private int endLine(com.github.javaparser.ast.Node node) { return node.getRange().map(range -> range.end.line).orElse(line(node)); }
    private String visibility(NodeWithAccessModifiers<?> node) {
        if (node.isPublic()) return "PUBLIC"; if (node.isProtected()) return "PROTECTED"; if (node.isPrivate()) return "PRIVATE"; return "PACKAGE";
    }
}
