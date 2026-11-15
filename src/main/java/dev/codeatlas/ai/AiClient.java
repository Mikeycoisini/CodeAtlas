package dev.codeatlas.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeatlas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiClient {
    private final String baseUrl;
    private final String token;
    private final int timeoutMillis;
    private final ObjectMapper json;
    private final Map<String, HttpURLConnection> inFlight = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Object dispatchLock = new Object();

    public AiClient(@Value("${codeatlas.ai.url:http://127.0.0.1:8001}") String baseUrl,
                    @Value("${codeatlas.ai.service-token:}") String token,
                    @Value("${codeatlas.ai.timeout-seconds:70}") int timeout, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.token = token;
        this.timeoutMillis = Math.max(1, Math.min(timeout, 310)) * 1000; this.json = json;
    }

    public Map<String, Object> provider() {
        var result = invoke("/internal/provider", null, null);
        for (String name : List.of("provider", "model", "baseUrl", "fingerprint")) {
            if (!(result.get(name) instanceof String text) || text.isBlank() || text.length() > 2048)
                throw new ApiException(502, "INVALID_RESPONSE_SCHEMA", "AI 配置响应无效");
        }
        if (!(result.get("configured") instanceof Boolean)) throw new ApiException(502, "INVALID_RESPONSE_SCHEMA", "AI 配置响应无效");
        return result;
    }

    public Map<String, Object> generate(String id, Map<String, Object> body) { return invoke("/internal/generate", body, id); }
    public void cancel(String id) {
        synchronized (dispatchLock) {
            cancelled.add(id);
            var connection = inFlight.get(id);
            if (connection != null) connection.disconnect();
        }
    }
    public void release(String id) {
        synchronized (dispatchLock) { cancelled.remove(id); inFlight.remove(id); }
    }
    private void send(HttpURLConnection connection, byte[] bytes, String id) throws IOException {
        synchronized (dispatchLock) {
            if (cancelled.contains(id)) throw new ApiException(409, "CANCELLED", "生成已取消");
            inFlight.put(id, connection);
            // Serialize cancellation with the local request write. A completed cancellation cannot be followed by a send.
            try (var output = connection.getOutputStream()) { output.write(bytes); }
        }
    }

    private Map<String, Object> invoke(String path, Map<String, Object> body, String id) {
        if (token.isBlank()) throw new ApiException(503, "AI_SERVICE_NOT_CONFIGURED", "请配置本机 AI 服务令牌");
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(baseUrl + path);
            if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new ApiException(503, "AI_SERVICE_NOT_CONFIGURED", "AI 服务地址必须为本机 HTTP 地址");
            connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(timeoutMillis); connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("X-CodeAtlas-Service-Token", token);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = json.writeValueAsBytes(body);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.length);
                send(connection, bytes, id);
            }
            int status = connection.getResponseCode();
            byte[] bytes;
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new ApiException(502, "AI_SERVICE_ERROR", "AI 服务响应为空");
            try (stream) { bytes = stream.readNBytes(2_097_153); }
            if (bytes.length > 2_097_152) throw new ApiException(502, "RESPONSE_LIMIT", "AI 服务响应超过大小限制");
            Map<String, Object> result = json.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() {});
            if (status != 200) {
                String code = result.get("code") instanceof String value && value.matches("[A-Z_]{1,80}") ? value : "AI_SERVICE_ERROR";
                throw new ApiException(status >= 400 && status < 600 ? status : 502, code, "AI 请求失败，请根据错误码检查模型配置或稍后重试");
            }
            return result;
        } catch (SocketTimeoutException e) {
            throw new ApiException(504, "PROVIDER_TIMEOUT", "AI 请求超时，请收窄问题后重新授权");
        } catch (IOException | IllegalArgumentException e) {
            throw new ApiException(503, "AI_SERVICE_UNAVAILABLE", "无法连接本机 AI 服务，手工资料仍可使用");
        } finally {
            if (id != null) inFlight.remove(id);
            if (connection != null) connection.disconnect();
        }
    }
}
