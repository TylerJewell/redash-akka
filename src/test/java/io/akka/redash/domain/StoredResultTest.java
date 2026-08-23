package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R15, against the four ages the original was asked about
 * (`probes/probe_03.py` freshness family) plus the boundary itself.
 */
class StoredResultTest {

  private static final Instant RETRIEVED = Instant.parse("2026-08-23T12:00:00Z");
  private static final Instant TWO_MINUTES_LATER = RETRIEVED.plusSeconds(120);

  private static StoredResult result() {
    return new StoredResult(
        "hash", "ds-1", "SELECT 1",
        List.<Map<String, Object>>of(Map.of("value", 1)), List.of("value"), 0.5, RETRIEVED);
  }

  @Test
  void theFourAgesTheOriginalWasAskedAbout() {
    var stored = result();
    assertFalse(stored.isFreshEnough(0, TWO_MINUTES_LATER), "a maxAge of zero reuses nothing");
    assertFalse(stored.isFreshEnough(60, TWO_MINUTES_LATER));
    assertTrue(stored.isFreshEnough(300, TWO_MINUTES_LATER));
    assertTrue(stored.isFreshEnough(StoredResult.ANY_AGE, TWO_MINUTES_LATER));
  }

  @Test
  void aResultExactlyOnTheBoundaryIsStillFreshEnough() {
    // The condition is `retrievedAt + maxAge >= now`, so the boundary is inclusive - which
    // is also why zero rejects everything: it asks for a result obtained now or later.
    var stored = result();
    assertTrue(stored.isFreshEnough(120, TWO_MINUTES_LATER));
    assertFalse(stored.isFreshEnough(120, TWO_MINUTES_LATER.plusSeconds(1)));
  }

  @Test
  void aMaxAgeOfZeroRejectsAResultRetrievedAtThisVeryInstant() {
    assertTrue(result().isFreshEnough(0, RETRIEVED), "at the same instant it is still fresh");
    assertFalse(result().isFreshEnough(0, RETRIEVED.plusMillis(1)));
  }

  @Test
  void anyAgeAcceptsAResultFromLongAgo() {
    assertTrue(result().isFreshEnough(StoredResult.ANY_AGE, RETRIEVED.plusSeconds(86400 * 365)));
  }

  @Test
  void theCacheKeyIsTheHashAndTheDataSource() {
    assertEquals("hash:ds-1", result().cacheKey());
  }
}
