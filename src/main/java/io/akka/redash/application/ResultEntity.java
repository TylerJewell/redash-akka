package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.redash.domain.StoredResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The cached answer for one cache key — the pair of query hash and data source, never the
 * query id (SPEC-001 R9). Two saved queries whose text hashes the same on the same data
 * source share this entity, which is what makes one run answer both of them.
 */
@Component(id = "result")
public class ResultEntity extends KeyValueEntity<ResultEntity.State> {

  public record State(
      String cacheKey,
      String queryHash,
      String dataSourceId,
      String queryText,
      List<Map<String, Object>> rows,
      List<String> columns,
      double runtimeSeconds,
      Instant retrievedAt,
      int storedCount) {

    public boolean isEmpty() {
      return retrievedAt == null;
    }

    public StoredResult asStoredResult() {
      return new StoredResult(queryHash, dataSourceId, queryText, rows, columns, runtimeSeconds, retrievedAt);
    }
  }

  public record Store(
      String queryHash,
      String dataSourceId,
      String queryText,
      List<Map<String, Object>> rows,
      List<String> columns,
      double runtimeSeconds,
      Instant retrievedAt) {}

  /** The key is filled by the first store; a command context is not available here. */
  @Override
  public State emptyState() {
    return new State(null, null, null, null, List.of(), List.of(), 0, null, 0);
  }

  public Effect<State> store(Store store) {
    var next =
        new State(
            commandContext().entityId(),
            store.queryHash(),
            store.dataSourceId(),
            store.queryText(),
            store.rows(),
            store.columns(),
            store.runtimeSeconds(),
            store.retrievedAt(),
            currentState().storedCount() + 1);
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }
}
