package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.redash.domain.RefreshSelection.Candidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R10 to R14, against the eleven selection rows and the five tracker steps the
 * original produced (`probes/probe_03.py`).
 */
class RefreshSelectionTest {

  private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
  private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(2));

  private static Candidate candidate(String id, Schedule schedule, Instant latestResultAt) {
    return new Candidate(id, "hash-" + id + ":ds-1", schedule, 0, latestResultAt, true);
  }

  private static RefreshSelection.Plan plan(Candidate... candidates) {
    return RefreshSelection.plan(List.of(candidates), Map.of(), NOW);
  }

  // ---- R10: which queries a sweep passes over, and what it leaves them as ----

  @Test
  void aQueryThatHasNeverRunIsEnqueued() {
    assertEquals(List.of("q1"), plan(candidate("q1", Schedule.every(3600), null)).enqueue());
  }

  @Test
  void aQueryWhoseResultIsStaleIsEnqueued() {
    assertEquals(List.of("q1"), plan(candidate("q1", Schedule.every(3600), LONG_AGO)).enqueue());
  }

  @Test
  void aQueryWhoseResultIsFreshIsPassedOver() {
    assertTrue(plan(candidate("q1", Schedule.every(3600), NOW.minusSeconds(30))).enqueue().isEmpty());
  }

  @Test
  void aQueryWithNoScheduleIsPassedOver() {
    assertTrue(plan(candidate("q1", null, LONG_AGO)).enqueue().isEmpty());
  }

  @Test
  void aDisabledScheduleIsPassedOver() {
    assertTrue(
        plan(candidate("q1", Schedule.every(3600).withDisabled(true), LONG_AGO)).enqueue().isEmpty());
  }

  @Test
  void anUntilAtOrBeforeNowIsPassedOver() {
    assertTrue(
        plan(candidate("q1", Schedule.every(3600).withUntil("2026-08-22"), LONG_AGO)).enqueue().isEmpty());
  }

  @Test
  void anUntilStillAheadIsEnqueued() {
    assertEquals(
        List.of("q1"),
        plan(candidate("q1", Schedule.every(3600).withUntil("2026-08-24"), LONG_AGO)).enqueue());
  }

  @Test
  void aScheduleWithEveryValueAbsentIsPassedOverAndNotRewritten() {
    var plan = plan(candidate("q1", Schedule.allNull(), LONG_AGO));
    assertTrue(plan.enqueue().isEmpty());
    assertTrue(plan.disableByError().isEmpty(), "an empty schedule is a skip, not an error");
  }

  // ---- R12: the two shapes that are an error rather than a skip ----

  @Test
  void aScheduleTheArithmeticCannotReadIsWrittenBackDisabled() {
    var plan = plan(candidate("q1", Schedule.unreadable("not-a-number"), LONG_AGO));
    assertTrue(plan.enqueue().isEmpty());
    assertEquals(List.of("q1"), plan.disableByError());
  }

  @Test
  void aScheduleCarryingOnlyDisabledFalseIsAlsoAnError() {
    // `disabled: false` is a value, so the schedule is not "every value absent" and the
    // arithmetic is asked for an interval that is not there.
    var plan = plan(candidate("q1", Schedule.unreadable(null), LONG_AGO));
    assertEquals(List.of("q1"), plan.disableByError());
  }

  @Test
  void anUnreadableUntilIsAnErrorRatherThanASkip() {
    var plan = plan(candidate("q1", Schedule.every(3600).withUntil("the-first-of-never"), LONG_AGO));
    assertEquals(List.of("q1"), plan.disableByError());
  }

  // ---- R13: one refresh per cache key, and which query keeps it ----

  @Test
  void twoQueriesSharingACacheKeyProduceOneRefreshAndTheLaterOneKeepsIt() {
    var earlier = new Candidate("q1", "shared:ds-1", Schedule.every(3600), 0, LONG_AGO, true);
    var later = new Candidate("q2", "shared:ds-1", Schedule.every(3600), 0, LONG_AGO, true);
    var plan = RefreshSelection.plan(List.of(earlier, later), Map.of(), NOW);
    assertEquals(List.of("q2"), plan.enqueue());
    var supersededRow =
        plan.considered().stream().filter(c -> c.queryId().equals("q1")).findFirst().orElseThrow();
    assertEquals("q2", supersededRow.supersededBy());
    assertFalse(supersededRow.enqueued());
  }

  @Test
  void theSameTextOnTwoDataSourcesIsTwoRefreshes() {
    var one = new Candidate("q1", "hash:ds-1", Schedule.every(3600), 0, LONG_AGO, true);
    var two = new Candidate("q2", "hash:ds-2", Schedule.every(3600), 0, LONG_AGO, true);
    assertEquals(List.of("q1", "q2"), RefreshSelection.plan(List.of(one, two), Map.of(), NOW).enqueue());
  }

  // ---- R11: the filter that is not about the schedule at all ----

  @Test
  void aQueryThatIsNotAllowedToRefreshIsPassedOverEvenWhenDue() {
    var blocked = new Candidate("q1", "hash:ds-1", Schedule.every(3600), 0, LONG_AGO, false);
    assertTrue(RefreshSelection.plan(List.of(blocked), Map.of(), NOW).enqueue().isEmpty());
  }

  // ---- R14: the execution tracker, driven as the original's five-step sequence ----

  @Test
  void theTrackerIsConsultedBeforeTheStoredResult() {
    var query = candidate("q1", Schedule.every(3600), LONG_AGO);

    // 1. a stale result and no tracker entry: due
    assertEquals(List.of("q1"), RefreshSelection.plan(List.of(query), Map.of(), NOW).enqueue());

    // 2. an entry written the moment execution started, before any result exists: not due
    var justStarted = Map.of("q1", NOW.toEpochMilli());
    assertTrue(RefreshSelection.plan(List.of(query), justStarted, NOW).enqueue().isEmpty());

    // 3. an entry older than the interval stops suppressing
    var staleEntry = Map.of("q1", NOW.minus(Duration.ofDays(3)).toEpochMilli());
    assertEquals(List.of("q1"), RefreshSelection.plan(List.of(query), staleEntry, NOW).enqueue());

    // 4. a fresh result does not rescue a stale entry - the entry is read first
    var freshResult = candidate("q1", Schedule.every(3600), NOW.minusSeconds(1));
    assertEquals(List.of("q1"), RefreshSelection.plan(List.of(freshResult), staleEntry, NOW).enqueue());

    // 5. remove the entry and the fresh result is consulted again
    assertTrue(RefreshSelection.plan(List.of(freshResult), Map.of(), NOW).enqueue().isEmpty());
  }

  @Test
  void everyQueryLookedAtIsReportedWithAReason() {
    var plan =
        plan(
            candidate("q1", Schedule.every(3600), LONG_AGO),
            candidate("q2", null, LONG_AGO),
            candidate("q3", Schedule.every(3600).withDisabled(true), LONG_AGO),
            candidate("q4", Schedule.unreadable("x"), LONG_AGO));
    assertEquals(4, plan.considered().size());
    assertEquals(ScheduleDecision.Outcome.DUE, outcomeOf(plan, "q1"));
    assertEquals(ScheduleDecision.Outcome.NO_SCHEDULE, outcomeOf(plan, "q2"));
    assertEquals(ScheduleDecision.Outcome.DISABLED, outcomeOf(plan, "q3"));
    assertEquals(ScheduleDecision.Outcome.UNREADABLE, outcomeOf(plan, "q4"));
  }

  private static ScheduleDecision.Outcome outcomeOf(RefreshSelection.Plan plan, String queryId) {
    return plan.considered().stream()
        .filter(c -> c.queryId().equals(queryId))
        .findFirst()
        .orElseThrow()
        .outcome();
  }
}
