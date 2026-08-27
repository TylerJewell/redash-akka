package io.akka.redash.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Json;
import io.akka.redash.domain.Oracle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The four external sign-ins, against what the original does (SPEC-001 R19, R20).
 *
 * <p>Three of the four need an identity provider standing up, and what they share does not:
 * every one ends in the same find-or-create, and Google's domain rule takes an organisation
 * and a profile and nothing else. Both were driven inside the original's own application
 * context over every case that changes the answer —
 * `probes/probe_22_external_login.py`, recorded at
 * `src/test/resources/from-redash/probe_22_external_login.json` — and this puts the same
 * cases to the rebuild.
 *
 * <p>Remote-user sign-in needs no provider, so it is driven through its own route on both
 * sides rather than through the part underneath it. That is where the comparison earned its
 * place: the rebuild answered a 400 page where the original redirects, treated the literal
 * word `(null)` as an address, and refused with 404 when the setting is off. All three are
 * the original's own answers now.
 */
class ExternalLoginIntegrationTest extends TestKitSupport {

  static final String RECORDED = "probe_22_external_login.json";

  private Service service() {
    return new Service(componentClient);
  }

  @Test
  void findOrCreateAnswersWhatTheOriginalAnswers() {
    var recorded = Oracle.section(RECORDED, "create_and_login_user");
    var service = service();
    service.setup("Probe", "Admin", "external@example.com", "probe-password-1");
    var client = new WalkClient("http://127.0.0.1:" + testKit.getPort());

    // Somebody nobody has seen. The header is the whole of the identity, so the name the
    // account is created with is the address, exactly as the original creates it.
    warmUp(client);
    var created = signIn(client, "new-person@example.com");
    assertEquals(302, created.status(), created.body());
    var after = settled("new-person@example.com");
    assertNotNull(after, "the address nobody had seen was not created");
    var expected = Json.asMap(Json.asMap(recorded.get("unknown-address")).get("after"));
    assertEquals(false, after.get("is_invitation_pending"),
        "a person signed in from outside does not start pending, as the original's does not");
    assertEquals(expected.get("group_ids") == null ? null : 1,
        service.groupIdsOf(after).size(),
        "one group, the organisation's default, as the original puts them in");

    // Somebody invited and not yet arrived: the pending flag is cleared and nothing else.
    service.createUser("pending-person@example.com", "Pending Person",
        List.of(service.builtinGroup("default")), null, true);
    assertEquals(true, settled("pending-person@example.com").get("is_invitation_pending"),
        "the recorded run's own precondition: the account starts pending");
    assertEquals(302, signIn(client, "pending-person@example.com").status());
    assertEquals(false, settledPending("pending-person@example.com"),
        "signing in from outside clears the pending flag, as it does on the original");

    // Somebody disabled: nobody is signed in, and the account is left as it was.
    var disabled = service.createUser("disabled-person@example.com", "Disabled Person",
        List.of(service.builtinGroup("default")), null, false);
    service.store().update(Store.USERS, disabled.get("id"),
        Json.map("disabled_at", Json.instant(java.time.Instant.now())));
    var refused = signIn(client, "disabled-person@example.com");
    assertEquals(302, refused.status());
    assertEquals("/", refused.headers().get("location").get(0),
        "a disabled person is sent to the index without a session, as on the original");
    assertNotNull(settled("disabled-person@example.com").get("disabled_at"),
        "and the account is left disabled");
  }

  /**
   * The three shapes that answer the index rather than signing anybody in.
   *
   * <p>Each is the original's own answer, recorded: 302 to `/?next=` with nothing after the
   * equals sign, and no account created.
   */
  @Test
  void theThreeShapesThatSignNobodyInAnswerWhatTheOriginalAnswers() {
    var recorded = Oracle.section(RECORDED, "remote_user");
    var service = service();
    service.setup("Probe", "Admin", "external2@example.com", "probe-password-1");
    var client = new WalkClient("http://127.0.0.1:" + testKit.getPort());
    warmUp(client);

    for (var each : List.of(
        Map.entry("header-is-the-word-null", "(null)"),
        Map.entry("without-the-header", ""))) {
      var expected = Json.asMap(recorded.get(each.getKey()));
      var answer = each.getValue().isEmpty()
          ? client.get("/remote_user/login")
          : signIn(client, each.getValue());
      assertEquals(Service.number(expected.get("status")), (long) answer.status(),
          each.getKey());
      assertEquals(expected.get("location"), answer.headers().get("location").get(0),
          each.getKey() + " location");
    }
    assertNull(service.userByEmail("(null)"), "the word (null) is not an address");
  }

  // ------------------------------------------------------------------ helpers

  private WalkClient.Answer signIn(WalkClient client, String email) {
    return client.getWithHeader("/remote_user/login", "X-Forwarded-Remote-User", email);
  }

  /** The runtime answers its first POST before reading it; a GET is enough to get past. */
  private void warmUp(WalkClient client) {
    for (int attempt = 0; attempt < 20; attempt++) {
      if (client.get("/ping").status() == 200) {
        return;
      }
    }
  }

  private Map<String, Object> settled(String email) {
    Map<String, Object> found = null;
    for (int attempt = 0; attempt < 50 && found == null; attempt++) {
      found = service().userByEmail(email);
      if (found == null) {
        sleep();
      }
    }
    return found;
  }

  private Object settledPending(String email) {
    Object pending = true;
    for (int attempt = 0; attempt < 50 && Boolean.TRUE.equals(pending); attempt++) {
      pending = settled(email).get("is_invitation_pending");
      if (Boolean.TRUE.equals(pending)) {
        sleep();
      }
    }
    return pending;
  }

  private static void sleep() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
