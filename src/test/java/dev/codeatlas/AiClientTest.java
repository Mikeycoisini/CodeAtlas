package dev.codeatlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.codeatlas.ai.AiClient;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AiClientTest {
    @Test void remembersCancellationBeforeConnectionRegistration() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/generate", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{}".getBytes(); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", 2, new ObjectMapper());
            // Cancellation lands after the worker's final status check, before generate registers a connection.
            client.cancel("cancelled-before-connect");
            assertThatThrownBy(() -> client.generate("cancelled-before-connect", Map.of("contextJson", "public fixture")))
                    .isInstanceOf(dev.codeatlas.web.ApiException.class);
            assertThat(calls.get()).isZero();
        } finally { server.stop(0); }
    }
}
