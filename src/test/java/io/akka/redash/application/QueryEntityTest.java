package io.akka.redash.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.redash.domain.QueryHash;
import io.akka.redash.domain.Schedule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SPEC-001 R7, R12, R23, and the cache key from R9. */
class QueryEntityTest {

  private static EventSourcedTestKit<QueryEntity.State, QueryEntity.Event, QueryEntity> saved(Schedule schedule) {
    var kit = EventSourcedTestKit.of("q1", QueryEntity::new);
    kit.method(QueryEntity::save).invoke(new QueryEntity.Save("SELECT 1", "ds-1", schedule));
    return kit;
  }

  @Test
  void aSavedQueryCarriesTheHashOfItsTextAndTheCacheKeyOfThePair() {
    var kit = saved(Schedule.every(3600));
    var state = kit.getState();
    assertEquals(QueryHash.of("SELECT 1"), state.queryHash());
    assertEquals(QueryHash.of("SELECT 1") + ":ds-1", state.cacheKey());
    assertTrue(state.created());
  }

  @Test
  void twoTextsThatHashTheSameShareACacheKey() {
    var one = EventSourcedTestKit.of("q1", QueryEntity::new);
    one.method(QueryEntity::save).invoke(new QueryEntity.Save("SELECT 42", "ds-1", Schedule.every(3600)));
    var two = EventSourcedTestKit.of("q2", QueryEntity::new);
    two.method(QueryEntity::save)
        .invoke(new QueryEntity.Save("/* note */SELECT   42", "ds-1", Schedule.every(3600)));
    assertEquals(one.getState().cacheKey(), two.getState().cacheKey());
  }

  // ---- R7: the counter, driven as the sequence fail, fail, succeed ----

  @Test
  void theFailureCounterRisesAndOneSuccessClearsIt() {
    var kit = saved(Schedule.every(3600));
    assertEquals(0, kit.getState().scheduleFailures());
    kit.method(QueryEntity::recordFailure).invoke("boom");
    assertEquals(1, kit.getState().scheduleFailures());
    kit.method(QueryEntity::recordFailure).invoke("boom again");
    assertEquals(2, kit.getState().scheduleFailures());
    kit.method(QueryEntity::recordSuccess)
        .invoke(new QueryEntity.RefreshSucceeded("result-1", Instant.parse("2026-08-23T12:00:00Z")));
    assertEquals(0, kit.getState().scheduleFailures());
  }

  @Test
  void aSuccessClearsTheWholeCountRatherThanDecrementingIt() {
    var kit = saved(Schedule.every(3600));
    for (int i = 0; i < 5; i++) {
      kit.method(QueryEntity::recordFailure).invoke("boom");
    }
    assertEquals(5, kit.getState().scheduleFailures());
    kit.method(QueryEntity::recordSuccess)
        .invoke(new QueryEntity.RefreshSucceeded("result-1", Instant.parse("2026-08-23T12:00:00Z")));
    assertEquals(0, kit.getState().scheduleFailures());
  }

  @Test
  void aSuccessPointsTheQueryAtTheNewResult() {
    var kit = saved(Schedule.every(3600));
    var at = Instant.parse("2026-08-23T12:00:00Z");
    kit.method(QueryEntity::recordSuccess).invoke(new QueryEntity.RefreshSucceeded("result-1", at));
    assertEquals("result-1", kit.getState().latestResultId());
    assertEquals(at, kit.getState().latestResultAt());
  }

  // ---- R12: the schedule the sweep writes back, and what it leaves ----

  @Test
  void anUnreadableScheduleIsWrittenBackDisabledRatherThanErased() {
    var kit = saved(Schedule.unreadable("not-a-number"));
    kit.method(QueryEntity::disableScheduleByError).invoke("the schedule could not be read");
    var schedule = kit.getState().schedule();
    assertNotNull(schedule, "the schedule is disabled, not removed");
    assertTrue(schedule.isDisabled());
    assertEquals("not-a-number", schedule.intervalRaw(), "the value that could not be read is kept");
  }

  @Test
  void disablingAnAlreadyDisabledScheduleWritesNothing() {
    var kit = saved(Schedule.every(3600).withDisabled(true));
    var before = kit.getAllEvents().size();
    kit.method(QueryEntity::disableScheduleByError).invoke("again");
    assertEquals(before, kit.getAllEvents().size());
  }

  @Test
  void disablingAQueryWithNoScheduleWritesNothing() {
    var kit = saved(null);
    var before = kit.getAllEvents().size();
    kit.method(QueryEntity::disableScheduleByError).invoke("nothing to disable");
    assertEquals(before, kit.getAllEvents().size());
  }

  // ---- R23: erasing is a different thing from disabling ----

  @Test
  void erasingRemovesTheScheduleOutright() {
    var kit = saved(Schedule.every(3600).withUntil("2026-08-20"));
    kit.method(QueryEntity::eraseSchedule).invoke();
    assertNull(kit.getState().schedule());
  }

  @Test
  void erasingAQueryWithNoScheduleWritesNothing() {
    var kit = saved(null);
    var before = kit.getAllEvents().size();
    kit.method(QueryEntity::eraseSchedule).invoke();
    assertEquals(before, kit.getAllEvents().size());
  }

  @Test
  void archivingLeavesTheScheduleAndTheResultAlone() {
    var kit = saved(Schedule.every(3600));
    kit.method(QueryEntity::recordSuccess)
        .invoke(new QueryEntity.RefreshSucceeded("result-1", Instant.parse("2026-08-23T12:00:00Z")));
    kit.method(QueryEntity::archive).invoke();
    assertTrue(kit.getState().archived());
    assertNotNull(kit.getState().schedule());
    assertEquals("result-1", kit.getState().latestResultId());
  }
}
