package io.akka.redash.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of the scheduled queries a sweep enqueues, and what it does to the ones it does not
 * (SPEC-001 R10 to R14).
 *
 * <p>Three things here decide the answer and only one of them is the schedule.
 *
 * <p>The instant a query "last ran" comes from the execution tracker when the tracker has an
 * entry, and only otherwise from the stored result. A stale tracker entry therefore beats a
 * fresh result, which is what lets a run that started and never finished eventually be
 * retried.
 *
 * <p>Two due queries sharing a cache key produce one refresh, and the walk being ordered by
 * id means the later one is the one kept.
 *
 * <p>A schedule the arithmetic cannot read is not a skip. It is written back switched off,
 * which is a side effect of a read, and the caller is told to do it.
 */
public final class RefreshSelection {

  /** One query the sweep looked at, and what it decided. */
  public record Considered(
      String queryId,
      String cacheKey,
      ScheduleDecision.Outcome outcome,
      boolean enqueued,
      /** Set when the query lost its place to a later query with the same cache key. */
      String supersededBy) {}

  /** What the sweep decided overall. */
  public record Plan(List<String> enqueue, List<String> disableByError, List<Considered> considered) {}

  /** Everything about one query the sweep needs, gathered by the caller. */
  public record Candidate(
      String queryId,
      String cacheKey,
      Schedule schedule,
      int scheduleFailures,
      Instant latestResultAt,
      /** Whether the query may refresh at all — organisation, data source, global flag. */
      boolean allowedToRefresh) {}

  private RefreshSelection() {}

  /**
   * @param candidates in the order the sweep walks them, which the original fixes as
   *     ascending query id — the order decides which of two queries sharing a cache key
   *     survives, so it is an input rather than a detail
   * @param startedAtMillis the execution tracker, consulted before the stored result
   */
  public static Plan plan(
      List<Candidate> candidates, Map<String, Long> startedAtMillis, Instant now) {

    var byCacheKey = new LinkedHashMap<String, String>();
    var disableByError = new ArrayList<String>();
    var considered = new ArrayList<Considered>();
    var supersededOf = new LinkedHashMap<String, String>();

    for (Candidate candidate : candidates) {
      Instant lastRun = lastRunOf(candidate, startedAtMillis);
      var decision =
          ScheduleDecision.due(candidate.schedule(), lastRun, now, candidate.scheduleFailures());

      if (decision.outcome() == ScheduleDecision.Outcome.UNREADABLE) {
        disableByError.add(candidate.queryId());
        considered.add(new Considered(candidate.queryId(), candidate.cacheKey(), decision.outcome(), false, null));
        continue;
      }

      if (!decision.isDue() || !candidate.allowedToRefresh()) {
        considered.add(new Considered(candidate.queryId(), candidate.cacheKey(), decision.outcome(), false, null));
        continue;
      }

      var previous = byCacheKey.put(candidate.cacheKey(), candidate.queryId());
      if (previous != null) {
        supersededOf.put(previous, candidate.queryId());
      }
      considered.add(new Considered(candidate.queryId(), candidate.cacheKey(), decision.outcome(), true, null));
    }

    var enqueue = List.copyOf(byCacheKey.values());
    var finalConsidered = new ArrayList<Considered>(considered.size());
    for (Considered c : considered) {
      var supersededBy = supersededOf.get(c.queryId());
      finalConsidered.add(
          supersededBy == null
              ? c
              : new Considered(c.queryId(), c.cacheKey(), c.outcome(), false, supersededBy));
    }

    return new Plan(enqueue, List.copyOf(disableByError), List.copyOf(finalConsidered));
  }

  private static Instant lastRunOf(Candidate candidate, Map<String, Long> startedAtMillis) {
    var tracked = startedAtMillis.get(candidate.queryId());
    if (tracked != null) {
      return Instant.ofEpochMilli(tracked);
    }
    return candidate.latestResultAt();
  }
}
