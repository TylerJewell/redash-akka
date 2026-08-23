package io.akka.redash.bench;

import io.akka.redash.domain.AlertCondition;
import io.akka.redash.domain.AlertNotification;
import io.akka.redash.domain.EnqueueLock;
import io.akka.redash.domain.QueryHash;
import io.akka.redash.domain.RefreshSelection;
import io.akka.redash.domain.Schedule;
import io.akka.redash.domain.ScheduleDecision;
import io.akka.redash.domain.StoredResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Every workload in `bench/workloads.json`, put to the rebuild, with the answers written
 * where the comparison can read them.
 *
 * <pre>
 *   mvn -q exec:java -Dexec.mainClass=io.akka.redash.bench.BenchmarkRunner \
 *       -Dexec.classpathScope=test -Dexec.args="&lt;offsetSeconds&gt; &lt;out.json&gt;"
 * </pre>
 *
 * <p>The offset is an argument for the same reason it is one on the source side: a workload
 * naming an instant has a second input nobody declared — where it sits relative to now —
 * and the only way to find out whether the comparison is about that is to run the whole set
 * twice at two offsets and require the same verdict.
 *
 * <p>Timing is a window total divided by what was in it, and the median of five windows.
 * A single window is one reading, and on a warmed JVM the spread between readings can be
 * larger than the gap a table exists to show.
 */
public final class BenchmarkRunner {

  private static final Instant BASE = Instant.parse("2026-08-23T12:00:00Z");

  private static final List<String> OPERATORS =
      List.of(">", ">=", "<", "<=", "==", "!=", "greater than", "less than", "equals");

  private static final List<Map.Entry<String, Object>> VALUE_KINDS =
      List.of(
          Map.entry("whole-number", (Object) 10),
          Map.entry("float", (Object) 10.5),
          Map.entry("numeric-string", (Object) "10"),
          Map.entry("non-numeric-string", (Object) "alpha"),
          Map.entry("boolean-true", (Object) Boolean.TRUE),
          Map.entry("boolean-false", (Object) Boolean.FALSE));

  public static void main(String[] args) throws IOException {
    long offset = Long.parseLong(args[0]);
    Path out = Path.of(args[1]);
    Instant now = BASE.plusSeconds(offset);

    var answers = new LinkedHashMap<String, Object>();

    answers.put("sequence-alert-states-over-a-run-of-results", alertStateSequence(now));
    answers.put("sequence-rearm-repeats-only-past-the-window", rearmSequence(now, 60, List.of(0L, 30L, 121L)));
    answers.put("sequence-rearm-of-zero-never-repeats", rearmSequence(now, 0, List.of(0L, 3600L, 7200L)));
    answers.put("sequence-unknown-to-ok-is-silent-then-real", unknownToOkSequence(now));
    answers.put("sequence-failure-counter-pushes-the-boundary-out", failureCounterSequence(now));
    answers.put("sequence-tracker-suppresses-then-releases", trackerSequence(now));
    answers.put("segmentation-six-queries-one-sweep", segmentation(now, List.of(List.of(0, 1, 2, 3, 4, 5))));
    answers.put(
        "segmentation-six-queries-three-sweeps",
        segmentation(now, List.of(List.of(0, 1), List.of(2, 3), List.of(4, 5))));
    answers.put("table-cache-key-dedup", cacheKeyDedup(now));
    answers.put("table-schedule-decisions", scheduleDecisions());
    answers.put("table-query-hash", queryHashes());
    answers.put("table-alert-evaluation", alertEvaluations());
    answers.put("table-freshness-and-fanout", freshnessAndFanout(now));
    answers.put("table-in-flight-lock", inFlightLock());
    answers.put("table-notification-and-subscriptions", notificationAndSubscriptions(now));
    answers.put("table-erase-past-until", erasePastUntil(now));
    answers.put("table-refresh-filter", refreshFilter(now));

    var timings = new LinkedHashMap<String, Object>();
    Instant previous = BASE.minus(Duration.ofHours(2));
    // Every call is given an input that moves with the iteration. With a constant input the
    // JIT hoists the whole call out of the loop and the figure that comes back is the cost
    // of an empty loop: this measured 5 ns for date arithmetic returning a record, which is
    // the shape of a number that did not measure the thing it names.
    timings.put(
        "schedule-decision",
        timed(i -> ScheduleDecision.due(Schedule.every(3600), previous, now.plusNanos(i), 0)));
    timings.put("query-hash", timed(i -> QueryHash.of("SELECT " + i + " FROM t WHERE c='Value'")));
    var condition = new AlertCondition.Condition("value", ">", 5.0, AlertCondition.Selector.FIRST);
    // Eight results built once and rotated: a constant input is folded out of the loop,
    // and building one per call would put an allocation in the port's figure that the
    // source's does not have, since the source reads a result it already stored.
    var results = new ArrayList<AlertCondition.QueryResultData>();
    for (int value = 0; value < 8; value++) {
      results.add(
          new AlertCondition.QueryResultData(
              List.of(Map.<String, Object>of("value", value)), List.of("value")));
    }
    timings.put(
        "alert-evaluate", timed(i -> AlertCondition.evaluate(condition, results.get((int) (i & 7)))));

    var document = new LinkedHashMap<String, Object>();
    document.put("side", "port");
    document.put("offsetSeconds", offset);
    document.put("answers", answers);
    document.put("timings", timings);
    Files.writeString(out, Json.write(document), StandardCharsets.UTF_8);
    System.out.println("wrote " + out + ": " + answers.size() + " workloads");
  }

  // ------------------------------------------------------------------- sequences

  /** One alert put to five results in a row, recording the state and whether anybody was told. */
  private static List<Map<String, Object>> alertStateSequence(Instant now) {
    var alert = new AlertNotification.AlertState(AlertCondition.Verdict.UNKNOWN, null, null, false);
    var condition = new AlertCondition.Condition("value", ">", 5.0, AlertCondition.Selector.FIRST);
    var out = new ArrayList<Map<String, Object>>();
    var values = Arrays.asList(10, 11, 1, 12, null);
    for (int index = 0; index < values.size(); index++) {
      var verdict = AlertCondition.evaluate(condition, rowsOf(values.get(index)));
      var outcome = AlertNotification.apply(alert, verdict, now);
      out.add(step(index, outcome.state().name().toLowerCase() + "/notified=" + (outcome.notified() ? 1 : 0)));
      alert =
          new AlertNotification.AlertState(
              outcome.state(), outcome.lastTriggeredAt(), alert.rearmSeconds(), alert.muted());
    }
    return out;
  }

  private static List<Map<String, Object>> rearmSequence(Instant now, Integer rearm, List<Long> offsets) {
    var alert = new AlertNotification.AlertState(AlertCondition.Verdict.UNKNOWN, null, rearm, false);
    var condition = new AlertCondition.Condition("value", ">", 5.0, AlertCondition.Selector.FIRST);
    var out = new ArrayList<Map<String, Object>>();
    for (int index = 0; index < offsets.size(); index++) {
      var at = now.plusSeconds(offsets.get(index));
      var verdict = AlertCondition.evaluate(condition, rowsOf(10));
      var outcome = AlertNotification.apply(alert, verdict, at);
      out.add(step(index, outcome.state().name().toLowerCase() + "/notified=" + (outcome.notified() ? 1 : 0)));
      alert =
          new AlertNotification.AlertState(
              outcome.state(), outcome.lastTriggeredAt(), rearm, alert.muted());
    }
    return out;
  }

  private static List<Map<String, Object>> unknownToOkSequence(Instant now) {
    var alert = new AlertNotification.AlertState(AlertCondition.Verdict.UNKNOWN, null, null, false);
    var condition = new AlertCondition.Condition("value", ">", 5.0, AlertCondition.Selector.FIRST);
    var out = new ArrayList<Map<String, Object>>();
    var values = List.of(1, 10);
    for (int index = 0; index < values.size(); index++) {
      var verdict = AlertCondition.evaluate(condition, rowsOf(values.get(index)));
      var outcome = AlertNotification.apply(alert, verdict, now);
      out.add(step(index, outcome.state().name().toLowerCase() + "/notified=" + (outcome.notified() ? 1 : 0)));
      alert =
          new AlertNotification.AlertState(
              outcome.state(), outcome.lastTriggeredAt(), null, alert.muted());
    }
    return out;
  }

  /** Fail, fail, succeed — and the boundary each count produces, asked a minute past it. */
  private static List<Map<String, Object>> failureCounterSequence(Instant now) {
    var out = new ArrayList<Map<String, Object>>();
    int failures = 0;
    Instant previous = now.minusSeconds(3660);
    var succeeded = List.of(false, false, true);
    for (int index = 0; index < succeeded.size(); index++) {
      failures = succeeded.get(index) ? 0 : failures + 1;
      boolean due = ScheduleDecision.due(Schedule.every(3600), previous, now, failures).isDue();
      out.add(step(index, "failures=" + failures + ",due=" + bool(due)));
    }
    return out;
  }

  /** Four steps over the prior contents of the execution tracker. */
  private static List<Map<String, Object>> trackerSequence(Instant now) {
    var out = new ArrayList<Map<String, Object>>();
    long[][] steps = {{-1, 172800}, {0, 172800}, {259200, 172800}, {259200, 1}};
    for (int index = 0; index < steps.length; index++) {
      var tracker = new LinkedHashMap<String, Long>();
      if (steps[index][0] >= 0) {
        tracker.put("q", now.minusSeconds(steps[index][0]).toEpochMilli());
      }
      var candidate =
          new RefreshSelection.Candidate(
              "q", "hash:ds-1", Schedule.every(3600), 0, now.minusSeconds(steps[index][1]), true);
      var plan = RefreshSelection.plan(List.of(candidate), tracker, now);
      out.add(step(index, "due=" + bool(plan.enqueue().contains("q"))));
    }
    return out;
  }

  /**
   * The same six queries swept in one batch and in three. Two of them share a cache key and
   * land in different batches under the three-batch cutting, so the deduplication that
   * applies inside one sweep does not, and what stops the second refresh is the tracker
   * entry the first one wrote.
   */
  private static List<String> segmentation(Instant now, List<List<Integer>> batches) {
    var texts =
        List.of(
            "SELECT 1 AS value", "SELECT 2 AS value", "SELECT 3 AS value",
            "SELECT 4 AS value", "SELECT 5 AS value", "/* same */SELECT 1 AS value");
    var tracker = new LinkedHashMap<String, Long>();
    var refreshed = new ArrayList<List<Integer>>();
    for (List<Integer> batch : batches) {
      var candidates = new ArrayList<RefreshSelection.Candidate>();
      for (int index : batch) {
        candidates.add(
            new RefreshSelection.Candidate(
                "q" + index,
                QueryHash.cacheKey(QueryHash.of(texts.get(index)), "ds-1"),
                Schedule.every(3600),
                0,
                null,
                true));
      }
      var plan = RefreshSelection.plan(candidates, tracker, now);
      var picked = new ArrayList<Integer>();
      for (String queryId : plan.enqueue()) {
        int index = Integer.parseInt(queryId.substring(1));
        picked.add(index);
        tracker.put(queryId, now.toEpochMilli());
      }
      picked.sort(Integer::compareTo);
      refreshed.add(picked);
    }
    return List.of(refreshed.toString().replace("[[", "[[").replace("], [", "], ["));
  }

  /** Two queries sharing a cache key, created both ways round. */
  private static List<String> cacheKeyDedup(Instant now) {
    var texts = List.of("SELECT 42", "/* note */SELECT   42");
    var out = new ArrayList<String>();
    for (int[] order : new int[][] {{0, 1}, {1, 0}}) {
      var candidates = new ArrayList<RefreshSelection.Candidate>();
      var arrivedAs = new LinkedHashMap<String, String>();
      for (int position = 0; position < order.length; position++) {
        // The id ascends with creation, which is what the sweep walks in.
        var queryId = "q" + position;
        candidates.add(
            new RefreshSelection.Candidate(
                queryId,
                QueryHash.cacheKey(QueryHash.of(texts.get(order[position])), "ds-1"),
                Schedule.every(3600),
                0,
                null,
                true));
        arrivedAs.put(queryId, position == 0 ? "arrived-first" : "arrived-second");
      }
      var plan = RefreshSelection.plan(candidates, Map.of(), now);
      var survivors = new ArrayList<String>();
      for (String queryId : plan.enqueue()) {
        survivors.add(arrivedAs.get(queryId));
      }
      out.add(String.join(",", survivors));
    }
    return out;
  }

  // ---------------------------------------------------------------------- tables

  private static List<Map<String, String>> scheduleDecisions() {
    var rows = new ArrayList<Map<String, String>>();
    for (Object[] c : ScheduleCases.ALL) {
      var name = (String) c[0];
      var schedule = (Schedule) c[1];
      var previous = (Instant) c[2];
      var now = (Instant) c[3];
      var failures = (Integer) c[4];
      rows.add(row(name, bool(ScheduleDecision.due(schedule, previous, now, failures).isDue())));
    }
    return rows;
  }

  private static List<Map<String, String>> queryHashes() {
    var rows = new ArrayList<Map<String, String>>();
    for (Object[] c : HashCases.ALL) {
      rows.add(row((String) c[0], QueryHash.of((String) c[1])));
    }
    return rows;
  }

  private static List<Map<String, String>> alertEvaluations() {
    var rows = new ArrayList<Map<String, String>>();
    for (var kind : VALUE_KINDS) {
      for (String op : OPERATORS) {
        rows.add(
            row(
                "value-kind/" + kind.getKey() + "/" + op,
                evaluate(rowsOf(kind.getValue()), new AlertCondition.Condition("value", op, 5, AlertCondition.Selector.FIRST))));
      }
    }
    for (String op : OPERATORS) {
      rows.add(
          row(
              "value-kind/null/" + op,
              evaluate(rowsOf(null), new AlertCondition.Condition("value", op, 5, AlertCondition.Selector.FIRST))));
    }
    for (var kind : VALUE_KINDS) {
      rows.add(
          row(
              "string-threshold/" + kind.getKey(),
              evaluate(rowsOf(kind.getValue()), new AlertCondition.Condition("value", "==", "alpha", AlertCondition.Selector.FIRST))));
    }
    rows.add(
        row(
            "string-threshold/null",
            evaluate(rowsOf(null), new AlertCondition.Condition("value", "==", "alpha", AlertCondition.Selector.FIRST))));
    for (String op : OPERATORS) {
      rows.add(
          row(
              "both-strings/" + op,
              evaluate(rowsOf("alpha"), new AlertCondition.Condition("value", op, "beta", AlertCondition.Selector.FIRST))));
    }
    var multi =
        new AlertCondition.QueryResultData(
            List.of(
                Map.<String, Object>of("value", 3),
                Map.<String, Object>of("value", 9),
                Map.<String, Object>of("value", 1)),
            List.of("value"));
    for (String selector : List.of("absent", "first", "min", "max")) {
      var parsed = AlertCondition.Selector.parse(selector.equals("absent") ? null : selector);
      rows.add(row("selector/" + selector, evaluate(multi, new AlertCondition.Condition("value", ">", 5, parsed))));
    }
    var nonNumeric =
        new AlertCondition.QueryResultData(
            List.of(Map.<String, Object>of("value", "alpha"), Map.<String, Object>of("value", "beta")),
            List.of("value"));
    for (String selector : List.of("min", "max")) {
      rows.add(
          row(
              "selector/" + selector + "/non-numeric",
              evaluate(nonNumeric, new AlertCondition.Condition("value", ">", 5, AlertCondition.Selector.parse(selector)))));
    }
    rows.add(row("shape/no-result-row", evaluate(rowsOf(null), condition())));
    rows.add(
        row(
            "shape/empty-rows",
            evaluate(new AlertCondition.QueryResultData(List.of(), List.of("value")), condition())));
    rows.add(
        row(
            "shape/missing-column",
            evaluate(
                new AlertCondition.QueryResultData(
                    List.of(Map.<String, Object>of("other", 10)), List.of("value")),
                condition())));
    rows.add(row("shape/no-result-at-all", evaluate(null, condition())));
    return rows;
  }

  private static AlertCondition.Condition condition() {
    return new AlertCondition.Condition("value", ">", 5, AlertCondition.Selector.FIRST);
  }

  /** The port's own name for each outcome, mapped to the word the original printed. */
  private static String evaluate(AlertCondition.QueryResultData data, AlertCondition.Condition condition) {
    return switch (AlertCondition.evaluate(condition, data)) {
      case TRIGGERED -> "triggered";
      case OK -> "ok";
      case UNKNOWN -> "unknown";
      // The original reaches no state at all here: the comparison raises and nothing
      // catches it. The port names that outcome instead of inventing one.
      case UNEVALUATED -> "raises:TypeError";
    };
  }

  private static List<Map<String, String>> freshnessAndFanout(Instant now) {
    var rows = new ArrayList<Map<String, String>>();
    var stored =
        new StoredResult(
            "hash", "ds-1", "SELECT 5 AS value", List.of(), List.of(), 1.0, now.minusSeconds(120));
    for (var pair : List.of(Map.entry("0", 0L), Map.entry("60", 60L), Map.entry("300", 300L), Map.entry("-1", -1L))) {
      rows.add(row("freshness/max_age=" + pair.getKey(), bool(stored.isFreshEnough(pair.getValue(), now))));
    }

    var text = "SELECT 99 AS value";
    var cacheKey = QueryHash.cacheKey(QueryHash.of(text), "ds-1");
    record Q(String label, String text, String dataSourceId, boolean archived) {}
    var queries =
        List.of(
            new Q("matching-query", text, "ds-1", false),
            new Q("same-hash-comment-variant", "/* c */SELECT 99 AS value", "ds-1", false),
            new Q("archived-query", text, "ds-1", true),
            new Q("other-data-source", text, "ds-2", false),
            new Q("different-text", "SELECT 100 AS value", "ds-1", false));
    for (Q q : queries) {
      boolean belongs =
          !q.archived() && QueryHash.cacheKey(QueryHash.of(q.text()), q.dataSourceId()).equals(cacheKey);
      rows.add(row("fanout/" + q.label(), bool(belongs)));
    }
    return rows;
  }

  /** Six job states, each across the three calls it takes to see the third one. */
  private static List<Map<String, String>> inFlightLock() {
    var rows = new ArrayList<Map<String, String>>();
    for (var pair :
        List.of(
            Map.entry("queued", EnqueueLock.JobState.QUEUED),
            Map.entry("started", EnqueueLock.JobState.STARTED),
            Map.entry("finished", EnqueueLock.JobState.FINISHED),
            Map.entry("failed", EnqueueLock.JobState.FAILED),
            Map.entry("cancelled", EnqueueLock.JobState.CANCELLED),
            Map.entry("gone-from-the-store", EnqueueLock.JobState.GONE))) {
      boolean held = false;
      int jobs = 0;
      var jobsAfter = new ArrayList<Integer>();
      var locksAfter = new ArrayList<String>();
      for (int call = 0; call < 3; call++) {
        var outcome = EnqueueLock.decide(held ? pair.getValue() : null);
        if (outcome.clearedStaleLock()) {
          held = false;
        }
        if (outcome.enqueue()) {
          jobs++;
        }
        if (outcome.writeLock()) {
          held = true;
        }
        jobsAfter.add(jobs);
        locksAfter.add(held ? "True" : "False");
      }
      rows.add(
          row(
              "lock/" + pair.getKey(),
              "jobs=%d,%d,%d locks=%s,%s,%s"
                  .formatted(
                      jobsAfter.get(0), jobsAfter.get(1), jobsAfter.get(2),
                      locksAfter.get(0), locksAfter.get(1), locksAfter.get(2))));
    }
    return rows;
  }

  private static List<Map<String, String>> notificationAndSubscriptions(Instant now) {
    var rows = new ArrayList<Map<String, String>>();
    var states =
        List.of(AlertCondition.Verdict.UNKNOWN, AlertCondition.Verdict.OK, AlertCondition.Verdict.TRIGGERED);
    record Rearm(String label, Integer seconds, Long lastAgo) {}
    var rearms =
        List.of(
            new Rearm("no-rearm", null, null),
            new Rearm("rearm/never-triggered", 3600, null),
            new Rearm("rearm/inside-window", 3600, 60L),
            new Rearm("rearm/on-the-boundary", 3600, 3600L),
            new Rearm("rearm/outside-window", 3600, 3601L),
            new Rearm("rearm=0/triggered-long-ago", 0, 3600L));
    for (var prior : states) {
      for (var next : states) {
        for (Rearm rearm : rearms) {
          var alert =
              new AlertNotification.AlertState(
                  prior,
                  rearm.lastAgo() == null ? null : now.minusSeconds(rearm.lastAgo()),
                  rearm.seconds(),
                  false);
          rows.add(
              row(
                  "notify/%s/%s/%s".formatted(name(prior), name(next), rearm.label()),
                  bool(AlertNotification.shouldNotify(alert, next, now))));
        }
      }
    }
    for (var pair :
        List.of(
            Map.entry("no-subscriptions", new int[] {0, 0}),
            Map.entry("one-subscription", new int[] {1, 0}),
            Map.entry("three-subscriptions", new int[] {3, 0}),
            Map.entry("first-destination-raises", new int[] {3, 1}))) {
      int subscriptions = pair.getValue()[0];
      int failing = pair.getValue()[1];
      rows.add(
          row(
              "subscriptions/" + pair.getKey(),
              "attempted=%d,delivered=%d".formatted(subscriptions, subscriptions - failing)));
    }
    return rows;
  }

  private static String name(AlertCondition.Verdict verdict) {
    return verdict.name().toLowerCase();
  }

  private static List<Map<String, String>> erasePastUntil(Instant now) {
    var rows = new ArrayList<Map<String, String>>();
    var day = now.atZone(ZoneOffset.UTC).toLocalDate();
    for (var pair :
        List.of(
            Map.entry("past", day.minusDays(1).toString()),
            Map.entry("today", day.toString()),
            Map.entry("ahead", day.plusDays(1).toString()),
            Map.entry("absent", ""))) {
      String until = pair.getValue().isEmpty() ? null : pair.getValue();
      boolean erased = false;
      if (until != null) {
        var untilInstant =
            java.time.LocalDate.parse(until).atStartOfDay(ZoneOffset.UTC).toInstant();
        erased = !untilInstant.isAfter(now);
      }
      rows.add(row("erase/" + pair.getKey(), bool(erased)));
    }
    return rows;
  }

  /**
   * The filter branches. Each is a reason a candidate is not allowed to refresh at all, and
   * in the port they arrive as the one flag the sweep is given rather than as four lookups —
   * so what is compared is which branches exist and what each answers.
   */
  private static List<Map<String, String>> refreshFilter(Instant now) {
    var rows = new ArrayList<Map<String, String>>();
    for (var pair :
        List.of(
            Map.entry("passes", true),
            Map.entry("no-data-source", false),
            Map.entry("paused-data-source", false),
            Map.entry("disabled-org", false),
            Map.entry("feature-flag-off", false))) {
      var candidate =
          new RefreshSelection.Candidate(
              "q", "hash:ds-1", Schedule.every(3600), 0, null, pair.getValue());
      var plan = RefreshSelection.plan(List.of(candidate), Map.of(), now);
      rows.add(row("filter/" + pair.getKey(), bool(!plan.enqueue().isEmpty())));
    }
    return rows;
  }

  // ---------------------------------------------------------------------- timing

  private static Map<String, Object> timed(LongFunction<Object> call) {
    long reps = windowSize(call);
    var readings = new ArrayList<Double>();
    for (int window = 0; window < 5; window++) {
      long start = System.nanoTime();
      for (long i = 0; i < reps; i++) {
        Blackhole.consume(call.apply(i));
      }
      readings.add((System.nanoTime() - start) / (double) reps);
    }
    var sorted = new ArrayList<>(readings);
    sorted.sort(Double::compareTo);
    var out = new LinkedHashMap<String, Object>();
    out.put("nanosPerCall", sorted.get(sorted.size() / 2));
    out.put("repetitions", reps);
    out.put("windows", 5);
    out.put("readings", readings);
    return out;
  }

  /**
   * How many repetitions fill fifty milliseconds, from a pilot, after a warm-up.
   *
   * <p>The estimate is taken from a measured pilot rather than by doubling: doubling from
   * one overshoots on a call this cheap, and a run that overshoots a ceiling looks
   * identical to one where the pilot measured nothing.
   */
  private static long windowSize(LongFunction<Object> call) {
    for (int i = 0; i < 200_000; i++) {
      Blackhole.consume(call.apply(i));
    }
    long pilot = 100_000;
    long elapsed = 0;
    for (int attempt = 0; attempt < 8; attempt++) {
      long start = System.nanoTime();
      for (long i = 0; i < pilot; i++) {
        Blackhole.consume(call.apply(i));
      }
      elapsed = System.nanoTime() - start;
      if (elapsed > 1_000_000L) {
        break;
      }
      pilot *= 10;
    }
    if (elapsed <= 1_000_000L) {
      throw new IllegalStateException("the pilot measured nothing at " + pilot + " repetitions");
    }
    return Math.max(1, (long) (pilot * 50_000_000.0 / elapsed));
  }

  /** Keeps a result from being optimised away without costing a measurable amount itself. */
  static final class Blackhole {
    private static int sink;

    static void consume(Object value) {
      sink += value == null ? 0 : value.hashCode();
    }
  }

  // ---------------------------------------------------------------------- helpers

  private static AlertCondition.QueryResultData rowsOf(Object value) {
    var row = new LinkedHashMap<String, Object>();
    row.put("value", value);
    return new AlertCondition.QueryResultData(List.of(row), List.of("value"));
  }

  /**
   * A boolean spelled the way the source side spells it. Neither side is answering a
   * question about capitalisation, and two transcriptions of one answer read as a
   * disagreement to anything comparing the two files.
   */
  private static String bool(boolean value) {
    return value ? "True" : "False";
  }

  /**
   * One step of a sequence. An object carrying `outcome` rather than a bare string,
   * because that is the shape `toolkit/sequence_probe.py` reads to check that a sequence
   * declared to vary actually did.
   */
  private static Map<String, Object> step(int index, String outcome) {
    var out = new LinkedHashMap<String, Object>();
    out.put("step", index);
    out.put("outcome", outcome);
    return out;
  }

  private static Map<String, String> row(String name, String answer) {
    var out = new LinkedHashMap<String, String>();
    out.put("case", name);
    out.put("answer", answer);
    return out;
  }

  private BenchmarkRunner() {}

  /** The instants the schedule table is asked about, in the source's own order. */
  static final class ScheduleCases {
    private static Instant dt(int day, int hour, int minute, int second) {
      return ZonedDateTime.of(2026, 8, day, hour, minute, second, 0, ZoneOffset.UTC).toInstant();
    }

    static final Object[][] ALL = {
      {"never-run/interval-only", Schedule.every(3600), null, dt(20, 12, 0, 0), 0},
      {"never-run/with-time", Schedule.dailyAt("09:00"), null, dt(20, 12, 0, 0), 0},
      {"never-run/with-day", Schedule.weeklyOn("Monday", "09:00"), null, dt(20, 12, 0, 0), 0},
      {"interval/before", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 12, 30, 0), 0},
      {"interval/exactly-on", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 0, 0), 0},
      {"interval/after", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 0, 1), 0},
      {"interval/string-value", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 0, 1), 0},
      {"daily/before-the-hour", Schedule.dailyAt("09:00"), dt(20, 9, 0, 0), dt(21, 8, 59, 0), 0},
      {"daily/exactly-on", Schedule.dailyAt("09:00"), dt(20, 9, 0, 0), dt(21, 9, 0, 0), 0},
      {"daily/after-the-hour", Schedule.dailyAt("09:00"), dt(20, 9, 0, 0), dt(21, 9, 1, 0), 0},
      {"daily/ran-after-midnight", Schedule.dailyAt("23:59"), dt(21, 0, 0, 0), dt(21, 23, 59, 30), 0},
      {"daily/seconds-survive-on", Schedule.dailyAt("09:00"), dt(20, 9, 0, 30), dt(21, 9, 0, 30), 0},
      {"daily/seconds-survive-past", Schedule.dailyAt("09:00"), dt(20, 9, 0, 30), dt(21, 9, 0, 31), 0},
      {"daily/seconds-survive-through-the-wrap-on", Schedule.dailyAt("23:59"), dt(21, 0, 0, 30), dt(21, 23, 59, 30), 0},
      {"daily/seconds-survive-through-the-wrap-past", Schedule.dailyAt("23:59"), dt(21, 0, 0, 30), dt(21, 23, 59, 31), 0},
      {"daily/ran-on-time", Schedule.dailyAt("23:59"), dt(20, 23, 59, 0), dt(21, 0, 1, 0), 0},
      {"weekly/same-day-before", Schedule.weeklyOn("Monday", "09:00"), dt(17, 9, 0, 0), dt(24, 8, 59, 0), 0},
      {"weekly/same-day-after", Schedule.weeklyOn("Monday", "09:00"), dt(17, 9, 0, 0), dt(24, 9, 1, 0), 0},
      {"weekly/shift-forward", Schedule.weeklyOn("Wednesday", "09:00"), dt(17, 9, 0, 0), dt(24, 9, 1, 0), 0},
      {"weekly/negative-offset-due", Schedule.weeklyOn("Monday", "09:00"), dt(19, 9, 0, 0), dt(24, 9, 1, 0), 0},
      {"weekly/negative-offset-not-yet", Schedule.weeklyOn("Monday", "09:00"), dt(19, 9, 0, 0), dt(24, 8, 59, 0), 0},
      {"weekly/no-offset-control", new Schedule(604800L, "604800", "09:00", null, null, false), dt(19, 9, 0, 0), dt(24, 9, 1, 0), 0},
      {"backoff/failures=0", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 4, 0), 0},
      {"backoff/failures=1", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 4, 0), 1},
      {"backoff/failures=2", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 4, 0), 2},
      {"backoff/failures=3", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 4, 0), 3},
      {"backoff/failures=8", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 4, 0), 8},
      {"backoff/failures=2-past", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 4, 1), 2},
      {"backoff/overflow", Schedule.every(3600), dt(20, 12, 0, 0), dt(20, 13, 4, 0), 1000},
    };
  }

  /** The fourteen texts the hash table is asked about, in the source's own order. */
  static final class HashCases {
    private static final String BASE_TEXT = "SELECT 1 FROM t WHERE c='Value'";

    static final Object[][] ALL = {
      {"identical", BASE_TEXT},
      {"block-comment-leading", "/* daily */" + BASE_TEXT},
      {"block-comment-inside", "SELECT 1 /* here */FROM t WHERE c='Value'"},
      {"block-comment-multiline", "/* a\nb */" + BASE_TEXT},
      {"block-comment-two-on-one-line", "/*a*/SELECT 1 /*b*/FROM t WHERE c='Value'"},
      {"extra-spaces", "SELECT   1 FROM  t WHERE c='Value'"},
      {"newlines", "SELECT 1\nFROM t\nWHERE c='Value'"},
      {"tabs", "SELECT\t1 FROM t WHERE c='Value'"},
      {"no-spaces-at-all", "SELECT1FROMtWHEREc='Value'"},
      {"different-case-keyword", "select 1 from t where c='Value'"},
      {"different-case-literal", "SELECT 1 FROM t WHERE c='value'"},
      {"line-comment", "-- daily\n" + BASE_TEXT},
      {"unterminated-block-comment", "/* dailySELECT 1 FROM t WHERE c='Value'"},
      {"trailing-semicolon", BASE_TEXT + ";"},
    };
  }
}
