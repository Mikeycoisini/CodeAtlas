package dev.codeatlas.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Publishes a complete random token shared by local Java and Python starts. */
final class LocalServiceToken {
    private LocalServiceToken() {}

    static String load() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path workspace = Files.isDirectory(current.resolve("CodeAtlasAI")) ? current : current.getParent();
        if (workspace == null || !Files.isDirectory(workspace.resolve("CodeAtlasAI"))) {
            throw new IOException("请从 CodeAtlas 根目录或后端目录启动，或配置内部服务令牌");
        }
        Path directory = Files.createDirectories(workspace.resolve(".runtime"));
        Path target = directory.resolve("ai-service.token");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        try {
            Files.writeString(target, HexFormat.of().formatHex(random), StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
            // Another service owns creation. Wait for its write to finish below.
        }
        String token = "";
        for (int attempt = 0; attempt < 100; attempt++) {
            token = Files.readString(target, StandardCharsets.US_ASCII).trim();
            if (token.length() == 64) break;
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("内部令牌读取已中断", error);
            }
        }
        if (!token.matches("[0-9a-f]{64}")) throw new IOException("本机内部令牌文件无效");
        return token;
    }
}
