package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R1 to R6. Every case here is one row of the table `probes/probe_01.py` printed
 * from the original, so a disagreement is a disagreement with a run of redash rather than
 * with a reading of it.
 */
class ScheduleDecisionTest {

  private static Instant at(int day, int hour, int minute, int second) {
    return ZonedDateTime.of(2026, 8, day, hour, minute, second, 0, ZoneOffset.UTC).toInstant();
  }

  private static Instant at(int day, int hour, int minute) {
    return at(day, hour, minute, 0);
  }

  private static boolean due(Schedule s, Instant lastRun, Instant now, int failures) {
    return ScheduleDecision.due(s, lastRun, now, failures).isDue();
  }

  // ---- R1: never run before is due immediately, whatever the schedule says ----

  @Test
  void neverRunIsDueWhateverTheShape() {
    assertTrue(due(Schedule.every(3600), null, at(20, 12, 0), 0));
    assertTrue(due(Schedule.dailyAt("09:00"), null, at(20, 12, 0), 0));
    assertTrue(due(Schedule.weeklyOn("Monday", "09:00"), null, at(20, 12, 0), 0));
  }

  @Test
  void neverRunIsDueEvenWithFailuresOnRecord() {
    assertTrue(due(Schedule.every(3600), null, at(20, 12, 0), 5));
  }

  // ---- R2, R3: interval only, and a boundary that is strictly greater ----

  @Test
  void intervalBeforeTheBoundaryIsNotDue() {
    assertFalse(due(Schedule.every(3600), at(20, 12, 0), at(20, 12, 30), 0));
  }

  @Test
  void intervalExactlyOnTheBoundaryIsNotDue() {
    assertFalse(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 0), 0));
  }

  @Test
  void intervalOneSecondPastTheBoundaryIsDue() {
    assertTrue(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 0, 1), 0));
  }

  // ---- R4: a time-of-day, and the seconds that survive the normalisation ----

  @Test
  void dailyBeforeTheHourIsNotDue() {
    assertFalse(due(Schedule.dailyAt("09:00"), at(20, 9, 0), at(21, 8, 59), 0));
  }

  @Test
  void dailyExactlyOnTheHourIsNotDue() {
    assertFalse(due(Schedule.dailyAt("09:00"), at(20, 9, 0), at(21, 9, 0), 0));
  }

  @Test
  void dailyAfterTheHourIsDue() {
    assertTrue(due(Schedule.dailyAt("09:00"), at(20, 9, 0), at(21, 9, 1), 0));
  }

  @Test
  void aRunThatLandedAfterMidnightStillGetsTheSameDaysBoundary() {
    // 23:59 daily, previous run recorded just after midnight on the 21st. Normalising to
    // 23:59 lands after that instant, so the boundary is taken from the 20th and the run
    // is due at 23:59 on the 21st rather than a day later.
    assertTrue(due(Schedule.dailyAt("23:59"), at(21, 0, 0), at(21, 23, 59, 30), 0));
  }

  @Test
  void aRunOnTimeIsNotDueAgainJustAfterMidnight() {
    assertFalse(due(Schedule.dailyAt("23:59"), at(20, 23, 59), at(21, 0, 1), 0));
  }

  @Test
  void theSecondsOfThePreviousRunSurviveIntoTheBoundary() {
    assertFalse(due(Schedule.dailyAt("09:00"), at(20, 9, 0, 30), at(21, 9, 0, 30), 0));
    assertTrue(due(Schedule.dailyAt("09:00"), at(20, 9, 0, 30), at(21, 9, 0, 31), 0));
  }

  @Test
  void theSecondsSurviveTheWrapAsWell() {
    // The route above takes the boundary from the previous run directly. This one takes it
    // from the normalised instant, because that instant lands after the previous run and
    // the boundary is stepped back a day from it — and the normalisation replaces only the
    // hour and the minute, so the seconds arrive here too.
    assertFalse(due(Schedule.dailyAt("23:59"), at(21, 0, 0, 30), at(21, 23, 59, 30), 0));
    assertTrue(due(Schedule.dailyAt("23:59"), at(21, 0, 0, 30), at(21, 23, 59, 31), 0));
  }

  // ---- R4, R5: a day-of-week offset, which may be negative ----

  @Test
  void weeklyOnTheSameWeekdayBeforeTheHourIsNotDue() {
    assertFalse(due(Schedule.weeklyOn("Monday", "09:00"), at(17, 9, 0), at(24, 8, 59), 0));
  }

  @Test
  void weeklyOnTheSameWeekdayAfterTheHourIsDue() {
    assertTrue(due(Schedule.weeklyOn("Monday", "09:00"), at(17, 9, 0), at(24, 9, 1), 0));
  }

  @Test
  void askingForALaterWeekdayPushesTheBoundaryForward() {
    // Previous run Monday the 17th; asking for Wednesday moves the boundary two days on,
    // so a sweep on Monday the 24th finds it not yet due.
    assertFalse(due(Schedule.weeklyOn("Wednesday", "09:00"), at(17, 9, 0), at(24, 9, 1), 0));
  }

  @Test
  void askingForAnEarlierWeekdayPullsTheBoundaryBack() {
    // The offset is weekday(dayOfWeek) - weekday(normalised previous), so a weekly
    // schedule whose previous run landed on a Wednesday and which asks for Mondays gets
    // a negative offset. It is not clamped: the boundary lands two days before the bare
    // interval would have put it, and the query is due on the Monday rather than the
    // Wednesday after.
    var pulledBack = ScheduleDecision.due(
        Schedule.weeklyOn("Monday", "09:00"), at(19, 9, 0), at(24, 9, 1), 0);
    var noOffset = ScheduleDecision.due(Schedule.dailyAt("09:00").withUntil(null), at(19, 9, 0), at(24, 9, 1), 0);
    assertTrue(pulledBack.isDue());
    assertFalse(
        ScheduleDecision.due(Schedule.weeklyOn("Monday", "09:00"), at(19, 9, 0), at(24, 8, 59), 0).isDue());
    // the same schedule without the day-of-week is not due until the Wednesday
    var bareInterval = ScheduleDecision.due(
        new Schedule(604800L, "604800", "09:00", null, null, false), at(19, 9, 0), at(24, 9, 1), 0);
    assertFalse(bareInterval.isDue());
    assertTrue(pulledBack.boundary().isBefore(bareInterval.boundary()));
    assertTrue(noOffset.isDue());
  }

  // ---- R6: the failure backoff, in minutes ----

  @Test
  void theBackoffIsTwoToThePowerOfFailuresInMinutes() {
    // Boundary at 13:00; `now` is 13:04, so failures of 0 and 1 are past it and 2 sits
    // exactly on it - which R2 makes not due.
    assertTrue(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 4), 0));
    assertTrue(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 4), 1));
    assertFalse(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 4), 2));
    assertTrue(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 4, 1), 2));
    assertFalse(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 4), 3));
    assertFalse(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 4), 8));
  }

  @Test
  void aFailureCountTooLargeToAddLeavesTheQueryNotDueRatherThanRaising() {
    assertFalse(due(Schedule.every(3600), at(20, 12, 0), at(20, 13, 4), 1000));
  }

  // ---- R10: the outcomes that are not about the boundary at all ----

  @Test
  void everySkipOutcomeIsNamedRatherThanBeingAFalse() {
    assertEquals(
        ScheduleDecision.Outcome.NO_SCHEDULE,
        ScheduleDecision.due(null, at(20, 12, 0), at(21, 12, 0), 0).outcome());
    assertEquals(
        ScheduleDecision.Outcome.DISABLED,
        ScheduleDecision.due(Schedule.every(3600).withDisabled(true), at(20, 12, 0), at(21, 12, 0), 0)
            .outcome());
    assertEquals(
        ScheduleDecision.Outcome.EVERY_VALUE_ABSENT,
        ScheduleDecision.due(Schedule.allNull(), at(20, 12, 0), at(21, 12, 0), 0).outcome());
    assertEquals(
        ScheduleDecision.Outcome.UNTIL_REACHED,
        ScheduleDecision.due(
                Schedule.every(3600).withUntil("2026-08-20"), at(19, 12, 0), at(21, 12, 0), 0)
            .outcome());
    assertEquals(
        ScheduleDecision.Outcome.UNREADABLE,
        ScheduleDecision.due(Schedule.unreadable("not-a-number"), at(20, 12, 0), at(21, 12, 0), 0)
            .outcome());
  }

  @Test
  void anUntilStillAheadDoesNotStopTheQuery() {
    assertTrue(due(Schedule.every(3600).withUntil("2026-08-24"), at(19, 12, 0), at(21, 12, 0), 0));
  }

  @Test
  void anUntilExactlyAtNowCountsAsReached() {
    var untilInstant = ZonedDateTime.of(2026, 8, 21, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
    assertEquals(
        ScheduleDecision.Outcome.UNTIL_REACHED,
        ScheduleDecision.due(
                Schedule.every(3600).withUntil("2026-08-21"), at(19, 12, 0), untilInstant, 0)
            .outcome());
  }

  @Test
  void aScheduleCarryingDisabledFalseAndNothingElseIsUnreadableRatherThanEmpty() {
    // `disabled: false` is a value, so the schedule is not "every value absent" and the
    // arithmetic is asked to read an interval that is not there.
    assertEquals(
        ScheduleDecision.Outcome.UNREADABLE,
        ScheduleDecision.due(Schedule.unreadable(null), at(20, 12, 0), at(21, 12, 0), 0).outcome());
  }
}
