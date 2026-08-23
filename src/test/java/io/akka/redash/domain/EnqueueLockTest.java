package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.redash.domain.EnqueueLock.JobState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R17, driven as the three-call sequence the original was driven as
 * (`probes/probe_04.py` lock family). The rows the original produced were:
 *
 * <pre>
 *   queued              1, 1, 1 jobs   lock held after all three
 *   started             1, 1, 1        lock held after all three
 *   finished            1, 2, 3        lock held, gone, held
 *   failed              1, 2, 3        lock held, gone, held
 *   cancelled           1, 2, 3        lock held, gone, held
 *   gone-from-the-store 1, 2, 3        lock held, gone, held
 * </pre>
 *
 * The middle column of the second half is the whole point: a two-call sequence agrees with
 * a rebuild that writes the replacement lock, and disagrees on the third call.
 */
class EnqueueLockTest {

  /** One caller's view of the lock across a sequence of requests. */
  private static final class Sequence {
    private boolean lockHeld;
    private final JobState whatTheHeldJobIsDoing;
    private int jobsCreated;
    private final List<Boolean> lockAfterEachCall = new ArrayList<>();

    Sequence(JobState whatTheHeldJobIsDoing) {
      this.whatTheHeldJobIsDoing = whatTheHeldJobIsDoing;
    }

    void request() {
      var outcome = EnqueueLock.decide(lockHeld ? whatTheHeldJobIsDoing : null);
      if (outcome.clearedStaleLock()) {
        lockHeld = false;
      }
      if (outcome.enqueue()) {
        jobsCreated++;
      }
      if (outcome.writeLock()) {
        lockHeld = true;
      }
      lockAfterEachCall.add(lockHeld);
    }
  }

  private static Sequence threeCalls(JobState state) {
    var s = new Sequence(state);
    s.request();
    s.request();
    s.request();
    return s;
  }

  @Test
  void aJobStillOnItsWayStopsEveryLaterRequest() {
    for (JobState state : List.of(JobState.QUEUED, JobState.STARTED)) {
      var s = threeCalls(state);
      assertEquals(1, s.jobsCreated, state.name());
      assertEquals(List.of(true, true, true), s.lockAfterEachCall, state.name());
    }
  }

  @Test
  void aJobNoLongerOnItsWayIsReplacedAndTheReplacementIsNotLocked() {
    for (JobState state :
        List.of(JobState.FINISHED, JobState.FAILED, JobState.CANCELLED, JobState.GONE)) {
      var s = threeCalls(state);
      assertEquals(3, s.jobsCreated, state.name());
      // held after the first, gone after the second, held again after the third
      assertEquals(List.of(true, false, true), s.lockAfterEachCall, state.name());
    }
  }

  @Test
  void aFirstRequestAlwaysEnqueuesAndAlwaysLocks() {
    var outcome = EnqueueLock.decide(null);
    assertTrue(outcome.enqueue());
    assertTrue(outcome.writeLock());
    assertFalse(outcome.clearedStaleLock());
  }

  @Test
  void everyJobStateIsAccountedFor() {
    for (JobState state : JobState.values()) {
      var outcome = EnqueueLock.decide(state);
      boolean inFlight = state == JobState.QUEUED || state == JobState.STARTED;
      assertEquals(!inFlight, outcome.enqueue(), state.name());
      assertFalse(outcome.writeLock(), state.name());
      assertEquals(!inFlight, outcome.clearedStaleLock(), state.name());
    }
  }
}
