package dev.codeatlas.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

import static dev.codeatlas.architecture.ArchitectureModels.*;

/** Standalone, dependency-free HTML/SVG renderer for a frozen ArchitectureDocument. */
public final class ArchitectureHtmlRenderer {
    private final ObjectMapper json = new ObjectMapper();

    public String render(Document document, String theme, String scope) {
        var nodes = document.nodes().stream().filter(node -> include(node, scope)).toList();
        var byId = new LinkedHashMap<String, Node>();
        nodes.forEach(node -> byId.put(node.id(), node));
        var modules = nodes.stream().filter(node -> node.kind().equals("MODULE")).toList();
        var components = nodes.stream().filter(node -> node.kind().equals("COMPONENT")).toList();
        var leaves = nodes.stream().filter(node -> node.kind().equals("EXTERNAL_SERVICE") || node.kind().equals("DATASTORE")).toList();
        var positions = new LinkedHashMap<String, Position>();
        var groupBoxes = new ArrayList<GroupBox>();
        int cursor = 40;
        for (var module : modules) {
            var children = components.stream().filter(node -> module.id().equals(node.parentId())).toList();
            int height = Math.max(116, children.size() * 92 + 44);
            positions.put(module.id(), new Position(600, cursor + 22, 200, 70));
            groupBoxes.add(new GroupBox(module, 560, cursor - 8, 650, height));
            for (int index = 0; index < children.size(); index++) {
                positions.put(children.get(index).id(), new Position(900, cursor + 12 + index * 92, 220, 78));
            }
            cursor += height + 26;
        }
        var unassigned = components.stream().filter(node -> !positions.containsKey(node.id())).toList();
        for (var component : unassigned) {
            positions.put(component.id(), new Position(900, cursor, 220, 78));
            cursor += 92;
        }
        for (var leaf : leaves) {
            positions.put(leaf.id(), new Position(900, cursor, 220, 78));
            cursor += 92;
        }
        int contentHeight = Math.max(420, cursor + 40);
        var application = nodes.stream().filter(node -> node.kind().equals("APPLICATION")).findFirst().orElse(null);
        var system = nodes.stream().filter(node -> node.kind().equals("SYSTEM")).findFirst().orElse(null);
        int center = Math.max(90, contentHeight / 2 - 35);
        if (application != null) positions.put(application.id(), new Position(300, center, 220, 78));
        if (system != null) positions.put(system.id(), new Position(40, center, 210, 78));
        var edges = document.edges().stream().filter(edge -> byId.containsKey(edge.source()) && byId.containsKey(edge.target())).toList();
        var nodeData = new LinkedHashMap<String, Object>();
        for (var node : nodes) {
            var item = new LinkedHashMap<String, Object>();
            item.put("title", node.name());
            item.put("meta", node.kind() + " · " + node.status());
            item.put("responsibility", node.responsibility());
            item.put("evidence", evidence(document, node.evidenceIds()));
            nodeData.put(node.id(), item);
        }
        var edgeData = new LinkedHashMap<String, Object>();
        for (var edge : edges) {
            var item = new LinkedHashMap<String, Object>();
            item.put("title", byId.get(edge.source()).name() + " → " + byId.get(edge.target()).name());
            item.put("meta", edge.kind() + " · " + edge.status());
            item.put("reason", edge.reason());
            item.put("evidence", evidence(document, edge.evidenceIds()));
            edgeData.put(edge.id(), item);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("nodes", nodeData); data.put("edges", edgeData);
        var html = new StringBuilder();
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<title>CodeAtlas 架构总览</title><style>").append(css(theme)).append("</style></head><body class=\"");
        html.append("dark".equals(theme) ? "theme-dark" : "theme-light").append("\">");
        html.append("<header><div><p class=\"eyebrow\">CodeAtlas · ARCHITECTURE</p><h1>系统架构总览</h1><p class=\"sub\">固定快照 ");
        html.append(esc(document.snapshotId())).append(" · 投影 ").append(esc(shortId(document.projectionId()))).append("</p></div>");
        html.append("<div class=\"header-actions\"><a class=\"local-link\" href=\"http://127.0.0.1:8080/?view=explore&snapshotId=");
        html.append(esc(document.snapshotId())).append("&generationId=").append(esc(document.generationId()));
        html.append("\">在本机 CodeAtlas 打开源码</a><span class=\"badge\">离线证据摘要</span></div></header>");
        html.append("<main class=\"layout\"><section class=\"canvas\" style=\"width:1240px;height:").append(contentHeight).append("px\">");
        for (var box : groupBoxes) {
            html.append("<div class=\"group\" style=\"left:").append(box.x).append("px;top:").append(box.y).append("px;width:")
                    .append(box.width).append("px;height:").append(box.height).append("px\"><span>").append(esc(box.module.name())).append("</span></div>");
        }
        html.append("<svg class=\"edges\" width=\"1240\" height=\"").append(contentHeight).append("\" aria-hidden=\"true\">");
        for (var edge : edges) {
            var source = positions.get(edge.source()); var target = positions.get(edge.target());
            if (source == null || target == null) continue;
            double sx = source.x + source.w, sy = source.y + source.h / 2.0;
            double tx = target.x, ty = target.y + target.h / 2.0;
            html.append("<path class=\"edge edge-").append(esc(edge.status().toLowerCase(Locale.ROOT))).append("\" data-edge=\"")
                    .append(esc(edge.id())).append("\" d=\"M ").append(fmt(sx)).append(" ").append(fmt(sy)).append(" C ")
                    .append(fmt(sx + 80)).append(" ").append(fmt(sy)).append(" ").append(fmt(tx - 80)).append(" ").append(fmt(ty))
                    .append(" ").append(fmt(tx)).append(" ").append(fmt(ty)).append("\"><title>")
                    .append(esc(byId.get(edge.source()).name() + " → " + byId.get(edge.target()).name() + " · " + edge.kind() + " · " + edge.status()))
                    .append("</title></path>");
        }
        html.append("</svg>");
        for (var node : nodes) {
            var pos = positions.get(node.id());
            if (pos == null) continue;
            html.append("<button class=\"node node-").append(esc(node.kind().toLowerCase(Locale.ROOT))).append("\" data-node=\"")
                    .append(esc(node.id())).append("\" style=\"left:").append(pos.x).append("px;top:").append(pos.y).append("px;width:")
                    .append(pos.w).append("px;height:").append(pos.h).append("px\"><strong>").append(esc(node.name()))
                    .append("</strong><small>").append(esc(node.kind())).append(" · ").append(esc(statusText(node.status())))
                    .append("</small><span>").append(esc(clip(node.responsibility(), 72))).append("</span></button>");
        }
        html.append("</section><aside id=\"evidence\"><h2>证据与范围</h2><p id=\"hint\">点击节点或关系查看固定快照证据。</p><div id=\"detail\"></div>");
        html.append("<section class=\"coverage\"><h3>分析覆盖</h3><p>文件 ").append(document.coverage().files()).append(" · Java ").append(document.coverage().javaFiles())
                .append(" · 配置 ").append(document.coverage().configFiles()).append(" · 模块 ").append(document.coverage().modules())
                .append(" · 组件 ").append(document.coverage().components()).append(" · 外部服务 ").append(document.coverage().externalServices())
                .append(" · 数据存储 ").append(document.coverage().dataStores()).append("</p>");
        if (document.truncated()) html.append("<p class=\"warning\">结果已截断；完整范围请回到本机应用查看。</p>");
        html.append("</section><details><summary>诊断 ").append(document.diagnostics().size()).append(" 条</summary><ul>");
        for (var diagnostic : document.diagnostics()) {
            html.append("<li><strong>").append(esc(diagnostic.code())).append("</strong> ").append(esc(diagnostic.message()));
            if (diagnostic.filePath() != null) html.append(" <small>").append(esc(diagnostic.filePath())).append("</small>");
            html.append("</li>");
        }
        html.append("</ul></details><p class=\"footer\">投影 ").append(esc(document.projectionId())).append("<br>摘要 ").append(esc(document.digest()))
                .append("<br>生成器 ").append(esc(document.analyzerVersion())).append(" · 离线文件不含令牌或私有绝对目录</p></aside></main>");
        html.append("<script>const DATA=").append(scriptJson(data)).append(";");
        html.append("(function(){const detail=document.getElementById('detail'),hint=document.getElementById('hint');");
        html.append("function esc(s){const d=document.createElement('div');d.textContent=s==null?'':String(s);return d.innerHTML;}");
        html.append("function show(kind,id){const item=(kind==='node'?DATA.nodes:DATA.edges)[id];if(!item)return;hint.hidden=true;");
        html.append("let rows=(item.evidence||[]).map(e=>'<li><strong>'+esc(e.path)+'</strong> · L'+e.start+'–'+e.end+'<br><small>'+esc(e.rule)+(e.symbol?' · '+esc(e.symbol):'')+'</small></li>');");
        html.append("detail.innerHTML='<h2>'+esc(item.title)+'</h2><p class=\"meta\">'+esc(item.meta)+'</p><p>'+esc(item.responsibility||item.reason||'')+'</p><h3>证据</h3><ul>'+(rows.join('')||'<li>无直接源码证据</li>')+'</ul>';}");
        html.append("document.addEventListener('click',e=>{const n=e.target.closest('[data-node]'),d=e.target.closest('[data-edge]');if(n){show('node',n.getAttribute('data-node'));}else if(d){show('edge',d.getAttribute('data-edge'));}});");
        html.append("})();</script></body></html>");
        return html.toString();
    }

    /** Standalone vector image; no scripts, external styles, fonts or network references. */
    public String renderSvg(Document document, String theme, String scope) {
        var nodes = document.nodes().stream().filter(node -> include(node, scope)).toList();
        var byId = new LinkedHashMap<String, Node>();
        nodes.forEach(node -> byId.put(node.id(), node));
        var positions = new LinkedHashMap<String, Position>();
        var columns = Map.of("SYSTEM", 20, "APPLICATION", 260, "MODULE", 500, "COMPONENT", 740,
                "EXTERNAL_SERVICE", 980, "DATASTORE", 980);
        var counts = new HashMap<String, Integer>();
        for (var node : nodes) {
            int column = columns.getOrDefault(node.kind(), 740);
            int index = counts.merge(node.kind(), 1, Integer::sum) - 1;
            positions.put(node.id(), new Position(column, 70 + index * 96, 200, 70));
        }
        int height = Math.max(420, 110 + nodes.stream().mapToInt(node -> positions.get(node.id()).y()).max().orElse(0));
        var edges = document.edges().stream().filter(edge -> byId.containsKey(edge.source()) && byId.containsKey(edge.target())).toList();
        boolean dark = "dark".equals(theme);
        var svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"").append(height)
                .append("\" viewBox=\"0 0 1200 ").append(height).append("\" role=\"img\" aria-labelledby=\"title desc\">");
        svg.append("<title id=\"title\">CodeAtlas 系统架构总览</title><desc id=\"desc\">")
                .append(esc(document.snapshotId())).append(" · ").append(esc(document.projectionId())).append(" · ").append(esc(document.digest()))
                .append("</desc><style>")
                .append("text{font-family:system-ui,-apple-system,'Segoe UI',sans-serif}")
                .append(".bg{fill:").append(dark ? "#0f1519" : "#f7f5ef").append("}")
                .append(".edge{fill:none;stroke-width:1.6;opacity:.75}.edge-confirmed{stroke:").append(dark ? "#56b4a8" : "#247b70")
                .append("}.edge-inferred{stroke:#d29a3a;stroke-dasharray:6 4}.edge-unresolved{stroke:").append(dark ? "#ff8a80" : "#b4433b")
                .append(";stroke-dasharray:3 4}.node{fill:").append(dark ? "#151f26" : "#fffdf8").append(";stroke:").append(dark ? "#2a3b45" : "#d9d7ce")
                .append(";stroke-width:1.2}.node-system,.node-application{stroke:").append(dark ? "#56b4a8" : "#247b70").append(";stroke-width:2}")
                .append(".name{fill:").append(dark ? "#e8f0f3" : "#1d2b31").append(";font-size:14px;font-weight:600}")
                .append(".meta{fill:").append(dark ? "#8fa3ad" : "#61727a").append(";font-size:11px}")
                .append("</style><rect class=\"bg\" width=\"1200\" height=\"").append(height).append("\"/>");
        for (var edge : edges) {
            var source = positions.get(edge.source()); var target = positions.get(edge.target());
            if (source == null || target == null) continue;
            double sx = source.x() + source.w(), sy = source.y() + source.h() / 2.0;
            double tx = target.x(), ty = target.y() + target.h() / 2.0;
            svg.append("<path class=\"edge edge-").append(esc(edge.status().toLowerCase(Locale.ROOT))).append("\" d=\"M ")
                    .append(fmt(sx)).append(" ").append(fmt(sy)).append(" C ").append(fmt(sx + 60)).append(" ").append(fmt(sy))
                    .append(" ").append(fmt(tx - 60)).append(" ").append(fmt(ty)).append(" ").append(fmt(tx)).append(" ").append(fmt(ty))
                    .append("\"><title>").append(esc(byId.get(edge.source()).name() + " → " + byId.get(edge.target()).name()
                            + " · " + edge.kind() + " · " + edge.status())).append("</title></path>");
        }
        for (var node : nodes) {
            var position = positions.get(node.id());
            svg.append("<g class=\"node node-").append(esc(node.kind().toLowerCase(Locale.ROOT))).append("\"><rect x=\"").append(position.x())
                    .append("\" y=\"").append(position.y()).append("\" width=\"").append(position.w()).append("\" height=\"").append(position.h())
                    .append("\" rx=\"10\"/><text class=\"name\" x=\"").append(position.x() + 12).append("\" y=\"").append(position.y() + 28)
                    .append("\">").append(esc(clip(node.name(), 28))).append("</text><text class=\"meta\" x=\"").append(position.x() + 12)
                    .append("\" y=\"").append(position.y() + 48).append("\">").append(esc(node.kind() + " · " + statusText(node.status())))
                    .append("</text></g>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private boolean include(Node node, String scope) {
        if ("APPLICATION".equals(scope)) return !node.kind().equals("SYSTEM");
        if ("MODULE".equals(scope)) return Set.of("MODULE", "COMPONENT").contains(node.kind());
        return true;
    }

    private List<Map<String, Object>> evidence(Document document, List<String> ids) {
        var result = new ArrayList<Map<String, Object>>();
        var wanted = new HashSet<>(ids == null ? List.of() : ids);
        for (var item : document.evidence()) {
            if (!wanted.contains(item.id())) continue;
            var map = new LinkedHashMap<String, Object>();
            map.put("path", item.filePath()); map.put("start", item.startLine()); map.put("end", item.endLine());
            map.put("rule", item.rule()); map.put("symbol", item.symbolId());
            result.add(map);
        }
        return result;
    }

    private String scriptJson(Object value) {
        try {
            return json.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e")
                    .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (Exception error) { return "{}"; }
    }

    private static String css(String theme) {
        var dark = "dark".equals(theme);
        return ":root{" + (dark
                ? "--bg:#0f1519;--panel:#151f26;--fg:#e8f0f3;--muted:#8fa3ad;--line:#2a3b45;--accent:#56b4a8;--bad:#ff8a80;"
                : "--bg:#f7f5ef;--panel:#fffdf8;--fg:#1d2b31;--muted:#61727a;--line:#d9d7ce;--accent:#247b70;--bad:#b4433b;")
                + "}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.55 system-ui,-apple-system,'Segoe UI',sans-serif}"
                + "header{display:flex;justify-content:space-between;gap:20px;align-items:flex-end;padding:28px 32px 20px;border-bottom:1px solid var(--line)}"
                + ".eyebrow{margin:0 0 4px;letter-spacing:.16em;font-size:11px;color:var(--accent);font-weight:700}h1{margin:0;font-size:30px}.sub{margin:6px 0 0;color:var(--muted)}"
                + ".header-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.local-link,.badge{border:1px solid var(--line);border-radius:999px;padding:7px 12px;text-decoration:none;color:var(--fg);font-size:12px}.badge{color:var(--muted)}"
                + ".layout{display:grid;grid-template-columns:minmax(0,1fr) 340px;gap:22px;padding:22px 32px 34px;align-items:start}.canvas{position:relative;background:var(--panel);border:1px solid var(--line);border-radius:18px;overflow:auto;min-height:420px}"
                + ".edges{position:absolute;inset:0;pointer-events:none}.edge{fill:none;stroke-width:1.6;opacity:.75;pointer-events:stroke;cursor:pointer}.edge-confirmed{stroke:var(--accent)}.edge-inferred{stroke:#d29a3a;stroke-dasharray:6 4}.edge-unresolved{stroke:var(--bad);stroke-dasharray:3 4}.edge:hover{stroke-width:3;opacity:1}"
                + ".node{position:absolute;display:flex;flex-direction:column;align-items:flex-start;gap:2px;text-align:left;padding:10px 12px;background:var(--panel);border:1px solid var(--line);border-radius:12px;color:var(--fg);cursor:pointer;box-shadow:0 2px 0 rgba(0,0,0,.04);overflow:hidden}"
                + ".node:hover,.node:focus-visible{border-color:var(--accent);outline:2px solid color-mix(in srgb,var(--accent) 35%,transparent)}.node strong{font-size:14px;overflow-wrap:anywhere}.node small{color:var(--muted);font-size:11px}.node span{color:var(--muted);font-size:11px;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}"
                + ".node-system,.node-application{border-color:var(--accent)}.node-datastore{border-style:dashed}.node-external_service{border-style:dotted}"
                + ".group{position:absolute;border:1px dashed var(--line);border-radius:16px;background:color-mix(in srgb,var(--accent) 5%,transparent);pointer-events:none}.group span{position:absolute;left:14px;top:8px;color:var(--muted);font-size:11px;letter-spacing:.08em;text-transform:uppercase}"
                + "aside{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:18px;position:sticky;top:18px}aside h2,aside h3{margin:0 0 8px}aside p{color:var(--muted)}#detail li{margin:8px 0;overflow-wrap:anywhere}.meta{font-size:12px;color:var(--muted)}.coverage{border-top:1px solid var(--line);margin-top:16px;padding-top:12px}.warning{color:var(--bad)}"
                + "details{margin-top:14px;border-top:1px solid var(--line);padding-top:10px}summary{cursor:pointer;color:var(--muted)}.footer{font-size:11px;overflow-wrap:anywhere}@media(max-width:980px){.layout{grid-template-columns:1fr;padding:16px}header{padding:20px 16px}.canvas{min-width:900px}}";
    }

    private static String statusText(String status) {
        return switch (status) { case "CONFIRMED" -> "已确认"; case "INFERRED" -> "推断"; case "UNRESOLVED" -> "未解析"; default -> status; };
    }

    private static String shortId(String id) { return id == null ? "" : id.substring(0, Math.min(8, id.length())); }
    private static String clip(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private record Position(int x, int y, int w, int h) {}
    private record GroupBox(Node module, int x, int y, int width, int height) {}
}
