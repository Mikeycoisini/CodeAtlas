package dev.codeatlas.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureAnalyzerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void extractsSystemApplicationModulesComponentsAndEvidence(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>demo</artifactId>
                <dependencies><dependency><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>
                </project>
                """);
        Files.writeString(root.resolve("src/main/resources/application.properties"), """
                spring.datasource.url=jdbc:mysql://db.example.com:3306/demo?user=secret&password=secret
                external.api.url=https://api.example.com/v1
                """);
        var files = new ArrayList<Map<String, Object>>();
        files.add(file("f-pom", "pom.xml", "pom"));
        files.add(file("f-config", "src/main/resources/application.properties", "config"));
        files.add(file("f-controller", "src/main/java/demo/DemoController.java", "controller"));
        files.add(file("f-service", "src/main/java/demo/DemoService.java", "service"));
        files.add(file("f-repo", "src/main/java/demo/DemoRepository.java", "repo"));
        var symbols = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        symbols.add(type("t-controller", "f-controller", "DemoController", "demo.DemoController", "RestController"));
        symbols.add(type("t-service", "f-service", "DemoService", "demo.DemoService", "Service"));
        symbols.add(type("t-repo", "f-repo", "DemoRepository", "demo.DemoRepository", "Repository"));
        symbols.add(method("m-controller", "f-controller", "process", "demo.DemoController.process"));
        symbols.add(method("m-service", "f-service", "load", "demo.DemoService.load"));
        symbols.add(method("m-repo", "f-repo", "find", "demo.DemoRepository.find"));
        var relations = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        relations.add(relation("r1", "m-controller", "m-service", "CALL", "CONFIRMED", "f-controller"));
        relations.add(relation("r2", "m-service", "m-repo", "CALL", "CONFIRMED", "f-service"));
        var document = new ArchitectureAnalyzer(new ArchitectureAnalyzer.Input("p", "Demo", "s", "g", root, files, symbols, relations)).analyze();

        assertEquals("codeatlas-architecture-v1", document.schemaVersion());
        assertTrue(document.nodes().stream().anyMatch(node -> node.kind().equals("SYSTEM")));
        assertTrue(document.nodes().stream().anyMatch(node -> node.kind().equals("APPLICATION")));
        assertTrue(document.nodes().stream().anyMatch(node -> node.kind().equals("MODULE")));
        assertEquals(3, document.nodes().stream().filter(node -> node.kind().equals("COMPONENT")).count());
        assertTrue(document.nodes().stream().anyMatch(node -> node.kind().equals("DATASTORE") && node.name().equals("MySQL")));
        assertTrue(document.nodes().stream().anyMatch(node -> node.kind().equals("EXTERNAL_SERVICE") && node.name().equals("api.example.com")));
        assertTrue(document.edges().stream().anyMatch(edge -> edge.kind().equals("DEPENDS_ON")));
        assertTrue(document.edges().stream().anyMatch(edge -> edge.kind().equals("READS_WRITES")));
        assertTrue(document.evidence().stream().anyMatch(item -> item.rule().equals("CONFIG_DATASOURCE")));
        assertTrue(document.evidence().stream().noneMatch(item -> item.filePath().contains("password")));
        assertFalse(document.coverage().truncated());
    }

    @Test
    void rejectsExternalEntitiesInBuildFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><artifactId>&xxe;</artifactId></project>
                """);
        var files = List.of(file("f-pom", "pom.xml", "pom"));
        var document = new ArchitectureAnalyzer(new ArchitectureAnalyzer.Input("p", "X", "s", "g", root,
                files, List.of(), List.of())).analyze();
        assertTrue(document.diagnostics().stream().anyMatch(item -> item.code().equals("ARCH_XML_ERROR")));
        assertTrue(document.nodes().stream().allMatch(node -> !node.name().contains("root:")));
    }

    private Map<String, Object> file(String id, String path, String marker) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", id); map.put("path", path); map.put("sha256", marker); map.put("byteSize", 100L);
        return map;
    }

    private com.fasterxml.jackson.databind.JsonNode type(String id, String fileId, String name, String qualified, String annotation) {
        ObjectNode node = json.createObjectNode();
        node.put("id", id); node.put("fileId", fileId); node.put("kind", "CLASS");
        node.put("name", name); node.put("qualifiedName", qualified); node.put("signature", name);
        node.put("startLine", 1); node.put("endLine", 20);
        node.putArray("annotations").add(annotation);
        return node;
    }

    private com.fasterxml.jackson.databind.JsonNode method(String id, String fileId, String name, String qualified) {
        ObjectNode node = json.createObjectNode();
        node.put("id", id); node.put("fileId", fileId); node.put("kind", "METHOD");
        node.put("name", name); node.put("qualifiedName", qualified); node.put("signature", name + "()");
        node.put("startLine", 5); node.put("endLine", 10);
        return node;
    }

    private com.fasterxml.jackson.databind.JsonNode relation(String id, String source, String target, String kind, String status, String fileId) {
        ObjectNode node = json.createObjectNode();
        node.put("id", id); node.put("sourceId", source); node.put("targetId", target);
        node.put("kind", kind); node.put("status", status); node.put("fileId", fileId);
        node.put("startLine", 6); node.put("endLine", 6);
        return node;
    }
}
