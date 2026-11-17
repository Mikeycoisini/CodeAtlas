package dev.codeatlas.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeatlas.store.AtlasStore;
import dev.codeatlas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/** Encrypted local GitHub credentials. Tokens are never returned by the API or written to logs. */
@Service
public class GitHubCredentialService {
    public record Credential(String id, String label, String scopes, String createdAt, String lastUsedAt, String revokedAt) {}
    private static final String KEY_FILE = ".github-key";
    private final AtlasStore store;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final Path keyFile;
    private volatile byte[] key;

    public GitHubCredentialService(AtlasStore store, ObjectMapper json, @Value("${codeatlas.data}") String data) {
        this.store = store; this.json = json;
        var root = Path.of(data).toAbsolutePath().normalize();
        this.keyFile = root.resolve(KEY_FILE);
        try { Files.createDirectories(root); } catch (Exception ignored) { }
    }

    public Map<String, Object> create(String label, String token) {
        var remote = validate(token);
        var id = AtlasStore.uuid();
        var iv = new byte[12]; new SecureRandom().nextBytes(iv);
        var cipher = encrypt(token, iv);
        store.update("INSERT INTO github_credentials(id,label,token_cipher,token_iv,scopes,created_at) VALUES(?,?,?,?,?,?)",
                id, cleanLabel(label), Base64.getEncoder().encodeToString(cipher), Base64.getEncoder().encodeToString(iv), remote.scopes(), AtlasStore.now());
        return view(store.one("SELECT * FROM github_credentials WHERE id=?", id));
    }

    public List<Map<String, Object>> list() {
        return store.rows("SELECT id,label,scopes,created_at,last_used_at,revoked_at FROM github_credentials ORDER BY created_at DESC");
    }

    public Map<String, Object> revoke(String id) {
        var row = store.one("SELECT * FROM github_credentials WHERE id=?", id);
        if (row.get("revokedAt") == null) {
            store.update("UPDATE github_credentials SET revoked_at=?,token_cipher='',token_iv='' WHERE id=?", AtlasStore.now(), id);
        }
        return view(store.one("SELECT * FROM github_credentials WHERE id=?", id));
    }

    public Map<String, Object> rotate(String id, String token) {
        var remote = validate(token);
        var iv = new byte[12]; new SecureRandom().nextBytes(iv);
        var cipher = encrypt(token, iv);
        store.update("UPDATE github_credentials SET token_cipher=?,token_iv=?,scopes=?,revoked_at=NULL WHERE id=?",
                Base64.getEncoder().encodeToString(cipher), Base64.getEncoder().encodeToString(iv), remote.scopes(), id);
        return view(store.one("SELECT * FROM github_credentials WHERE id=?", id));
    }

    public String token(String id) {
        var row = store.one("SELECT * FROM github_credentials WHERE id=?", id);
        if (row.get("revokedAt") != null) throw new ApiException(409, "GITHUB_CREDENTIAL_REVOKED", "GitHub 凭据已撤销");
        try {
            var iv = Base64.getDecoder().decode((String) row.get("tokenIv"));
            var cipher = Base64.getDecoder().decode((String) row.get("tokenCipher"));
            var value = decrypt(cipher, iv);
            store.update("UPDATE github_credentials SET last_used_at=? WHERE id=?", AtlasStore.now(), id);
            return value;
        } catch (Exception error) {
            throw new ApiException(500, "GITHUB_CREDENTIAL_UNREADABLE", "GitHub 凭据无法解密，请轮换凭据");
        }
    }

    public void bind(String projectId, String credentialId, String repoUrl, String ref, String commit) {
        var existing = store.rows("SELECT project_id FROM github_repo_bindings WHERE project_id=?", projectId);
        if (existing.isEmpty()) {
            store.update("INSERT INTO github_repo_bindings(project_id,credential_id,repo_url,ref,last_commit,last_checked_at) VALUES(?,?,?,?,?,?)",
                    projectId, credentialId, repoUrl, ref, commit, AtlasStore.now());
        } else {
            store.update("UPDATE github_repo_bindings SET credential_id=?,repo_url=?,ref=?,last_commit=?,last_checked_at=? WHERE project_id=?",
                    credentialId, repoUrl, ref, commit, AtlasStore.now(), projectId);
        }
    }

    public Map<String, Object> binding(String projectId) {
        var rows = store.rows("SELECT b.*, c.label AS credential_label FROM github_repo_bindings b JOIN github_credentials c ON c.id=b.credential_id WHERE b.project_id=?", projectId);
        if (rows.isEmpty()) return Map.of("bound", false, "projectId", projectId);
        var binding = new LinkedHashMap<String, Object>(rows.getFirst());
        binding.put("bound", true);
        return binding;
    }

    protected RemoteUser validate(String token) {
        var value = token == null ? "" : token.trim();
        if (value.length() < 20 || value.length() > 512 || value.chars().anyMatch(Character::isWhitespace))
            throw new ApiException(400, "INVALID_GITHUB_CREDENTIAL", "请输入有效的 GitHub 访问令牌");
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + value)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CodeAtlas")
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new ApiException(400, "INVALID_GITHUB_CREDENTIAL", "GitHub 凭据验证失败，请检查权限或过期时间");
            JsonNode body = json.readTree(response.body());
            var login = body.path("login").asText("");
            if (login.isBlank()) throw new ApiException(400, "INVALID_GITHUB_CREDENTIAL", "GitHub 凭据验证失败");
            var scopes = response.headers().firstValue("X-OAuth-Scopes").orElse("");
            return new RemoteUser(login, scopes);
        } catch (ApiException error) { throw error;
        } catch (Exception error) {
            throw new ApiException(503, "GITHUB_UNAVAILABLE", "无法连接 GitHub 验证凭据，请检查网络");
        }
    }

    private Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id")); result.put("label", row.get("label")); result.put("scopes", row.get("scopes"));
        result.put("createdAt", row.get("createdAt")); result.put("lastUsedAt", row.get("lastUsedAt")); result.put("revokedAt", row.get("revokedAt"));
        return result;
    }

    private String cleanLabel(String label) {
        var value = label == null ? "" : label.trim();
        if (value.isBlank() || value.length() > 120) throw new ApiException(400, "INVALID_GITHUB_LABEL", "请输入 1–120 字符的凭据名称");
        return value;
    }

    private byte[] encrypt(String value, byte[] iv) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new ApiException(500, "CREDENTIAL_ENCRYPTION_FAILED", "凭据无法安全保存"); }
    }

    private String decrypt(byte[] value, byte[] iv) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(value), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private byte[] key() {
        if (key != null) return key;
        synchronized (this) {
            if (key != null) return key;
            try {
                if (!Files.exists(keyFile)) {
                    var generated = new byte[32]; new SecureRandom().nextBytes(generated);
                    Files.write(keyFile, generated, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    try { keyFile.toFile().setReadable(true, true); keyFile.toFile().setWritable(true, true); } catch (Exception ignored) { }
                }
                key = Files.readAllBytes(keyFile);
                if (key.length != 32) throw new IllegalStateException("invalid key");
                return key;
            } catch (Exception error) { throw new ApiException(500, "CREDENTIAL_KEY_FAILED", "本机凭据密钥不可用"); }
        }
    }

    public record RemoteUser(String login, String scopes) {}
}
