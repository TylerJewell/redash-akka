package io.akka.redash.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.Test;

/**
 * The rebuild driven the way a browser drives it: set up, sign in, and read the two
 * documents the shell asks for before it draws anything.
 *
 * <p>This is the check that the whole HTTP layer is reachable at all — a capability that no
 * component-level test can see, because those call the entity directly and never go through
 * routing, the session cookie or the serialisers.
 */
class SurfaceSmokeIntegrationTest extends TestKitSupport {

  @Test
  void anEmptyInstanceIsSetUpAndThenSignedIntoOverHttp() {
    var base = "http://localhost:" + testKit.getPort();
    var client = new WalkClient(base);

    var setupPage = client.get("/setup");
    assertEquals(200, setupPage.status());
    assertTrue(setupPage.body().contains("csrf_token"),
        "the setup form carries a token the browser posts back");

    var created = client.form("/setup",
        "name=Admin&email=admin%40example.com&password=probe-password-1&org_name=Probe");
    assertEquals(302, created.status());

    var session = client.getJson("/api/session");
    assertEquals(200, session.status());
    assertTrue(session.body().contains("admin@example.com"),
        "the session names the person who set the instance up");

    var config = client.getJson("/api/config");
    assertEquals(200, config.status());
    assertTrue(config.body().contains("client_config"));

    var status = client.getJson("/api/organization/status");
    assertEquals(200, status.status());
    assertTrue(status.body().contains("object_counters"));
  }

  @Test
  void anUnauthenticatedApiRequestIsRefusedAsNotFound() {
    var client = new WalkClient("http://localhost:" + testKit.getPort());
    var answer = client.getJson("/api/queries");
    assertEquals(404, answer.status());
    assertTrue(answer.body().contains("Please login and try again"));
  }
}
