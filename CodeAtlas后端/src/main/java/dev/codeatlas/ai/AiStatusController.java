package dev.codeatlas.ai;

import dev.codeatlas.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public, secret-free AI service status for the unified assistant entry. */
@RestController
@RequestMapping("/api/ai")
public class AiStatusController {
    private static final List<Map<String, String>> CAPABILITIES = List.of(
            Map.of("id", "explanation", "name", "源码解释与追问", "requires", "已选方法、类型或文件范围"),
            Map.of("id", "training", "name", "理解题与出题", "requires", "固定源码来源"),
            Map.of("id", "practice-feedback", "name", "实践反馈", "requires", "一次代码练习提交"),
            Map.of("id", "guide", "name", "六步引导建议", "requires", "引导草稿与来源"),
            Map.of("id", "project-note", "name", "项目/技术说明", "requires", "固定源码范围"));
    private final AiClient client;

    public AiStatusController(AiClient client) { this.client = client; }

    @GetMapping("/status")
    public Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("capabilities", CAPABILITIES);
        try {
            var provider = client.provider();
            result.put("reachable", true);
            result.put("configured", Boolean.TRUE.equals(provider.get("configured")));
            result.put("provider", provider.get("provider"));
            result.put("model", provider.get("model"));
            result.put("baseUrl", provider.get("baseUrl"));
            result.put("fingerprint", provider.get("fingerprint"));
            result.put("maxTokens", provider.get("maxTokens"));
            result.put("message", Boolean.TRUE.equals(provider.get("configured"))
                    ? "本机 AI 服务已连接" : "AI 服务已连接，但模型密钥或提供方尚未配置");
        } catch (ApiException error) {
            result.put("reachable", false);
            result.put("configured", false);
            result.put("code", error.code);
            result.put("message", error.getMessage());
        }
        return result;
    }
}
