package dev.codeatlas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.*;

@Component
public class LocalAccessFilter extends OncePerRequestFilter {
    private final String token = UUID.randomUUID() + "-" + UUID.randomUUID();
    private final ObjectMapper json;
    private final String developmentOrigin;
    public LocalAccessFilter(ObjectMapper json, @Value("${codeatlas.allowed-origin}") String developmentOrigin) {
        this.json = json;
        this.developmentOrigin = developmentOrigin;
    }
    public String token() { return token; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) { chain.doFilter(request, response); return; }
        String host = request.getServerName();
        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (!Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host) || "cross-site".equals(fetchSite) || !allowedOrigin(origin, request)) {
            reject(response, 403, "LOCAL_ACCESS_ONLY", "仅允许本机同源访问");
            return;
        }
        boolean publicRoute = request.getRequestURI().equals("/api/session") || request.getRequestURI().equals("/api/health");
        if (!publicRoute && !token.equals(request.getHeader("X-CodeAtlas-Token"))) {
            reject(response, 401, "SESSION_REQUIRED", "请刷新页面建立本机会话");
            return;
        }
        if (Set.of("POST", "PUT", "PATCH").contains(request.getMethod()) && (request.getContentType() == null || !request.getContentType().startsWith("application/json"))) {
            reject(response, 415, "JSON_REQUIRED", "请使用 JSON 请求");
            return;
        }
        if (request.getContentLengthLong() > 1_048_576) { reject(response, 413, "REQUEST_LIMIT", "请求内容过大"); return; }
        if (Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
            byte[] body = request.getInputStream().readNBytes(1_048_577);
            if (body.length > 1_048_576) { reject(response, 413, "REQUEST_LIMIT", "请求内容过大"); return; }
            request = new BufferedRequest(request, body);
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }

    private static class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var input = new java.io.ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    try { if (isFinished()) listener.onAllDataRead(); else listener.onDataAvailable(); }
                    catch (IOException e) { listener.onError(e); }
                }
            };
        }
        @Override public java.io.BufferedReader getReader() { return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
    }

    private boolean allowedOrigin(String origin, HttpServletRequest request) {
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() < 0 ? 80 : uri.getPort();
            URI configured = URI.create(developmentOrigin);
            boolean developmentLoopback = "http".equals(uri.getScheme()) && "http".equals(configured.getScheme())
                    && Set.of("localhost", "127.0.0.1").contains(uri.getHost())
                    && Set.of("localhost", "127.0.0.1").contains(configured.getHost())
                    && port == configured.getPort();
            return origin.equals(developmentOrigin) || developmentLoopback
                    || ("http".equals(uri.getScheme()) && uri.getHost().equals(request.getServerName()) && port == request.getServerPort());
        } catch (Exception e) { return false; }
    }
    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), Map.of("code", code, "message", message, "details", Map.of(), "requestId", UUID.randomUUID().toString()));
    }
}
