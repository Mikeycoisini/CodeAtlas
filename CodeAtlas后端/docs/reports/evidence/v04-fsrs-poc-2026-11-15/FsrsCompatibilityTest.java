package dev.codeatlas.poc;

import io.github.openspacedrepetition.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Throwaway compatibility spike, separate from the product and its database. */
class FsrsCompatibilityTest {
    private static final Instant START = Instant.parse("2026-11-15T00:00:00Z");
    private Scheduler scheduler() { return Scheduler.builder().enableFuzzing(false).build(); }
    private Card card(Instant time) { return Card.builder().cardId(0).due(time).build(); }

    @Test void matchesPinnedUpstreamGoldenIntervals() {
        // Golden sequence from java-fsrs FSRSTest.testReviewCard, MIT, commit c561ee6c56239b621a0a45b16a6c2e688441eae5.
        var grades = List.of(Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD,
                Rating.AGAIN, Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD);
        var expected = List.of(0L, 4L, 14L, 45L, 135L, 372L, 0L, 0L, 2L, 5L, 10L, 20L, 40L);
        var actual = new ArrayList<Long>(); var scheduler = scheduler(); var card = card(START); Instant now = START;
        for (var grade : grades) {
            card = scheduler.reviewCard(card, grade, now).card();
            actual.add(ChronoUnit.DAYS.between(now, card.getDue())); now = card.getDue();
        }
        assertThat(actual).isEqualTo(expected);
        System.out.println("Golden day intervals " + actual);
    }

    @Test void exposesFourExplicitGradesAndLearningSteps() {
        var scheduler = scheduler(); var card = card(START);
        assertThat(scheduler.reviewCard(card, Rating.AGAIN, START).card().getDue()).isEqualTo(START.plusSeconds(60));
        assertThat(scheduler.reviewCard(card, Rating.HARD, START).card().getDue()).isEqualTo(START.plusSeconds(330));
        assertThat(scheduler.reviewCard(card, Rating.GOOD, START).card().getDue()).isEqualTo(START.plusSeconds(600));
        assertThat(scheduler.reviewCard(card, Rating.EASY, START).card().getState()).isEqualTo(State.REVIEW);
        assertThat(scheduler.reviewCard(card, Rating.EASY, START).card().getDue()).isAfter(START.plus(Duration.ofDays(1)));
    }

    @Test void roundTripsSchedulerStateAndLogWithManagedJackson() {
        var scheduler = scheduler(); var config = scheduler.toJson();
        var restored = Scheduler.fromJson(config); var first = card(START);
        for (int index = 0; index < 30; index++) {
            var grade = List.of(Rating.GOOD, Rating.EASY, Rating.HARD, Rating.AGAIN).get(index % 4);
            var output = scheduler.reviewCard(first, grade, first.getDue());
            var replay = restored.reviewCard(Card.fromJson(first.toJson()), grade, first.getDue());
            assertThat(output.card().toJson()).isEqualTo(replay.card().toJson());
            assertThat(ReviewLog.fromJson(output.reviewLog().toJson())).isEqualTo(output.reviewLog());
            first = Card.fromJson(output.card().toJson());
            assertThat(first.getStability()).isFinite().isPositive();
            assertThat(first.getDifficulty()).isBetween(1.0, 10.0);
        }
        assertThat(config).contains("\"enableFuzzing\":false");
        System.out.println("Managed Jackson " + com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION);
    }

    @Test void usesInstantsAcrossTimezonesDstAndLeapDay() {
        for (String timestamp : List.of("2026-03-08T06:59:59Z", "2026-11-01T05:59:59Z", "2024-02-29T23:59:59Z")) {
            Instant utc = Instant.parse(timestamp);
            Instant china = utc.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            Instant newYork = utc.atZone(ZoneId.of("America/New_York")).toInstant();
            var scheduler = scheduler(); var input = card(utc);
            assertThat(scheduler.reviewCard(input, Rating.EASY, china).card().toJson()).isEqualTo(scheduler.reviewCard(input, Rating.EASY, newYork).card().toJson());
            assertThat(input.getLastReview()).isNull();
        }
    }

    @Test void parallelAndRetriedPureComputationsDoNotMutateInputs() throws Exception {
        var input = card(START); var original = input.toJson();
        var expected = scheduler().reviewCard(input, Rating.GOOD, START).card().toJson();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new ArrayList<Future<String>>();
            for (int index = 0; index < 64; index++) jobs.add(threads.submit(() -> scheduler().reviewCard(Card.fromJson(original), Rating.GOOD, START).card().toJson()));
            for (var job : jobs) assertThat(job.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
        }
        assertThat(input.toJson()).isEqualTo(original);
    }

    @Test void outputDependsOnExplicitClockAndConfiguration() {
        var input = scheduler().reviewCard(card(START), Rating.EASY, START).card();
        var late = START.plus(Duration.ofDays(20));
        var first = scheduler().reviewCard(input, Rating.GOOD, late);
        var replay = Scheduler.fromJson(scheduler().toJson()).reviewCard(Card.fromJson(input.toJson()), Rating.GOOD, late);
        assertThat(first.card().toJson()).isEqualTo(replay.card().toJson());
        assertThat(first.reviewLog().reviewDatetime()).isEqualTo(late);
        assertThat(first.card().getDue()).isAfter(late);
    }
}
