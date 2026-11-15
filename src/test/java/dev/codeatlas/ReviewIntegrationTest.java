package dev.codeatlas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TEST_REVIEW_DB_URL:jdbc:h2:mem:review;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=target/review-test-data"})
class ReviewIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired TestClock clock;
    @Autowired dev.codeatlas.store.AtlasStore store;
    @Autowired FailingStore failingStore;
    @Autowired dev.codeatlas.review.ReviewService reviews;
    @Autowired javax.sql.DataSource dataSource;
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
        @Bean @Primary FailingStore failingStore(org.springframework.jdbc.core.JdbcTemplate jdbc,
                org.springframework.transaction.support.TransactionTemplate tx, com.fasterxml.jackson.databind.ObjectMapper json) { return new FailingStore(jdbc, tx, json); }
    }
    static class FailingStore extends dev.codeatlas.store.AtlasStore {
        final java.util.concurrent.atomic.AtomicBoolean failStateWrite = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean failProjectionWrite = new java.util.concurrent.atomic.AtomicBoolean();
        public void armFailure() { failStateWrite.set(true); }
        public void armProjectionFailure() { failProjectionWrite.set(true); }
        FailingStore(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.support.TransactionTemplate tx, com.fasterxml.jackson.databind.ObjectMapper json) { super(jdbc, tx, json); }
        @Override public void update(String sql, Object... args) {
            super.update(sql, args);
            if (sql.startsWith("UPDATE review_states SET revision=") && failStateWrite.compareAndSet(true, false)) throw new org.springframework.dao.DataIntegrityViolationException("Injected review transaction failure");
            if (sql.startsWith("INSERT INTO mastery_assessments") && failProjectionWrite.compareAndSet(true, false)) throw new org.springframework.dao.DataIntegrityViolationException("Injected projection failure");
        }
    }
    static class TestClock extends Clock {
        private final AtomicLong time = new AtomicLong(Instant.parse("2026-11-15T00:00:00Z").toEpochMilli());
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(time.get()); }
        void advance(Duration duration) { time.addAndGet(duration.toMillis()); }
    }
    String key() { return UUID.randomUUID().toString(); }
    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        return http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    JsonNode ok(HttpMethod method, String path, Object body) {
        var response = call(method, path, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue(); return response.getBody();
    }
    JsonNode card() {
        return ok(HttpMethod.POST, "/cards", Map.of("question", "What does return 1 do?", "answer", "Returns integer 1", "explanation", "A fixed literal", "sourceIds", List.of(), "tags", List.of("Java"), "idempotencyKey", key()));
    }
    JsonNode enroll(JsonNode card) { return ok(HttpMethod.POST, "/reviews/cards/" + card.get("id").asText() + "/enroll", Map.of("idempotencyKey", key())); }
    JsonNode start(JsonNode card) { return ok(HttpMethod.POST, "/reviews/cards/" + card.get("id").asText() + "/attempts", Map.of("idempotencyKey", key())); }
    JsonNode reveal(JsonNode attempt) { return ok(HttpMethod.POST, "/reviews/attempts/" + attempt.get("id").asText() + "/reveal", Map.of("revision", attempt.get("revision").asInt(), "idempotencyKey", key())); }
    Map<String, Object> gradeBody(JsonNode attempt, String rating) { return Map.of("revision", attempt.get("revision").asInt(), "rating", rating, "reflection", "Checked against reference", "idempotencyKey", key()); }
    Map<String, Object> editBody(JsonNode card, String answer, String notes) {
        return Map.of("revision", card.get("revision").asInt(), "question", card.get("question").asText(), "answer", answer,
                "explanation", card.get("explanation").asText(), "notes", notes, "sourceIds", List.of(), "tags", List.of());
    }
    Map<String, Object> management(JsonNode state) { return Map.of("revision", state.get("revision").asInt(), "epoch", state.get("epoch").asInt(), "idempotencyKey", key()); }

    @Test void enrollmentRecallRevealAndTwoRatingsKeepImmutableHistory() {
        var card = card(); String cardPath = "/reviews/cards/" + card.get("id").asText();
        var enrollment = Map.of("idempotencyKey", key());
        var state = ok(HttpMethod.POST, cardPath + "/enroll", enrollment);
        assertThat(ok(HttpMethod.POST, cardPath + "/enroll", enrollment).get("epoch").asInt()).isEqualTo(1);
        assertThat(state.get("dueAtMs").asLong()).isEqualTo(clock.millis());
        var attempt = start(card); String path = "/reviews/attempts/" + attempt.get("id").asText();
        assertThat(attempt.get("card").has("answer")).isFalse();
        assertThat(call(HttpMethod.POST, path + "/submit", gradeBody(attempt, "GOOD")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var saved = ok(HttpMethod.PUT, path, Map.of("revision", attempt.get("revision").asInt(), "recalledAnswer", "It returns one"));
        assertThat(ok(HttpMethod.GET, path, null).get("recalledAnswer").asText()).isEqualTo("It returns one");
        var shown = reveal(saved); assertThat(shown.get("card").get("answer").asText()).contains("integer 1");
        assertThat(call(HttpMethod.PUT, path, Map.of("revision", shown.get("revision").asInt(), "recalledAnswer", "After seeing answer")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var body = gradeBody(shown, "GOOD"); var done = ok(HttpMethod.POST, path + "/submit", body);
        assertThat(done.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(ok(HttpMethod.POST, path + "/submit", body).get("reviewEvent")).isEqualTo(done.get("reviewEvent"));
        var firstEvent = done.get("reviewEvent");
        assertThat(ok(HttpMethod.GET, "/reviews/events/" + firstEvent.get("id").asText(), null)).isEqualTo(firstEvent);
        assertThat(firstEvent.get("payload").get("rating").asText()).isEqualTo("GOOD");
        assertThat(ok(HttpMethod.GET, cardPath + "/state", null).get("dueAtMs").asLong()).isEqualTo(clock.millis() + 600_000);
        clock.advance(Duration.ofMinutes(10));
        var second = reveal(start(card)); ok(HttpMethod.POST, "/reviews/attempts/" + second.get("id").asText() + "/submit", gradeBody(second, "GOOD"));
        var history = ok(HttpMethod.GET, cardPath + "/history", null);
        assertThat(history.get("items").findValuesAsText("kind").stream().filter("REVIEW"::equals)).hasSize(2);
        assertThat(ok(HttpMethod.GET, path, null).get("reviewEvent")).isEqualTo(firstEvent);
    }

    @Test void skipPreservesRecallAndScheduleAndStaleAttemptCannotDoubleRate() {
        var card = card(); var state = enroll(card);
        var skipped = start(card); String skipPath = "/reviews/attempts/" + skipped.get("id").asText();
        var saved = ok(HttpMethod.PUT, skipPath, Map.of("revision", skipped.get("revision").asInt(), "recalledAnswer", "Keep my recall"));
        var body = Map.of("revision", saved.get("revision").asInt(), "idempotencyKey", key());
        assertThat(ok(HttpMethod.POST, skipPath + "/skip", body).get("recalledAnswer").asText()).isEqualTo("Keep my recall");
        assertThat(ok(HttpMethod.GET, "/reviews/cards/" + card.get("id").asText() + "/state", null).get("dueAtMs")).isEqualTo(state.get("dueAtMs"));
        var first = reveal(start(card)); var stale = reveal(start(card));
        ok(HttpMethod.POST, "/reviews/attempts/" + first.get("id").asText() + "/submit", gradeBody(first, "EASY"));
        assertThat(call(HttpMethod.POST, "/reviews/attempts/" + stale.get("id").asText() + "/submit", gradeBody(stale, "GOOD")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ok(HttpMethod.GET, "/reviews/attempts/" + stale.get("id").asText(), null).get("status").asText()).isEqualTo("REVEALED");
    }

    @Test void contentResetEpochAndMetadataEditsProtectHistory() {
        var card = card(); enroll(card); var old = reveal(start(card)); String id = card.get("id").asText();
        var metadata = ok(HttpMethod.PUT, "/cards/" + id, editBody(card, card.get("answer").asText(), "Personal notes"));
        ok(HttpMethod.POST, "/reviews/attempts/" + old.get("id").asText() + "/submit", gradeBody(old, "GOOD"));
        var active = reveal(start(metadata));
        ok(HttpMethod.PUT, "/cards/" + id, editBody(metadata, "Updated reference", "Personal notes"));
        var changed = ok(HttpMethod.GET, "/reviews/cards/" + id + "/state", null);
        assertThat(changed.get("status").asText()).isEqualTo("CONTENT_CHANGED");
        assertThat(call(HttpMethod.POST, "/reviews/attempts/" + active.get("id").asText() + "/submit", gradeBody(active, "GOOD")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var reset = ok(HttpMethod.POST, "/reviews/cards/" + id + "/reset", management(changed));
        assertThat(reset.get("epoch").asInt()).isEqualTo(2);
        assertThat(reveal(start(metadata)).get("card").get("answer").asText()).isEqualTo("Updated reference");
        ok(HttpMethod.DELETE, "/cards/" + id, Map.of());
        assertThat(ok(HttpMethod.GET, "/reviews/attempts/" + old.get("id").asText(), null).get("card").get("answer").asText()).contains("integer 1");
        assertThat(ok(HttpMethod.GET, "/reviews/cards/" + id + "/history", null).get("total").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test void suspensionAndClockRollbackCannotWriteFalseReview() {
        var card = card(); var state = enroll(card); String path = "/reviews/cards/" + card.get("id").asText();
        var paused = ok(HttpMethod.POST, path + "/suspend", management(state));
        assertThat(paused.get("suspended").asBoolean()).isTrue();
        assertThat(call(HttpMethod.POST, path + "/attempts", Map.of("idempotencyKey", key())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ok(HttpMethod.POST, path + "/resume", management(paused));
        var first = reveal(start(card)); ok(HttpMethod.POST, "/reviews/attempts/" + first.get("id").asText() + "/submit", gradeBody(first, "GOOD"));
        var second = reveal(start(card)); clock.advance(Duration.ofSeconds(-1));
        try { assertThat(call(HttpMethod.POST, "/reviews/attempts/" + second.get("id").asText() + "/submit", gradeBody(second, "GOOD")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); }
        finally { clock.advance(Duration.ofSeconds(1)); }
        var events = ok(HttpMethod.GET, path + "/history", null).get("items").findValuesAsText("kind");
        assertThat(events.stream().filter("REVIEW"::equals)).hasSize(1);
    }

    @Test void failedStateWriteRollsBackEventAttemptAndIdempotencyThenRetries() {
        var card = card(); var state = enroll(card); var shown = reveal(start(card));
        String path = "/reviews/attempts/" + shown.get("id").asText(); var body = gradeBody(shown, "GOOD");
        failingStore.armFailure();
        assertThat(call(HttpMethod.POST, path + "/submit", body).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ok(HttpMethod.GET, path, null).get("status").asText()).isEqualTo("REVEALED");
        assertThat(ok(HttpMethod.GET, "/reviews/cards/" + card.get("id").asText() + "/state", null).get("revision")).isEqualTo(state.get("revision"));
        assertThat(ok(HttpMethod.GET, "/reviews/cards/" + card.get("id").asText() + "/history", null).get("total").asInt()).isEqualTo(1);
        assertThat(ok(HttpMethod.POST, path + "/submit", body).get("status").asText()).isEqualTo("COMMITTED");
    }

    @Test void simultaneousGradingProducesOneCommittedAttemptAndOneReview() throws Exception {
        var card = card(); enroll(card); var first = reveal(start(card)); var second = reveal(start(card));
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = threads.submit(() -> { gate.await(); return call(HttpMethod.POST, "/reviews/attempts/" + first.get("id").asText() + "/submit", gradeBody(first, "GOOD")); });
            var b = threads.submit(() -> { gate.await(); return call(HttpMethod.POST, "/reviews/attempts/" + second.get("id").asText() + "/submit", gradeBody(second, "EASY")); });
            gate.countDown();
            var codes = List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS).getStatusCode().value(), b.get(10, java.util.concurrent.TimeUnit.SECONDS).getStatusCode().value());
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(ok(HttpMethod.GET, "/reviews/cards/" + card.get("id").asText() + "/history", null).get("items").findValuesAsText("kind").stream().filter("REVIEW"::equals)).hasSize(1);
    }

    @Test void failedKnowledgeProjectionRollsBackReviewAndEvidenceTogether() {
        var knowledge = ok(HttpMethod.POST, "/knowledge", Map.of("title", "Atomic evidence", "body", "Review and evidence together", "tags", List.of(), "sourceIds", List.of(), "idempotencyKey", key()));
        var card = ok(HttpMethod.POST, "/cards", Map.of("question", "Atomic?", "answer", "Yes", "knowledgeId", knowledge.get("id").asText(), "tags", List.of(), "sourceIds", List.of(), "idempotencyKey", key()));
        var state = enroll(card); var shown = reveal(start(card)); String path = "/reviews/attempts/" + shown.get("id").asText(); var body = gradeBody(shown, "GOOD");
        failingStore.armProjectionFailure();
        assertThat(call(HttpMethod.POST, path + "/submit", body).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ok(HttpMethod.GET, "/progress/knowledge/" + knowledge.get("id").asText(), null).get("evidence")).isEmpty();
        assertThat(ok(HttpMethod.GET, path, null).get("status").asText()).isEqualTo("REVEALED");
        assertThat(ok(HttpMethod.GET, "/reviews/cards/" + card.get("id").asText() + "/state", null).get("revision")).isEqualTo(state.get("revision"));
        ok(HttpMethod.POST, path + "/submit", body);
        assertThat(ok(HttpMethod.GET, "/progress/knowledge/" + knowledge.get("id").asText(), null).get("evidence")).hasSize(1);
    }

    @Test void mysqlConcurrentIdenticalGradeRestoresTheFirstResponse() throws Exception {
        try (var connection = dataSource.getConnection()) { org.junit.jupiter.api.Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName().equals("MySQL")); }
        var knowledge = ok(HttpMethod.POST, "/knowledge", Map.of("title", "Retry", "body", "One fact", "sourceIds", List.of(), "idempotencyKey", key()));
        var card = ok(HttpMethod.POST, "/cards", Map.of("question", "Retry?", "answer", "Once", "knowledgeId", knowledge.get("id").asText(), "sourceIds", List.of(), "idempotencyKey", key()));
        enroll(card); var shown = reveal(start(card)); var body = gradeBody(shown, "GOOD");
        var written = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = threads.submit(() -> store.transaction(() -> {
                var result = reviews.submit(shown.get("id").asText(), body); written.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); } catch (InterruptedException error) { throw new AssertionError(error); }
                return result;
            }));
            assertThat(written.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var reader = threads.submit(() -> reviews.submit(shown.get("id").asText(), body));
            try {
                boolean waiting = false;
                for (int index = 0; index < 200; index++) {
                    waiting = store.rows("SHOW FULL PROCESSLIST").stream().anyMatch(row -> { String query = String.valueOf(row.get("info")); return query.contains((String) body.get("idempotencyKey")) || query.contains(card.get("id").asText()) && query.contains("FOR UPDATE"); });
                    if (waiting) break; Thread.sleep(20);
                }
                assertThat(waiting).isTrue();
            } finally { release.countDown(); }
            assertThat(reader.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(writer.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertThat(ok(HttpMethod.GET, "/progress/knowledge/" + knowledge.get("id").asText(), null).get("evidence")).hasSize(1);
    }

    @Test void mysqlWaitingGradeReadsCommittedStateAfterTheCardLock() throws Exception {
        try (var connection = dataSource.getConnection()) { org.junit.jupiter.api.Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName().equals("MySQL")); }
        var card = card(); enroll(card); var first = reveal(start(card)); var second = reveal(start(card));
        var written = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = threads.submit(() -> store.transaction(() -> {
                var result = reviews.submit(first.get("id").asText(), gradeBody(first, "GOOD")); written.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); } catch (InterruptedException error) { throw new AssertionError(error); }
                return result;
            }));
            assertThat(written.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var reader = threads.submit(() -> {
                try { reviews.submit(second.get("id").asText(), gradeBody(second, "EASY")); return "UNEXPECTED_SUCCESS"; }
                catch (dev.codeatlas.web.ApiException error) { return error.code; }
            });
            try {
                boolean waiting = false;
                for (int index = 0; index < 200; index++) {
                    waiting = store.rows("SHOW FULL PROCESSLIST").stream().anyMatch(row -> { String query = String.valueOf(row.get("info")); return query.contains(card.get("id").asText()) && query.contains("learning_records") && query.contains("FOR UPDATE"); });
                    if (waiting) break; Thread.sleep(20);
                }
                assertThat(waiting).as("second grade must wait for the card lock").isTrue();
            } finally { release.countDown(); }
            assertThat(writer.get(10, java.util.concurrent.TimeUnit.SECONDS).get("status")).isEqualTo("COMMITTED");
            assertThat(reader.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("STALE_REVIEW_STATE");
        } finally { release.countDown(); }
    }
}
