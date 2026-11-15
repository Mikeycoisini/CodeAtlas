package dev.codeatlas.review;

import io.github.openspacedrepetition.Card;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FsrsSchedulerTest {
    @Test void pureAdapterReplaysGoldenSequenceAndPinsConfiguration() {
        // Golden intervals follow java-fsrs FSRSTest.testReviewCard (MIT), pinned commit in third-party notice.
        var scheduler = new FsrsScheduler(); long now = Instant.parse("2026-11-15T00:00:00Z").toEpochMilli();
        String state = scheduler.initial(now); var intervals = new ArrayList<Long>();
        for (String grade : List.of("GOOD","GOOD","GOOD","GOOD","GOOD","GOOD","AGAIN","AGAIN","GOOD","GOOD","GOOD","GOOD","GOOD")) {
            long reviewedAt = now;
            var output = scheduler.schedule(state, grade, now);
            assertThat(scheduler.schedule(state, grade, now)).isEqualTo(output);
            long due = ((Number) output.get("dueAtMs")).longValue(); intervals.add((due - now) / 86_400_000);
            state = (String) output.get("stateJson"); now = due;
            assertThat(Card.fromJson(state).getLastReview()).isEqualTo(Instant.ofEpochMilli(reviewedAt));
        }
        assertThat(intervals).containsExactly(0L,4L,14L,45L,135L,372L,0L,0L,2L,5L,10L,20L,40L);
        assertThat(scheduler.configJson()).contains("\"enableFuzzing\":false");
        assertThat(scheduler.configHash()).hasSize(64).isEqualTo(new FsrsScheduler().configHash());
        assertThatThrownBy(() -> scheduler.schedule(scheduler.initial(0), "BAD", 0)).isInstanceOf(dev.codeatlas.web.ApiException.class);
    }
}
