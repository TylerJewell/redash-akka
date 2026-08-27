package io.akka.redash.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;

/** `/api/jobs` — where a caller watches a query run, and stops it (SPEC-001 R83, R84). */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/jobs")
public class JobsEndpoint extends ApiBase {

  public JobsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/{jobId}")
  public HttpResponse get(String jobId) {
    return answer(() -> {
      caller();
      return Jobs.read(service, jobId);
    });
  }

  @Delete("/{jobId}")
  public HttpResponse cancel(String jobId) {
    return answer(() -> {
      guardCsrf(null);
      caller();
      return Jobs.cancel(service, jobId);
    });
  }
}
