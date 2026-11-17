package dev.codeatlas.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codeatlas.analysis.JavaAnalyzer;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.codeatlas.architecture.ArchitectureModels.*;

/**
 * Bounded, read-only architecture extraction from a fixed snapshot.
 * The analyzer never executes project scripts and never reads values that look like credentials.
 */
public final class ArchitectureAnalyzer {
    private static final int MAX_CONFIG_BYTES = 512_000;
    private static final int MAX_MODULES = 20;
    private static final int MAX_COMPONENTS = 120;
    private static final int MAX_NODES = 220;
    private static final int MAX_EDGES = 600;
    private static final int MAX_EVIDENCE = 800;
    private static final Pattern JAVA_PACKAGE = Pattern.compile("^[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*$");
    private static final Pattern JDBC = Pattern.compile("jdbc:(mysql|mariadb|postgresql|h2|oracle|sqlserver):[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONGO = Pattern.compile("mongodb(?:\\+srv)?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern REDIS = Pattern.compile("rediss?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");

    public record Input(String projectId, String projectName, String snapshotId, String generationId, Path root,
                        List<Map<String, Object>> files, List<JsonNode> symbols, List<JsonNode> relations) {}

    private record FileInfo(String id, String path, String sha256, long bytes) {}
    private record TypeInfo(String id, String fileId, String path, String name, String qualifiedName,
                            String kind, List<String> annotations, int startLine, int endLine) {}
    private record ModuleInfo(String id, String name, String pathPrefix, String packagePrefix, String status,
                              String evidenceId) {}
    private record ConfigLine(FileInfo file, int line, String text) {}
    private record PomInfo(List<String> modules, String artifactId, Set<String> technologies) {}
    private record Role(String role, String technology, boolean confirmed) {}

    private final Input input;
    private final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
    private final LinkedHashMap<String, Edge> edges = new LinkedHashMap<>();
    private final LinkedHashMap<String, Evidence> evidence = new LinkedHashMap<>();
    private final ArrayList<Diagnostic> diagnostics = new ArrayList<>();
    private final LinkedHashMap<String, FileInfo> filesByPath = new LinkedHashMap<>();
    private final LinkedHashMap<String, FileInfo> filesById = new LinkedHashMap<>();
    private final ArrayList<TypeInfo> types = new ArrayList<>();
    private final LinkedHashMap<String, TypeInfo> typeByQualified = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> symbolOwner = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> componentByType = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> componentRole = new LinkedHashMap<>();
    private final LinkedHashMap<String, ModuleInfo> modules = new LinkedHashMap<>();
    private boolean truncated;
    private int unclassifiedTypes;
    private String systemId;
    private String applicationId;

    public ArchitectureAnalyzer(Input input) { this.input = input; }

    public Document analyze() {
        for (var file : input.files()) {
            var info = new FileInfo(text(file, "id"), text(file, "path"), text(file, "sha256"),
                    number(file, "byteSize"));
            filesByPath.put(info.path(), info);
            filesById.put(info.id(), info);
        }
        for (var symbol : input.symbols()) {
            var kind = text(symbol, "kind");
            if (!kind.equals("CLASS") && !kind.equals("INTERFACE")) continue;
            var item = new TypeInfo(text(symbol, "id"), text(symbol, "fileId"), path(text(symbol, "fileId")),
                    text(symbol, "name"), text(symbol, "qualifiedName"), kind, strings(symbol, "annotations"),
                    integer(symbol, "startLine"), integer(symbol, "endLine"));
            types.add(item);
            typeByQualified.put(item.qualifiedName(), item);
        }
        ownSymbols();
        var build = identifyBuild();
        var application = identifyApplication(build);
        identifyModules(build);
        identifyComponents();
        identifyConfigComponents();
        identifyDependencies();
        identifyContains();
        var groups = groups();
        var coverage = new Coverage(input.files().size(),
                (int) input.files().stream().filter(file -> text(file, "path").endsWith(".java")).count(),
                (int) input.files().stream().filter(file -> isConfig(text(file, "path"))).count(),
                modules.size(), componentByType.size(), externalCount, dataStoreCount, unclassifiedTypes, truncated);
        return new Document(SCHEMA_VERSION, input.projectId(), input.snapshotId(), input.generationId(),
                ANALYZER_VERSION, "", "", new ArrayList<>(nodes.values()), new ArrayList<>(edges.values()),
                groups, new ArrayList<>(evidence.values()), diagnostics, coverage, truncated);
    }

    private int externalCount;
    private int dataStoreCount;

    private void ownSymbols() {
        for (var symbol : input.symbols()) {
            var id = text(symbol, "id");
            var kind = text(symbol, "kind");
            var qualified = text(symbol, "qualifiedName");
            if (kind.equals("CLASS") || kind.equals("INTERFACE")) {
                symbolOwner.put(id, id);
            } else if (!qualified.isBlank() && qualified.contains(".")) {
                var owner = typeByQualified.get(qualified.substring(0, qualified.lastIndexOf('.')));
                if (owner != null) symbolOwner.put(id, owner.id());
            }
        }
    }

    private PomInfo identifyBuild() {
        var technologies = new LinkedHashSet<String>();
        var modules = new ArrayList<String>();
        var poms = input.files().stream().map(file -> new FileInfo(text(file, "id"), text(file, "path"),
                text(file, "sha256"), number(file, "byteSize")))
                .filter(file -> file.path().equals("pom.xml") || file.path().endsWith("/pom.xml")).limit(30).toList();
        boolean gradle = false;
        for (var file : poms) {
            var text = read(file);
            if (text == null) continue;
            var pom = parsePom(file, text);
            if (file.path().equals("pom.xml")) modules.addAll(pom.modules());
            technologies.addAll(pom.technologies());
        }
        for (var file : input.files().stream().map(item -> new FileInfo(text(item, "id"), text(item, "path"),
                text(item, "sha256"), number(item, "byteSize"))).toList()) {
            if (!(file.path().equals("build.gradle") || file.path().equals("build.gradle.kts")
                    || file.path().equals("settings.gradle") || file.path().equals("settings.gradle.kts"))) continue;
            gradle = true;
            var text = read(file);
            if (text == null) continue;
            if (text.contains("org.springframework.boot")) technologies.add("Spring Boot");
            var matcher = Pattern.compile("include\\s*\\(?\\s*['\"]([^'\"]+)").matcher(text);
            while (matcher.find()) modules.add(matcher.group(1));
        }
        if (poms.isEmpty() && !gradle) diagnostics.add(new Diagnostic("ARCH_NO_BUILD_FILE",
                "未发现 pom.xml 或 Gradle 构建文件；应用和模块使用源码结构推断。", null, null, 0));
        if (technologies.isEmpty()) technologies.add("Java");
        return new PomInfo(modules.stream().distinct().limit(30).toList(), null, technologies);
    }

    private PomInfo parsePom(FileInfo file, String text) {
        var modules = new ArrayList<String>();
        var technologies = new LinkedHashSet<String>();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            var root = document.getDocumentElement();
            for (var item : directChildren(root, "modules")) {
                for (var module : directChildren(item, "module")) modules.add(module.getTextContent().trim());
            }
            if (text.contains("spring-boot")) technologies.add("Spring Boot");
            if (text.contains("spring-boot-starter-web") || text.contains("spring-webmvc")) technologies.add("Spring MVC");
            if (text.contains("spring-data-jpa") || text.contains("hibernate-core")) technologies.add("JPA");
            if (text.contains("spring-boot-starter-jdbc") || text.contains("spring-jdbc")) technologies.add("JDBC");
            if (text.contains("spring-data-mongodb")) technologies.add("MongoDB");
            var artifact = directChildren(root, "artifactId");
            var artifactId = artifact.isEmpty() ? null : artifact.getFirst().getTextContent().trim();
            return new PomInfo(modules, artifactId, technologies);
        } catch (Exception error) {
            diagnostics.add(new Diagnostic("ARCH_XML_ERROR", "构建文件无法安全解析。", file.id(), file.path(), 1));
            return new PomInfo(List.of(), null, technologies);
        }
    }

    private String identifyApplication(PomInfo build) {
        systemId = id("system", input.projectId());
        var systemTech = build.technologies().isEmpty() ? "Java" : String.join(" / ", build.technologies());
        addNode(new Node(systemId, "SYSTEM", input.projectName(), "固定快照定义的软件系统边界",
                systemTech, null, "CONFIRMED", List.of(), 0));
        var boot = types.stream().filter(type -> type.annotations().stream().anyMatch(name -> name.equalsIgnoreCase("SpringBootApplication"))).findFirst();
        var main = input.symbols().stream().filter(symbol -> text(symbol, "kind").equals("METHOD")
                && text(symbol, "name").equals("main")).findFirst();
        applicationId = id("application", input.projectId() + "|" + input.snapshotId());
        var status = boot.isPresent() || main.isPresent() ? "CONFIRMED" : "INFERRED";
        var responsibility = boot.isPresent() ? "Spring Boot 应用入口" : main.isPresent() ? "Java 应用入口" : "按源码结构推断的应用边界";
        addNode(new Node(applicationId, "APPLICATION", input.projectName(), responsibility,
                build.technologies().isEmpty() ? "Java" : String.join(" / ", build.technologies()),
                systemId, status, evidenceForType(boot.orElse(null)), 0));
        addEdge(systemId, applicationId, "CONTAINS", "CONFIRMED", "系统包含应用；由固定快照边界推导", List.of());
        return applicationId;
    }

    private void identifyModules(PomInfo build) {
        var maven = input.files().stream().map(file -> new FileInfo(text(file, "id"), text(file, "path"),
                text(file, "sha256"), number(file, "byteSize")))
                .filter(file -> file.path().endsWith("/pom.xml")).limit(30).toList();
        if (!maven.isEmpty()) {
            for (var file : maven) {
                var text = read(file);
                var name = file.path().contains("/") ? file.path().substring(file.path().lastIndexOf('/') + 1) : file.path();
                var dir = file.path().contains("/") ? file.path().substring(0, file.path().lastIndexOf('/')) : "";
                if (text != null) {
                    var pom = parsePom(file, text);
                    if (pom.artifactId() != null && !pom.artifactId().isBlank()) name = pom.artifactId();
                }
                addModule(name, dir.isEmpty() ? "" : dir + "/", null, "CONFIRMED", file);
            }
        }
        if (modules.isEmpty()) {
            var packages = types.stream().map(type -> packageName(type.qualifiedName(), type.name())).filter(name -> name != null && !name.isBlank()).distinct().toList();
            var common = commonPrefix(packages);
            var byName = new LinkedHashMap<String, ModuleInfo>();
            for (var type : types) {
                var pkg = packageName(type.qualifiedName(), type.name());
                var name = moduleName(pkg, common);
                var prefix = packageNameForModule(pkg, common);
                if (!byName.containsKey(name)) {
                    var module = addModule(name, null, prefix, "INFERRED", filesById.get(type.fileId()));
                    if (module != null) byName.put(name, module);
                }
            }
            if (byName.isEmpty()) addModule("应用核心", null, null, "INFERRED", null);
        }
        for (var type : types) {
            var module = moduleFor(type);
            if (module != null) typeModule.put(type.id(), module.id());
        }
    }

    private final LinkedHashMap<String, String> typeModule = new LinkedHashMap<>();

    private ModuleInfo addModule(String name, String pathPrefix, String packagePrefix, String status, FileInfo file) {
        if (modules.size() >= MAX_MODULES) {
            truncated = true;
            diagnosticOnce("ARCH_MODULE_LIMIT", "模块数量超过展示上限，其余模块合并到“其他模块”。");
            name = "其他模块";
            pathPrefix = null;
            packagePrefix = null;
        }
        var id = id("module", input.projectId() + "|" + name);
        if (modules.containsKey(id)) return modules.get(id);
        var evidence = file == null ? List.<String>of() : List.of(addEvidence(file, "BUILD_MODULE", null, 1, 1));
        var module = new ModuleInfo(id, name, pathPrefix, packagePrefix, status, evidence.isEmpty() ? null : evidence.getFirst());
        modules.put(id, module);
        addNode(new Node(id, "MODULE", name, pathPrefix != null ? "构建模块边界" : "按 Java 包结构分组的业务模块",
                pathPrefix != null ? "Maven/Gradle module" : "Java package", applicationId, status, evidence, 0));
        addEdge(applicationId, id, "CONTAINS", status, "应用包含模块；由构建文件或包结构推导", evidence);
        return module;
    }

    private ModuleInfo moduleFor(TypeInfo type) {
        ModuleInfo best = null;
        for (var module : modules.values()) {
            if (module.pathPrefix() != null && type.path().startsWith(module.pathPrefix())
                    && (best == null || module.pathPrefix().length() > (best.pathPrefix() == null ? 0 : best.pathPrefix().length()))) best = module;
        }
        if (best != null) return best;
        var pkg = packageName(type.qualifiedName(), type.name());
        for (var module : modules.values()) {
            if (module.packagePrefix() != null && pkg != null
                    && (pkg.equals(module.packagePrefix()) || pkg.startsWith(module.packagePrefix() + "."))) {
                if (best == null || module.packagePrefix().length() > (best.packagePrefix() == null ? 0 : best.packagePrefix().length())) best = module;
            }
        }
        return best;
    }

    private void identifyComponents() {
        for (var type : types) {
            var role = role(type);
            if (role == null) { unclassifiedTypes++; continue; }
            if (componentByType.size() >= MAX_COMPONENTS) {
                truncated = true;
                diagnosticOnce("ARCH_COMPONENT_LIMIT", "组件数量超过展示上限，其余类型保留在模块成员计数中。");
                break;
            }
            var module = typeModule.get(type.id());
            var id = id("component", type.id());
            var evidence = evidenceForType(type);
            var memberCount = (int) input.symbols().stream().filter(symbol -> type.id().equals(symbolOwner.get(text(symbol, "id")))
                    && Set.of("METHOD", "CONSTRUCTOR", "FIELD").contains(text(symbol, "kind"))).count();
            addNode(new Node(id, "COMPONENT", type.name(), role.role() + (role.confirmed() ? "（注解声明）" : "（命名推断）"),
                    role.technology(), module == null ? applicationId : module, role.confirmed() ? "CONFIRMED" : "INFERRED", evidence, memberCount));
            addEdge(module == null ? applicationId : module, id, "CONTAINS",
                    role.confirmed() ? "CONFIRMED" : "INFERRED", "模块包含组件；由类型声明或命名推导", evidence);
            componentByType.put(type.id(), id);
            componentRole.put(id, role.role());
            if (type.annotations().stream().anyMatch(name -> name.equalsIgnoreCase("FeignClient"))) {
                var externalName = type.name().replaceAll("Client$", "");
                var externalId = id("external", "feign|" + externalName);
                if (!nodes.containsKey(externalId)) {
                    addNode(new Node(externalId, "EXTERNAL_SERVICE", externalName, "声明式远程客户端",
                            "HTTP client", applicationId, "INFERRED", evidence, 0));
                    externalCount++;
                }
                addEdge(id, externalId, "DEPENDS_ON", "INFERRED", "Feign 客户端声明；实际目标与运行时调用未验证", evidence);
            }
        }
    }

    private Role role(TypeInfo type) {
        var annotations = type.annotations().stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        boolean has = annotations.stream().anyMatch(value -> value.endsWith("restcontroller") || value.endsWith("controller"));
        if (has || type.name().endsWith("Controller")) return new Role("Web Controller", "Spring MVC", has);
        has = annotations.stream().anyMatch(value -> value.endsWith("service"));
        if (has || type.name().endsWith("Service")) return new Role("Domain Service", "Spring", has);
        has = annotations.stream().anyMatch(value -> value.endsWith("repository"));
        if (has || type.name().endsWith("Repository")) return new Role("Repository", "Spring Data", has);
        has = annotations.stream().anyMatch(value -> value.endsWith("entity") || value.endsWith("table"));
        if (has || type.name().endsWith("Entity")) return new Role("Entity", "JPA", has);
        has = annotations.stream().anyMatch(value -> value.endsWith("configuration"));
        if (has || type.name().endsWith("Config") || type.name().endsWith("Configuration")) return new Role("Configuration", "Spring", has);
        has = annotations.stream().anyMatch(value -> value.endsWith("feignclient"));
        if (has) return new Role("Remote Client", "HTTP client", true);
        if (type.annotations().stream().anyMatch(value -> value.equalsIgnoreCase("SpringBootApplication"))) return null;
        return null;
    }

    private void identifyConfigComponents() {
        var configs = input.files().stream().map(file -> new FileInfo(text(file, "id"), text(file, "path"),
                text(file, "sha256"), number(file, "byteSize")))
                .filter(file -> isConfig(file.path())).limit(40).toList();
        var dataStores = new LinkedHashMap<String, String>();
        var externals = new LinkedHashMap<String, String>();
        for (var file : configs) {
            var text = read(file);
            if (text == null) continue;
            var lines = text.split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                var line = lines[index].strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                var lineNumber = index + 1;
                var jdbc = JDBC.matcher(line);
                if (jdbc.find()) {
                    var raw = jdbc.group();
                    var technology = switch (jdbc.group(1).toLowerCase(Locale.ROOT)) {
                        case "mysql", "mariadb" -> "MySQL";
                        case "postgresql" -> "PostgreSQL";
                        case "h2" -> "H2";
                        case "oracle" -> "Oracle";
                        case "sqlserver" -> "SQL Server";
                        default -> "Database";
                    };
                    var key = technology + "|" + sanitizeJdbc(raw);
                    var nodeId = dataStores.computeIfAbsent(key, ignored -> {
                        var id = id("datastore", key);
                        var evidenceId = addEvidence(file, "CONFIG_DATASOURCE", null, lineNumber, lineNumber);
                        addNode(new Node(id, "DATASTORE", technology, "配置声明的数据存储（运行连接未验证）",
                                technology, applicationId, PLACEHOLDER.matcher(line).find() ? "INFERRED" : "CONFIRMED",
                                List.of(evidenceId), 0));
                        addEdge(applicationId, id, "READS_WRITES",
                                PLACEHOLDER.matcher(line).find() ? "INFERRED" : "CONFIRMED",
                                "配置文件声明数据源；未验证实际连接", List.of(evidenceId));
                        dataStoreCount++;
                        return id;
                    });
                    continue;
                }
                var mongo = MONGO.matcher(line);
                if (mongo.find()) {
                    addDataStore("MongoDB", file, lineNumber, line, dataStores);
                    continue;
                }
                var redis = REDIS.matcher(line);
                if (redis.find()) {
                    addDataStore("Redis", file, lineNumber, line, dataStores);
                    continue;
                }
                var http = HTTP.matcher(line);
                if (http.find() && line.toLowerCase(Locale.ROOT).matches(".*(url|uri|endpoint|host|base-url).*")) {
                    var raw = http.group();
                    var host = host(raw);
                    if (host == null || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) continue;
                    var key = "external|" + host;
                    externals.computeIfAbsent(key, ignored -> {
                        var id = id("external", key);
                        var evidenceId = addEvidence(file, "CONFIG_EXTERNAL_URL", null, lineNumber, lineNumber);
                        addNode(new Node(id, "EXTERNAL_SERVICE", host, "配置声明的外部服务（调用未验证）",
                                "HTTP", applicationId, "CONFIRMED", List.of(evidenceId), 0));
                        addEdge(applicationId, id, "COMMUNICATES_WITH", "CONFIRMED",
                                "配置文件声明外部 URL；未验证实际调用", List.of(evidenceId));
                        externalCount++;
                        return id;
                    });
                }
            }
        }
    }

    private void addDataStore(String technology, FileInfo file, int lineNumber, String line, Map<String, String> dataStores) {
        var key = technology + "|" + file.path();
        dataStores.computeIfAbsent(key, ignored -> {
            var id = id("datastore", key);
            var evidenceId = addEvidence(file, "CONFIG_DATASTORE", null, lineNumber, lineNumber);
            addNode(new Node(id, "DATASTORE", technology, "配置声明的数据存储（运行连接未验证）",
                    technology, applicationId, PLACEHOLDER.matcher(line).find() ? "INFERRED" : "CONFIRMED",
                    List.of(evidenceId), 0));
            addEdge(applicationId, id, "READS_WRITES", PLACEHOLDER.matcher(line).find() ? "INFERRED" : "CONFIRMED",
                    "配置文件声明数据源；未验证实际连接", List.of(evidenceId));
            dataStoreCount++;
            return id;
        });
    }

    private void identifyDependencies() {
        var dataStores = nodes.values().stream().filter(node -> node.kind().equals("DATASTORE")).toList();
        for (var relation : input.relations()) {
            var kind = text(relation, "kind");
            if (!Set.of("CALL", "EXTENDS", "IMPLEMENTS").contains(kind)) continue;
            var sourceOwner = symbolOwner.get(text(relation, "sourceId"));
            var targetOwner = symbolOwner.get(text(relation, "targetId"));
            var source = sourceOwner == null ? null : componentByType.get(sourceOwner);
            var target = targetOwner == null ? null : componentByType.get(targetOwner);
            if (source == null || target == null || source.equals(target)) continue;
            var file = filesById.get(text(relation, "fileId"));
            var evidenceId = file == null ? null : addEvidence(file, "RELATION_" + kind, text(relation, "sourceId"),
                    integer(relation, "startLine"), integer(relation, "endLine"));
            addEdge(source, target, "DEPENDS_ON", text(relation, "status"),
                    kind + " · 由固定代次索引关系聚合", evidenceId == null ? List.of() : List.of(evidenceId));
        }
        for (var entry : componentRole.entrySet()) {
            if (!entry.getValue().equals("Repository") || dataStores.isEmpty()) continue;
            for (var dataStore : dataStores) {
                addEdge(entry.getKey(), dataStore.id(), "READS_WRITES", "INFERRED",
                        "仓库组件与配置数据源的静态关联；运行时连接未验证", dataStore.evidenceIds());
            }
        }
    }

    private void identifyContains() {
        for (var module : modules.values()) {
            if (module.evidenceId() != null) {
                addEdge(applicationId, module.id(), "CONTAINS", module.status(),
                        "应用包含模块；由构建文件或包结构推导", List.of(module.evidenceId()));
            }
        }
    }

    private List<Group> groups() {
        var children = new LinkedHashMap<String, List<String>>();
        for (var node : nodes.values()) {
            if (node.parentId() != null) children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node.id());
        }
        var result = new ArrayList<Group>();
        for (var node : nodes.values()) {
            var members = children.get(node.id());
            if (members == null || members.isEmpty()) continue;
            result.add(new Group(node.id(), node.kind(), node.name(), node.parentId(), members));
        }
        return result;
    }

    private List<String> evidenceForType(TypeInfo type) {
        if (type == null) return List.of();
        var file = filesById.get(type.fileId());
        if (file == null) return List.of();
        return List.of(addEvidence(file, "TYPE_DECLARATION", type.id(), type.startLine(), type.endLine()));
    }

    private String addEvidence(FileInfo file, String rule, String symbolId, int start, int end) {
        var id = id("evidence", file.id() + "|" + rule + "|" + symbolId + "|" + start + "|" + end);
        if (!evidence.containsKey(id) && evidence.size() < MAX_EVIDENCE) {
            evidence.put(id, new Evidence(id, file.id(), file.path(), symbolId, Math.max(1, start), Math.max(1, end),
                    file.sha256(), rule));
        }
        return id;
    }

    private void addNode(Node node) {
        if (nodes.size() >= MAX_NODES) {
            truncated = true;
            diagnosticOnce("ARCH_NODE_LIMIT", "架构节点超过展示上限，未展示部分保留在分析覆盖计数中。");
            return;
        }
        nodes.putIfAbsent(node.id(), node);
    }

    private void addEdge(String source, String target, String kind, String status, String reason, List<String> evidenceIds) {
        if (source == null || target == null || source.equals(target) || !nodes.containsKey(source) || !nodes.containsKey(target)) return;
        var id = id("edge", source + "|" + target + "|" + kind);
        var existing = edges.get(id);
        if (existing != null) {
            var merged = new LinkedHashSet<>(existing.evidenceIds());
            merged.addAll(evidenceIds);
            edges.put(id, new Edge(id, source, target, kind, status, existing.reason(), merged.stream().limit(20).toList()));
            return;
        }
        if (edges.size() >= MAX_EDGES) {
            truncated = true;
            diagnosticOnce("ARCH_EDGE_LIMIT", "架构关系超过展示上限，未展示关系保留在覆盖计数中。");
            return;
        }
        edges.put(id, new Edge(id, source, target, kind, status, reason, evidenceIds.stream().limit(20).toList()));
    }

    private void diagnosticOnce(String code, String message) {
        if (diagnostics.stream().noneMatch(item -> item.code().equals(code))) {
            diagnostics.add(new Diagnostic(code, message, null, null, 0));
        }
    }

    private String read(FileInfo file) {
        if (file.bytes() > MAX_CONFIG_BYTES) {
            diagnostics.add(new Diagnostic("ARCH_CONFIG_TOO_LARGE", "配置文件超过解析上限，已跳过。", file.id(), file.path(), 1));
            return null;
        }
        try {
            var path = input.root().resolve(file.path()).normalize();
            if (!path.startsWith(input.root())) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception error) {
            diagnostics.add(new Diagnostic("ARCH_CONFIG_UNREADABLE", "配置文件无法读取。", file.id(), file.path(), 1));
            return null;
        }
    }

    private String id(String kind, String key) {
        return JavaAnalyzer.id(input.snapshotId() + "|architecture|" + kind + "|" + key);
    }

    private String path(String fileId) {
        var file = filesById.get(fileId);
        return file == null ? "" : file.path();
    }

    private static String packageName(String qualifiedName, String name) {
        var suffix = "." + name;
        if (qualifiedName == null || qualifiedName.equals(name) || !qualifiedName.endsWith(suffix)) return "";
        var value = qualifiedName.substring(0, qualifiedName.length() - suffix.length());
        var dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(0, dot);
    }

    private static String commonPrefix(List<String> packages) {
        if (packages.isEmpty()) return "";
        var prefix = packages.getFirst();
        for (var value : packages) {
            while (!value.equals(prefix) && !value.startsWith(prefix + ".") && !prefix.isEmpty()) {
                var dot = prefix.lastIndexOf('.');
                prefix = dot < 0 ? "" : prefix.substring(0, dot);
            }
        }
        return prefix;
    }

    private static String moduleName(String pkg, String common) {
        if (pkg == null || pkg.isBlank()) return "应用核心";
        var rest = common.isEmpty() ? pkg : pkg.startsWith(common + ".") ? pkg.substring(common.length() + 1) : pkg;
        var first = rest.split("\\.")[0];
        return first.isBlank() ? "应用核心" : first;
    }

    private static String packageNameForModule(String pkg, String common) {
        if (pkg == null || pkg.isBlank()) return null;
        var rest = common.isEmpty() ? pkg : pkg.startsWith(common + ".") ? pkg.substring(common.length() + 1) : pkg;
        var first = rest.split("\\.")[0];
        if (first.isBlank()) return null;
        return common.isEmpty() ? first : common + "." + first;
    }

    private static String sanitizeJdbc(String raw) {
        var value = raw.replaceAll("(?i)(user|password|pwd|secret|token)=[^&;]*", "$1=REDACTED");
        return value.replaceAll("\\?.*$", "");
    }

    private static String host(String raw) {
        try {
            var uri = java.net.URI.create(raw);
            return uri.getHost();
        } catch (Exception error) { return null; }
    }

    private static boolean isConfig(String path) {
        var lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith("pom.xml") || lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")
                || lower.contains("/application") || lower.startsWith("application")
                || lower.contains("/bootstrap") || lower.startsWith("bootstrap")
                || lower.endsWith("package.json") || lower.endsWith("docker-compose.yml")
                || lower.endsWith("docker-compose.yaml");
    }

    private static List<Element> directChildren(Element parent, String name) {
        var result = new ArrayList<Element>();
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            var node = children.item(index);
            if (node instanceof Element element && (name.equals(element.getTagName()) || name.equals(element.getLocalName()))) result.add(element);
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() || value.isNumber() ? value.asText() : "";
    }

    private static int integer(JsonNode node, String field) {
        return node.path(field).asInt(0);
    }

    private static long number(Map<String, Object> map, String field) {
        var value = map.get(field);
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String text(Map<String, Object> map, String field) {
        var value = map.get(field);
        return value == null ? "" : value.toString();
    }

    private static List<String> strings(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isArray()) return List.of();
        var result = new ArrayList<String>();
        value.forEach(item -> result.add(item.asText()));
        return result;
    }
}
