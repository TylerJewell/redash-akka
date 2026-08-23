package io.akka.redash.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Whether a saved query is due to run again (SPEC-001 R1 to R6).
 *
 * <p>The comparison at the end is strictly greater, and so is the one inside the failure
 * backoff: a query sitting exactly on its boundary is not due, and becomes due on the next
 * instant. That is the whole difference between a query that runs at 09:00:00 and one that
 * runs on the sweep after it.
 */
public final class ScheduleDecision {

  /** What {@link #due} decided, and why, so a caller can report a skip rather than a silence. */
  public enum Outcome {
    DUE_NEVER_RUN,
    DUE,
    NOT_YET,
    NO_SCHEDULE,
    DISABLED,
    UNTIL_REACHED,
    EVERY_VALUE_ABSENT,
    /** The arithmetic could not read the schedule. R12 makes this write back, not skip. */
    UNREADABLE
  }

  public record Decision(Outcome outcome, Instant boundary) {
    public boolean isDue() {
      return outcome == Outcome.DUE || outcome == Outcome.DUE_NEVER_RUN;
    }
  }

  private ScheduleDecision() {}

  public static Decision due(Schedule schedule, Instant lastRun, Instant now, int scheduleFailures) {
    if (schedule == null) {
      return new Decision(Outcome.NO_SCHEDULE, null);
    }
    if (schedule.isDisabled()) {
      return new Decision(Outcome.DISABLED, null);
    }
    if (schedule.everyValueAbsent()) {
      return new Decision(Outcome.EVERY_VALUE_ABSENT, null);
    }
    if (schedule.until() != null) {
      Instant untilInstant;
      try {
        untilInstant = java.time.LocalDate.parse(schedule.until()).atStartOfDay(ZoneOffset.UTC).toInstant();
      } catch (RuntimeException e) {
        return new Decision(Outcome.UNREADABLE, null);
      }
      if (!untilInstant.isAfter(now)) {
        return new Decision(Outcome.UNTIL_REACHED, null);
      }
    }
    if (lastRun == null) {
      return new Decision(Outcome.DUE_NEVER_RUN, null);
    }
    if (schedule.intervalSeconds() == null) {
      return new Decision(Outcome.UNREADABLE, null);
    }

    Instant boundary;
    try {
      boundary = boundary(schedule, lastRun);
    } catch (RuntimeException e) {
      return new Decision(Outcome.UNREADABLE, null);
    }

    if (scheduleFailures > 0) {
      // 2**failures minutes. Above 62 the shift itself would wrap, and the source's
      // equivalent overflow leaves the query permanently not due rather than raising.
      if (scheduleFailures > 40) {
        return new Decision(Outcome.NOT_YET, boundary);
      }
      boundary = boundary.plus(Duration.ofMinutes(1L << scheduleFailures));
    }

    return new Decision(now.isAfter(boundary) ? Outcome.DUE : Outcome.NOT_YET, boundary);
  }

  private static Instant boundary(Schedule schedule, Instant lastRun) {
    long interval = schedule.intervalSeconds();
    if (schedule.timeOfDay() == null) {
      return lastRun.plusSeconds(interval);
    }

    var parts = schedule.timeOfDay().split(":");
    int hour = Integer.parseInt(parts[0]);
    int minute = Integer.parseInt(parts[1]);

    var previous = lastRun.atZone(ZoneOffset.UTC);
    // Only the hour and the minute are replaced. The seconds of the previous run survive
    // into the boundary, so a run that landed at :30 past moves every later boundary with it.
    var normalised = previous.withHour(hour).withMinute(minute);
    if (normalised.isAfter(previous)) {
      previous = normalised.minusDays(1);
    }

    long daysDelay = interval / 86400L;
    long daysToAdd = 0;
    if (schedule.dayOfWeek() != null) {
      // May be negative, and is not clamped: asking for a day earlier in the week moves the
      // boundary backwards, and the query is then due on the next sweep.
      daysToAdd = weekdayIndex(schedule.dayOfWeek()) - (normalised.getDayOfWeek().getValue() - 1);
    }

    return previous.plusDays(daysDelay).plusDays(daysToAdd).withHour(hour).withMinute(minute).toInstant();
  }

  private static int weekdayIndex(String dayName) {
    for (DayOfWeek day : DayOfWeek.values()) {
      if (day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).equalsIgnoreCase(dayName)) {
        return day.getValue() - 1;
      }
    }
    throw new IllegalArgumentException("not a day name: " + dayName);
  }

  /** The days-delay a schedule's interval produces, exposed for the sweep's own reporting. */
  public static long daysDelay(Schedule schedule) {
    return schedule.intervalSeconds() == null ? 0 : schedule.intervalSeconds() / 86400L;
  }

  static ZonedDateTime atUtc(Instant instant) {
    return instant.atZone(ZoneOffset.UTC);
  }
}
