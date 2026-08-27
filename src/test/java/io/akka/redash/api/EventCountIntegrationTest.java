package io.akka.redash.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Json;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One request writes one event, and one creation takes one identifier (SPEC-001 R145).
 *
 * <p>This exists because the surface walk found every event recorded twice and every
 * identifier advancing by two, which is what a handler running twice per request looks
 * like from the outside. A count is the shortest statement of the rule that would have
 * caught it, and it is one method because the instance can only be set up once.
 *
 * <p>Counting is done in two steps and both matter: wait until the event arrives, then keep
 * waiting and check that a second one does not. A count taken the instant a request returns
 * is a count of how fast the view is, and a count taken once and believed cannot tell one
 * event from two.
 */
class EventCountIntegrationTest extends TestKitSupport {

  @Test
  void oneRequestWritesOneEventAndOneCreationTakesOneIdentifier() {
    // The address is an IPv4 literal: the test kit binds 127.0.0.1 only, and a client that
    // resolves `localhost` tries ::1 first and waits about two seconds for that to fail.
    var client = new WalkClient("http://127.0.0.1:" + testKit.getPort());
    warmUp();
    // The setup form is posted by somebody who is not signed in yet, and an
    // unauthenticated state-changing request is the one shape the cross-site guard does
    // refuse (SPEC-001 R151). Reading the page first is what puts the token in hand, which
    // is what a browser does with it.
    var setup = client.getAndPost("/setup",
        "name=Admin&email=count%40example.com&password=probe-password-1&org_name=Probe");
    assertEquals(302, setup.status(), "setup did not answer a redirect: " + setup.body());

    var listed = client.getJson("/api/groups");
    assertEquals(200, listed.status(), "the session did not survive setup: " + listed.body());
    assertEquals(1, settledCount("list", "group", 1),
        "listing the groups once wrote more than one event");

    var first = client.json("POST", "/api/groups", "{\"name\": \"first\"}");
    assertEquals(200, first.status(), first.body());
    assertEquals(1, settledCount("create", "group", 1),
        "creating one group wrote more than one event");

    var second = client.json("POST", "/api/groups", "{\"name\": \"second\"}");
    long firstId = Service.number(Json.asMap(Json.loads(first.body())).get("id"));
    long secondId = Service.number(Json.asMap(Json.loads(second.body())).get("id"));
    assertEquals(firstId + 1, secondId,
        "two creations should take two consecutive identifiers");

    // A request that is refused writes nothing: the guard runs before the handler does.
    var refused = new WalkClient("http://localhost:" + testKit.getPort())
        .getJson("/api/groups");
    assertEquals(404, refused.status());
    assertEquals(1, settledCount("list", "group", 1),
        "a refused request wrote an event anyway");
  }

  /**
   * Take the runtime past the point where it answers a POST before reading its body.
   *
   * <p>A request answered that way arrives at the client as a closed connection, and
   * {@link WalkClient} re-sends it — which is right for a read and wrong for {@code /setup},
   * because the second attempt finds the organisation already there and redirects without
   * signing anybody in. So the first POST this test makes is one that changes nothing: an
   * unauthenticated create, refused before it reaches a handler. Two in a row have to get
   * through, because the window is a few tens of milliseconds wide and one attempt can
   * cross it by luck.
   */
  private void warmUp() {
    var anonymous = new WalkClient("http://127.0.0.1:" + testKit.getPort());
    int inARow = 0;
    for (int attempt = 0; attempt < 100 && inARow < 2; attempt++) {
      inARow = anonymous.json("POST", "/api/groups", "{\"name\": \"warm\"}").status() == 404
          ? inARow + 1 : 0;
    }
  }

  /**
   * Wait for at least {@code expected} events of one kind, then keep watching.
   *
   * @return how many there are once nothing more has arrived for a second
   */
  private long settledCount(String action, String objectType, long expected) {
    long count = 0;
    for (int attempt = 0; attempt < 100 && count < expected; attempt++) {
      count = countOf(action, objectType);
      pause();
    }
    assertTrue(count >= expected,
        "only " + count + " " + action + "/" + objectType + " events ever arrived");
    for (int attempt = 0; attempt < 10; attempt++) {
      pause();
    }
    return countOf(action, objectType);
  }

  private static void pause() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private long countOf(String action, String objectType) {
    var store = new Store(componentClient);
    long count = 0;
    for (Map<String, Object> event : store.byOrg(Store.EVENTS, 1L)) {
      if (action.equals(event.get("action")) && objectType.equals(event.get("object_type"))) {
        count++;
      }
    }
    return count;
  }
}
