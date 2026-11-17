package dev.codeatlas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TEST_ORDER_DB_URL:jdbc:h2:mem:evidence_order;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=target/evidence-order-test-data"})
class EvidenceOrderIntegrationTest {
    @Autowired TestRestTemplate http;
    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock clock() { return Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC); }
    }
    JsonNode call(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        var response = http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }
    JsonNode reflect(String knowledgeId, String rating) {
        return call(HttpMethod.POST, "/progress/evidence", Map.of("knowledgeId", knowledgeId, "sourceType", "PERSONAL_REFLECTION",
                "rating", rating, "reflection", rating, "idempotencyKey", UUID.randomUUID().toString()));
    }
    @Test void laterFactWinsWhenOccurrenceAndCreationTimesTie() {
        for (int i = 0; i < 20; i++) {
            String id = call(HttpMethod.POST, "/knowledge", Map.of("title", "Tie " + i, "body", "fixed clock", "tags", List.of(),
                    "sourceIds", List.of(), "idempotencyKey", UUID.randomUUID().toString())).get("id").asText();
            var first = reflect(id, "UNDERSTOOD");
            var second = reflect(id, "NEEDS_REVIEW");
            assertThat(second.get("occurredAtMs")).isEqualTo(first.get("occurredAtMs"));
            assertThat(second.get("createdAtMs")).isEqualTo(first.get("createdAtMs"));
            var progress = call(HttpMethod.GET, "/progress/knowledge/" + id, null);
            assertThat(progress.get("assessment").get("status").asText()).as("pair " + i).isEqualTo("NEEDS_REVIEW");
        }
    }
}
