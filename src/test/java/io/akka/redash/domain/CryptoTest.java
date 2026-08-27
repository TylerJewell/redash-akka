package io.akka.redash.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SPEC-001 R9, R10, R13, R14, R15, checked against what the original produced. */
class CryptoTest {

  @Test
  @DisplayName("a hash the original wrote verifies here")
  void verifiesTheOriginalsOwnHash() {
    var recorded = Oracle.section("probe_10_crypto.json", "password");
    var stored = String.valueOf(recorded.get("example"));
    assertTrue(Crypto.verifyPassword("probe-password-1", stored));
    assertFalse(Crypto.verifyPassword("probe-password-2", stored));
  }

  @Test
  @DisplayName("recomputing the original's hash from its own salt gives the same string")
  void recomputesByteForByte() {
    var recorded = Oracle.section("probe_10_crypto.json", "password");
    var stored = String.valueOf(recorded.get("example"));
    var parts = stored.split("\\$");
    var salt = parts[3];
    assertEquals(stored, Crypto.shaCrypt("probe-password-1", salt, 656000, true));
  }

  @Test
  @DisplayName("a hash written here has the scheme and round count the original defaults to")
  void writesTheSameShape() {
    var hash = Crypto.hashPassword("probe-password-1");
    assertTrue(hash.startsWith("$6$rounds=656000$"), hash);
    assertTrue(Crypto.verifyPassword("probe-password-1", hash));
    assertFalse(Crypto.verifyPassword("nope", hash));
    // Two hashes of the same password differ, because the salt is drawn each time.
    assertNotEquals(hash, Crypto.hashPassword("probe-password-1"));
  }

  @Test
  @DisplayName("the other scheme the original's context accepts also verifies")
  void verifiesSha256Crypt() {
    var hash = Crypto.shaCrypt("probe-password-1", "0123456789abcdef", 5000, false);
    assertTrue(hash.startsWith("$5$0123456789abcdef$"), hash);
    assertTrue(Crypto.verifyPassword("probe-password-1", hash));
  }

  @Test
  @DisplayName("a stored hash that is not one of the two schemes verifies nothing")
  void refusesAnUnknownScheme() {
    assertFalse(Crypto.verifyPassword("x", "$2b$12$abcdefghijklmnopqrstuv"));
    assertFalse(Crypto.verifyPassword("x", "plaintext"));
    assertFalse(Crypto.verifyPassword("x", null));
    assertFalse(Crypto.verifyPassword(null, "$6$rounds=5000$salt$hash"));
  }

  @Test
  @DisplayName("an API key is forty characters of the original's own alphabet")
  void generatesTheSameShapeOfKey() {
    var recorded = Oracle.section("probe_10_crypto.json", "generate_token");
    var alphabet = String.valueOf(recorded.get("declared_alphabet"));
    for (int i = 0; i < 50; i++) {
      var token = Crypto.generateToken(40);
      assertEquals(40, token.length());
      for (char c : token.toCharArray()) {
        assertTrue(alphabet.indexOf(c) >= 0, "unexpected character " + c);
      }
    }
  }

  @Test
  @DisplayName("the shared-link signature matches the original's, including the float form")
  void signsLinksTheSameWay() {
    var recorded = Oracle.section("probe_10_crypto.json", "sign");
    assertNull(Crypto.signLink(null, "/api/queries/1/results.json", 1600000000));
    // The expiry is folded in as text, so its *type* is part of the signature: a whole
    // number signs differently from the same number written as a float. Both recorded
    // values are asserted, because the request path always takes the second one.
    assertEquals(recorded.get("example"),
        Crypto.signLink("apikey123", "/api/queries/1/results.json", "1600000000"));
    assertEquals(recorded.get("path_matters"),
        Crypto.signLink("apikey123", "/api/queries/2/results.json", "1600000000"));
    assertEquals(recorded.get("expires_matters"),
        Crypto.signLink("apikey123", "/api/queries/1/results.json", "1600000001"));
    assertEquals(recorded.get("expires_is_str_of_float"),
        Crypto.signLink("apikey123", "/api/queries/1/results.json", 1600000000.0));
  }

  @Test
  @DisplayName("the session identity is the original's md5 over the same two fields")
  void buildsTheSameIdentity() {
    var recorded = Oracle.section("probe_10_crypto.json", "identity");
    assertEquals(recorded.get("digest"), Crypto.md5Hex("a@b.com,hash"));
    assertEquals("7-" + recorded.get("digest"), Crypto.sessionIdentity(7, "a@b.com", "hash"));
  }

  @Test
  @DisplayName("the token signer derives the key and signs the way itsdangerous does")
  void derivesTheSameKey() {
    var recorded = Oracle.section("probe_15_internals.json", "signer");
    assertEquals(recorded.get("derived_key_hex"),
        Json.hex(Crypto.derivedKey("k", "itsdangerous")));
    assertEquals(recorded.get("signature_of_empty"),
        Crypto.urlSafe(Crypto.signature("k", "itsdangerous", "")));
  }

  @Test
  @DisplayName("the original's own invitation token reads back here")
  void readsTheOriginalsToken() {
    var recorded = Oracle.section("probe_10_crypto.json", "token");
    var token = String.valueOf(recorded.get("example"));
    var result = Crypto.readToken("probe-secret", "itsdangerous", token, -1, 0);
    var valid = assertInstanceOf(Crypto.TokenResult.Valid.class, result);
    assertEquals("42", valid.payload());

    // The middle segment is a plain Unix second count, not an offset from a custom epoch.
    var seconds = Crypto.bytesToInt(
        Crypto.fromUrlSafe(String.valueOf(recorded.get("timestamp_segment"))));
    assertEquals(seconds, valid.issuedAt());

    // A token one character different fails on the signature rather than on the payload.
    var tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
    assertInstanceOf(Crypto.TokenResult.Bad.class,
        Crypto.readToken("probe-secret", "itsdangerous", tampered, -1, 0));
  }

  @Test
  @DisplayName("a token written here reads back, and expires when it is old enough")
  void roundTripsAndExpires() {
    var token = Crypto.signToken("secret", "itsdangerous", "17", 1_700_000_000L);
    assertEquals(String.valueOf(Oracle.section("probe_10_crypto.json", "token")
        .get("segments")), String.valueOf(token.split("\\.").length));

    assertInstanceOf(Crypto.TokenResult.Valid.class,
        Crypto.readToken("secret", "itsdangerous", token, 60, 1_700_000_030L));
    assertInstanceOf(Crypto.TokenResult.Expired.class,
        Crypto.readToken("secret", "itsdangerous", token, 60, 1_700_000_061L));
    // Exactly on the boundary is still valid: the comparison is strictly greater.
    assertInstanceOf(Crypto.TokenResult.Valid.class,
        Crypto.readToken("secret", "itsdangerous", token, 60, 1_700_000_060L));
    assertInstanceOf(Crypto.TokenResult.Bad.class,
        Crypto.readToken("another-secret", "itsdangerous", token, 60, 1_700_000_030L));
  }

  @Test
  @DisplayName("the timestamp encoding drops leading zero bytes")
  void encodesTheTimestampMinimally() {
    assertEquals(0, Crypto.intToBytes(0).length);
    assertEquals(1, Crypto.intToBytes(255).length);
    assertEquals(2, Crypto.intToBytes(256).length);
    assertEquals(1_700_000_000L, Crypto.bytesToInt(Crypto.intToBytes(1_700_000_000L)));
  }

  @Test
  @DisplayName("a whole float is written the way Python writes it")
  void writesPythonFloats() {
    assertEquals("1600000000.0", Crypto.pythonFloat(1600000000));
    assertEquals("1.5", Crypto.pythonFloat(1.5));
  }
}
