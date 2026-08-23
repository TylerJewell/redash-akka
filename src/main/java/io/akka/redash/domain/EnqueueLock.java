package io.akka.redash.domain;

/**
 * Whether a request to refresh a cache key that is already on its way should enqueue
 * anything (SPEC-001 R17).
 *
 * <p>The outcome depends on the state of the job the lock names, and — this is the part a
 * two-call sequence cannot show — on whether the previous call left a lock behind at all.
 * When a lock is found to name a job that is no longer in flight, the replacement is
 * enqueued and <em>no lock is written for it</em>, so the call after that enqueues again.
 *
 * <p>That is reproduced rather than repaired. It is behaviour the original has settled, not
 * behaviour it lacks (SPEC-001 D-3), and the original runs a separate periodic sweep for
 * locks that outlive their jobs, which is what lock hygiene by sweeping rather than by
 * invariant looks like.
 */
public final class EnqueueLock {

  /** Every state the job named by an existing lock can be in. */
  public enum JobState {
    QUEUED,
    STARTED,
    FINISHED,
    FAILED,
    CANCELLED,
    /** The job store no longer has it. */
    GONE
  }

  /**
   * @param enqueue whether a job is created
   * @param writeLock whether a lock is written naming that job — false on exactly the path
   *     where a stale lock was cleared first
   * @param clearedStaleLock whether the lock that was there is removed
   */
  public record Outcome(boolean enqueue, boolean writeLock, boolean clearedStaleLock) {}

  private EnqueueLock() {}

  /**
   * @param heldJob the job the current lock names, or null when no lock is held
   */
  public static Outcome decide(JobState heldJob) {
    if (heldJob == null) {
      return new Outcome(true, true, false);
    }
    if (heldJob == JobState.QUEUED || heldJob == JobState.STARTED) {
      return new Outcome(false, false, false);
    }
    // The stale lock is cleared and the replacement enqueued on two passes of the same
    // retry loop, and the job created on the first pass survives into the second - so the
    // branch that would write the new lock is skipped.
    return new Outcome(true, false, true);
  }
}
