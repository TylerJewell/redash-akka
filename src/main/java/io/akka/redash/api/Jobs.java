package io.akka.redash.api;

import io.akka.redash.application.QueryExecutionWorkflow;
import io.akka.redash.domain.Json;
import java.util.Map;

/**
 * Reading and cancelling a running query (SPEC-001 R83, R84).
 *
 * <p>A job identity that names nothing answers the shape a queued job has rather than a
 * refusal, because the source's own fetch of a job that has expired out of its store does
 * the same.
 */
final class Jobs {

  private Jobs() {}

  static Map<String, Object> read(Service service, String jobId) {
    var job = fetch(service, jobId);
    return Serializers.job(job == null ? Json.map("id", jobId) : job);
  }

  static Map<String, Object> cancel(Service service, String jobId) {
    try {
      var job = service.store().client()
          .forWorkflow(jobId).method(QueryExecutionWorkflow::cancel).invoke();
      return Serializers.job(job == null || job.isEmpty() ? Json.map("id", jobId) : job);
    } catch (RuntimeException e) {
      return Serializers.job(Json.map("id", jobId));
    }
  }

  static Map<String, Object> fetch(Service service, String jobId) {
    try {
      var job = service.store().client()
          .forWorkflow(jobId).method(QueryExecutionWorkflow::get).invoke();
      return job == null || job.isEmpty() ? null : job;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
