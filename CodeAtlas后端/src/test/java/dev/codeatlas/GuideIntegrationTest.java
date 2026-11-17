package dev.codeatlas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TEST_GUIDE_DB_URL:jdbc:h2:mem:guides;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TEST_DB_USER:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}", "codeatlas.data=target/guide-test-data"})
class GuideIntegrationTest {
    @Autowired TestRestTemplate http;
    String key() { return UUID.randomUUID().toString(); }
    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CodeAtlas-Token", http.getForObject("/api/session", JsonNode.class).get("token").asText());
        return http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    JsonNode ok(HttpMethod method, String path, Object body) { var result = call(method, path, body); assertThat(result.getStatusCode().is2xxSuccessful()).as(result.toString()).isTrue(); return result.getBody(); }
    JsonNode create() { return ok(HttpMethod.POST, "/guides", Map.of("title", "Understand transactions", "goal", "Study the transactional source and recall it", "sourceIds", List.of(), "knowledgeIds", List.of(), "idempotencyKey", key())); }
    JsonNode action(JsonNode guide, String action) { return ok(HttpMethod.POST, "/guides/" + guide.get("id").asText() + "/actions", Map.of("revision", guide.get("revision").asInt(), "action", action, "idempotencyKey", key())); }
    List<Map<String, Object>> steps(JsonNode guide) {
        var result = new ArrayList<Map<String, Object>>();
        guide.get("steps").forEach(step -> { var item = new LinkedHashMap<String, Object>(); step.fields().forEachRemaining(field -> item.put(field.getKey(), field.getValue().asText())); result.add(item); }); return result;
    }
    Map<String, Object> update(JsonNode guide, List<Map<String, Object>> steps) {
        return Map.of("revision", guide.get("revision").asInt(), "title", guide.get("title").asText(), "goal", guide.get("goal").asText(), "sourceIds", List.of(), "knowledgeIds", List.of(), "steps", steps);
    }

    @Test void optionalGuideReordersSkipsExitsAndResumesWithoutBlockingFeatures() {
        var guide = create(); assertThat(guide.get("steps")).hasSize(6); String path = "/guides/" + guide.get("id").asText();
        var steps = steps(guide); Collections.reverse(steps); steps.getFirst().put("status", "SKIPPED"); steps.getFirst().put("notes", "Will return later");
        var edited = ok(HttpMethod.PUT, path, update(guide, steps));
        assertThat(edited.get("steps").get(0).get("id").asText()).isEqualTo("REFLECT");
        var paused = action(edited, "PAUSE"); var exited = action(paused, "EXIT");
        assertThat(exited.get("status").asText()).isEqualTo("EXITED");
        assertThat(ok(HttpMethod.GET, "/cards", null).get("items")).isNotNull();
        var resumed = action(exited, "RESUME"); assertThat(resumed.get("steps")).isEqualTo(edited.get("steps"));
        assertThat(ok(HttpMethod.GET, path + "/history", null).get("total").asInt()).isEqualTo(5);
        assertThat(ok(HttpMethod.GET, path, null)).isEqualTo(resumed);
    }

    @Test void completionRequiresExplicitResolvedStepsAndPreservesSkippedCountsAndHistory() {
        var guide = create(); String path = "/guides/" + guide.get("id").asText();
        assertThat(call(HttpMethod.POST, path + "/actions", Map.of("revision", 1, "action", "COMPLETE", "idempotencyKey", key())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var steps = steps(guide); for (int index = 0; index < steps.size(); index++) steps.get(index).put("status", index == 0 ? "DONE" : "SKIPPED");
        var edited = ok(HttpMethod.PUT, path, update(guide, steps));
        var body = Map.of("revision", edited.get("revision").asInt(), "action", "COMPLETE", "idempotencyKey", key());
        var complete = ok(HttpMethod.POST, path + "/actions", body);
        assertThat(ok(HttpMethod.POST, path + "/actions", body)).isEqualTo(complete);
        assertThat(complete.get("doneCount").asInt()).isEqualTo(1); assertThat(complete.get("skippedCount").asInt()).isEqualTo(5);
        assertThat(action(complete, "REOPEN").get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test void malformedStepSetsAndStaleEditsAreRejectedAtomically() {
        var guide = create(); String path = "/guides/" + guide.get("id").asText(); var steps = steps(guide);
        steps.removeLast(); assertThat(call(HttpMethod.PUT, path, update(guide, steps)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ok(HttpMethod.GET, path, null).get("revision").asInt()).isEqualTo(1);
        var latest = ok(HttpMethod.PUT, path, update(guide, steps(guide)));
        assertThat(call(HttpMethod.PUT, path, update(guide, steps(guide))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ok(HttpMethod.GET, path, null)).isEqualTo(latest);
        assertThat(ok(HttpMethod.GET, "/guides?query=Understand&status=ACTIVE", null).get("total").asInt()).isGreaterThanOrEqualTo(1);
    }
}
