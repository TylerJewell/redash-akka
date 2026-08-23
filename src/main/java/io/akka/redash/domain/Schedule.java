package io.akka.redash.domain;

/**
 * When a saved query wants to run again.
 *
 * <p>Every field is independently absent-able, and the combination where all of them are
 * absent is reachable — it is one of the cases a refresh sweep passes over (SPEC-001 R10).
 *
 * <p>Modelled as plain fields rather than as a sealed interface over "every interval",
 * "daily at a time" and "weekly on a day": a sealed interface nested inside another
 * record's field compiles clean and fails at runtime with {@code Could not deserialize
 * message}, which the target probe established before this was written
 * (question-log row 4).
 *
 * @param intervalSeconds the gap between runs; absent when the schedule carries nothing
 * @param intervalRaw the interval exactly as it was stored, kept so that a value the
 *     arithmetic cannot read is distinguishable from one that was never given — the two
 *     are different outcomes (R10 against R12)
 * @param timeOfDay {@code "HH:MM"}, or null; when present the interval is a whole number
 *     of days
 * @param dayOfWeek an English day name, or null
 * @param until {@code "YYYY-MM-DD"}, or null
 * @param disabled whether the schedule has been switched off, which may itself be absent
 */
public record Schedule(
    Long intervalSeconds,
    String intervalRaw,
    String timeOfDay,
    String dayOfWeek,
    String until,
    Boolean disabled) {

  public static Schedule none() {
    return null;
  }

  public static Schedule every(long seconds) {
    return new Schedule(seconds, String.valueOf(seconds), null, null, null, false);
  }

  public static Schedule dailyAt(String timeOfDay) {
    return new Schedule(86400L, "86400", timeOfDay, null, null, false);
  }

  public static Schedule weeklyOn(String dayOfWeek, String timeOfDay) {
    return new Schedule(604800L, "604800", timeOfDay, dayOfWeek, null, false);
  }

  public Schedule withUntil(String until) {
    return new Schedule(intervalSeconds, intervalRaw, timeOfDay, dayOfWeek, until, disabled);
  }

  public Schedule withDisabled(boolean value) {
    return new Schedule(intervalSeconds, intervalRaw, timeOfDay, dayOfWeek, until, value);
  }

  /**
   * A schedule stored with an interval that is not a number. The sweep does not pass over
   * this the way it passes over an empty one — it writes the schedule back disabled (R12).
   */
  public static Schedule unreadable(String raw) {
    return new Schedule(null, raw, null, null, null, false);
  }

  /** Every value absent, including {@code disabled} — the case R10's last clause covers. */
  public static Schedule allNull() {
    return new Schedule(null, null, null, null, null, null);
  }

  public boolean isDisabled() {
    return Boolean.TRUE.equals(disabled);
  }

  /**
   * True when every value the schedule carries is absent. {@code disabled} counts, which is
   * why a schedule carrying {@code disabled: false} is not "all null" and takes the error
   * path instead.
   */
  public boolean everyValueAbsent() {
    return intervalRaw == null
        && timeOfDay == null
        && dayOfWeek == null
        && until == null
        && disabled == null;
  }
}
