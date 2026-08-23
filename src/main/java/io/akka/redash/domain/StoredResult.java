package io.akka.redash.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One stored answer, and whether it may stand in for running the query again
 * (SPEC-001 R15).
 *
 * <p>A {@code maxAgeSeconds} of zero accepts nothing at all. That is not a degenerate case
 * to guard against: the freshness test is {@code retrievedAt + maxAge >= now}, so zero asks
 * for a result obtained at this instant or later, which no stored result is.
 */
public record StoredResult(
    String queryHash,
    String dataSourceId,
    String queryText,
    List<Map<String, Object>> rows,
    List<String> columns,
    double runtimeSeconds,
    Instant retrievedAt) {

  /** Accept a result of any age. */
  public static final long ANY_AGE = -1;

  public String cacheKey() {
    return QueryHash.cacheKey(queryHash, dataSourceId);
  }

  public boolean isFreshEnough(long maxAgeSeconds, Instant now) {
    if (maxAgeSeconds == ANY_AGE) {
      return true;
    }
    return !retrievedAt.plus(Duration.ofSeconds(maxAgeSeconds)).isBefore(now);
  }

  public AlertCondition.QueryResultData asAlertInput() {
    return new AlertCondition.QueryResultData(rows, columns);
  }
}
