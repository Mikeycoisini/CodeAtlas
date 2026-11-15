package dev.codeatlas.review;

import dev.codeatlas.snapshot.SnapshotScanner;
import dev.codeatlas.web.ApiException;
import io.github.openspacedrepetition.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class FsrsScheduler {
    public static final String ALGORITHM = "FSRS6_JAVA_1_0_0";
    public static final String CONFIG_VERSION = "DEFAULT_V1";
    private static final double[] PARAMETERS = {0.2172,1.1771,3.2602,16.1507,7.0114,0.57,2.0966,0.0069,1.5261,0.112,1.0178,1.849,0.1133,0.3127,2.2934,0.2191,3.0004,0.7536,0.3332,0.1437,0.2};
    private Scheduler scheduler() {
        return Scheduler.builder().parameters(PARAMETERS.clone()).desiredRetention(0.9)
                .learningSteps(new Duration[]{Duration.ofSeconds(60), Duration.ofSeconds(600)})
                .relearningSteps(new Duration[]{Duration.ofSeconds(600)}).maximumInterval(36500).enableFuzzing(false).build();
    }
    public String configJson() { return scheduler().toJson(); }
    public String configHash() { return SnapshotScanner.hash(configJson()); }
    public String initial(long now) { return Card.builder().cardId(0).due(Instant.ofEpochMilli(now)).build().toJson(); }
    public Map<String, Object> schedule(String state, String grade, long reviewedAt) {
        Rating rating;
        try { rating = Rating.valueOf(grade); } catch (IllegalArgumentException error) { throw new ApiException(400, "INVALID_RATING", "请选择忘记、困难、记住或简单"); }
        var card = Card.fromJson(state); var time = Instant.ofEpochMilli(reviewedAt);
        if (card.getLastReview() != null && time.isBefore(card.getLastReview())) throw new ApiException(409, "CLOCK_MOVED_BACKWARDS", "系统时间早于上次复习，请核对时间后重试");
        var result = scheduler().reviewCard(card, rating, time);
        var next = result.card();
        if (next.getStability() == null || !Double.isFinite(next.getStability()) || next.getStability() <= 0
                || next.getDifficulty() == null || !Double.isFinite(next.getDifficulty()) || next.getDifficulty() < 1 || next.getDifficulty() > 10
                || !next.getDue().isAfter(time)) throw new ApiException(503, "SCHEDULING_FAILED", "复习计算失败，请重试");
        return Map.of("stateJson", next.toJson(), "dueAtMs", next.getDue().toEpochMilli(), "algorithmLog", result.reviewLog().toJson());
    }
}
