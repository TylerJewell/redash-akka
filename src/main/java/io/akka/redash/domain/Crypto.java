package io.akka.redash.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The four wire formats an account is carried in (SPEC-001 R9, R10, R13, R14, R15).
 *
 * <p>Every one of these is a format rather than a decision: a password hash written by
 * redash has to verify here and a hash written here has to verify there, or an
 * installation cannot be moved from one to the other. They are checked against values the
 * original produced, not against their own arithmetic — `probes/out/probe_10_crypto.json`
 * and `probe_15_internals.json` hold the vectors the tests use.
 */
public final class Crypto {

  /** The alphabet crypt(3) encodes its digests with, in its own non-standard order. */
  private static final String CRYPT64 =
      "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  /** The alphabet an API key is drawn from, in the order the source lists it. */
  private static final String TOKEN_ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

  /** What passlib's application context uses by default for a new hash. */
  public static final int DEFAULT_ROUNDS = 656000;

  private static final SecureRandom RANDOM = new SecureRandom();

  private Crypto() {}

  // ---------------------------------------------------------------- passwords

  /** A new hash, in the scheme and with the round count the source's context defaults to. */
  public static String hashPassword(String password) {
    return shaCrypt(password, randomSalt(16), DEFAULT_ROUNDS, true);
  }

  /**
   * Whether a stored hash matches. Both schemes the source's context accepts are handled,
   * because an installation carried over may hold either.
   */
  public static boolean verifyPassword(String password, String stored) {
    if (password == null || stored == null || !stored.startsWith("$")) {
      return false;
    }
    var parts = stored.split("\\$");
    // "", scheme, [rounds=N], salt, hash
    if (parts.length < 4) {
      return false;
    }
    var scheme = parts[1];
    boolean sha512;
    if ("6".equals(scheme)) {
      sha512 = true;
    } else if ("5".equals(scheme)) {
      sha512 = false;
    } else {
      return false;
    }
    int index = 2;
    int rounds = 5000;
    if (parts[index].startsWith("rounds=")) {
      try {
        rounds = Integer.parseInt(parts[index].substring("rounds=".length()));
      } catch (NumberFormatException e) {
        return false;
      }
      index++;
    }
    if (parts.length <= index) {
      return false;
    }
    var salt = parts[index];
    var recomputed = shaCrypt(password, salt, rounds, sha512);
    return MessageDigest.isEqual(
        recomputed.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * crypt(3)'s SHA-2 scheme, whole.
   *
   * <p>The two loops at the end are the whole cost of the algorithm and the reason the
   * round count is part of the stored hash: changing it changes the hash, so the count
   * travels with the value rather than with the program.
   */
  static String shaCrypt(String password, String salt, int rounds, boolean sha512) {
    var algorithm = sha512 ? "SHA-512" : "SHA-256";
    int length = sha512 ? 64 : 32;
    byte[] pw = password.getBytes(StandardCharsets.UTF_8);
    byte[] sb = salt.getBytes(StandardCharsets.UTF_8);
    if (sb.length > 16) {
      sb = Arrays.copyOf(sb, 16);
      salt = new String(sb, StandardCharsets.UTF_8);
    }

    var b = digest(algorithm, pw, sb, pw);

    var a = newDigest(algorithm);
    a.update(pw);
    a.update(sb);
    for (int i = pw.length; i >= length; i -= length) {
      a.update(b);
    }
    a.update(b, 0, pw.length % length);
    for (int i = pw.length; i > 0; i >>= 1) {
      if ((i & 1) != 0) {
        a.update(b);
      } else {
        a.update(pw);
      }
    }
    byte[] current = a.digest();

    var dp = newDigest(algorithm);
    for (int i = 0; i < pw.length; i++) {
      dp.update(pw);
    }
    byte[] p = repeat(dp.digest(), pw.length, length);

    var ds = newDigest(algorithm);
    for (int i = 0, limit = 16 + (current[0] & 0xff); i < limit; i++) {
      ds.update(sb);
    }
    byte[] s = repeat(ds.digest(), sb.length, length);

    for (int i = 0; i < rounds; i++) {
      var c = newDigest(algorithm);
      if ((i & 1) != 0) {
        c.update(p);
      } else {
        c.update(current);
      }
      if (i % 3 != 0) {
        c.update(s);
      }
      if (i % 7 != 0) {
        c.update(p);
      }
      if ((i & 1) != 0) {
        c.update(current);
      } else {
        c.update(p);
      }
      current = c.digest();
    }

    var out = new StringBuilder();
    out.append('$').append(sha512 ? '6' : '5').append('$');
    if (rounds != 5000) {
      out.append("rounds=").append(rounds).append('$');
    }
    out.append(salt).append('$');
    out.append(sha512 ? encode512(current) : encode256(current));
    return out.toString();
  }

  private static byte[] repeat(byte[] digest, int wanted, int length) {
    var out = new byte[wanted];
    int written = 0;
    while (written + length <= wanted) {
      System.arraycopy(digest, 0, out, written, length);
      written += length;
    }
    System.arraycopy(digest, 0, out, written, wanted - written);
    return out;
  }

  /** The permutation crypt(3) writes a 64-byte digest in. It is not an ordering anybody chose. */
  private static String encode512(byte[] b) {
    int[][] groups = {
      {0, 21, 42}, {22, 43, 1}, {44, 2, 23}, {3, 24, 45}, {25, 46, 4}, {47, 5, 26},
      {6, 27, 48}, {28, 49, 7}, {50, 8, 29}, {9, 30, 51}, {31, 52, 10}, {53, 11, 32},
      {12, 33, 54}, {34, 55, 13}, {56, 14, 35}, {15, 36, 57}, {37, 58, 16}, {59, 17, 38},
      {18, 39, 60}, {40, 61, 19}, {62, 20, 41}
    };
    var out = new StringBuilder();
    for (int[] group : groups) {
      out.append(b64(b[group[0]], b[group[1]], b[group[2]], 4));
    }
    out.append(b64((byte) 0, (byte) 0, b[63], 2));
    return out.toString();
  }

  /** The same permutation for a 32-byte digest. */
  private static String encode256(byte[] b) {
    int[][] groups = {
      {0, 10, 20}, {21, 1, 11}, {12, 22, 2}, {3, 13, 23}, {24, 4, 14},
      {15, 25, 5}, {6, 16, 26}, {27, 7, 17}, {18, 28, 8}, {9, 19, 29}
    };
    var out = new StringBuilder();
    for (int[] group : groups) {
      out.append(b64(b[group[0]], b[group[1]], b[group[2]], 4));
    }
    out.append(b64((byte) 0, b[31], b[30], 3));
    return out.toString();
  }

  private static String b64(byte b2, byte b1, byte b0, int count) {
    int value = ((b2 & 0xff) << 16) | ((b1 & 0xff) << 8) | (b0 & 0xff);
    var out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append(CRYPT64.charAt(value & 0x3f));
      value >>= 6;
    }
    return out.toString();
  }

  private static String randomSalt(int length) {
    var out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.append(CRYPT64.charAt(RANDOM.nextInt(CRYPT64.length())));
    }
    return out.toString();
  }

  // ---------------------------------------------------------------- tokens

  /** An API key: as long as asked for, from the source's own alphabet, cryptographically drawn. */
  public static String generateToken(int length) {
    var out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
    }
    return out.toString();
  }

  public static String md5Hex(String value) {
    return Json.hex(digest("MD5", value.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * What the session cookie carries. Both halves of the pair it hashes change when an
   * account changes, which is what makes every other session stop working.
   */
  public static String sessionIdentity(long userId, String email, String passwordHash) {
    return userId + "-" + md5Hex(email + "," + passwordHash);
  }

  // ---------------------------------------------------------------- signed links

  /**
   * The signature on a shared link. The expiry is folded in as its *string* form after the
   * path, and the caller's value arrives as text and is read as a float first — so 1600000000
   * and 1600000000.0 sign differently and a link signed for one does not open with the other.
   */
  public static String signLink(String key, String path, String expires) {
    if (key == null || key.isEmpty()) {
      return null;
    }
    var mac = mac("HmacSHA1", key.getBytes(StandardCharsets.UTF_8));
    mac.update(path.getBytes(StandardCharsets.UTF_8));
    mac.update(expires.getBytes(StandardCharsets.UTF_8));
    return Json.hex(mac.doFinal());
  }

  /**
   * What the request path itself signs. The handler reads the caller's `expires` as a float
   * before signing, so the bytes that go into the digest always carry a decimal point even
   * when the caller sent a whole number.
   */
  public static String signLink(String key, String path, double expires) {
    return signLink(key, path, pythonFloat(expires));
  }

  /** How Python writes a float that happens to be whole: with a trailing {@code .0}. */
  static String pythonFloat(double value) {
    if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e16) {
      return (long) value + ".0";
    }
    return Double.toString(value);
  }

  // ---------------------------------------------------------------- timed tokens

  /** What an invitation, reset or verification link carries, and what refused it. */
  public sealed interface TokenResult {
    record Valid(String payload, long issuedAt) implements TokenResult {}

    /** The signature is right and the token is older than the caller allows. */
    record Expired(String payload, long issuedAt) implements TokenResult {}

    record Bad(String reason) implements TokenResult {}
  }

  /**
   * itsdangerous' URL-safe timed serialiser, whole: three dot-separated URL-safe base64
   * segments over the JSON of the payload, a big-endian Unix second count with its leading
   * zero bytes removed, and an HMAC-SHA1 under a key derived by concatenating the salt, the
   * literal {@code signer}, and the secret.
   *
   * <p>The payload is compressed when that makes it shorter and the compressed form is
   * marked with a leading dot — which is why a token is decoded by looking at that dot
   * rather than by trying to parse what is inside.
   */
  public static String signToken(String secret, String salt, String payload, long nowSeconds) {
    var body = Json.dumps(payload).getBytes(StandardCharsets.UTF_8);
    var encoded = urlSafe(body);
    var value = encoded + "." + urlSafe(intToBytes(nowSeconds));
    return value + "." + urlSafe(signature(secret, salt, value));
  }

  public static TokenResult readToken(String secret, String salt, String token, long maxAgeSeconds,
      long nowSeconds) {
    if (token == null) {
      return new TokenResult.Bad("no token");
    }
    int lastDot = token.lastIndexOf('.');
    if (lastDot < 0) {
      return new TokenResult.Bad("no signature");
    }
    var value = token.substring(0, lastDot);
    byte[] given;
    try {
      given = fromUrlSafe(token.substring(lastDot + 1));
    } catch (IllegalArgumentException e) {
      return new TokenResult.Bad("bad signature encoding");
    }
    if (!MessageDigest.isEqual(given, signature(secret, salt, value))) {
      return new TokenResult.Bad("bad signature");
    }

    int stampDot = value.lastIndexOf('.');
    if (stampDot < 0) {
      return new TokenResult.Bad("no timestamp");
    }
    long issuedAt;
    String payload;
    try {
      issuedAt = bytesToInt(fromUrlSafe(value.substring(stampDot + 1)));
      var body = fromUrlSafe(value.substring(0, stampDot));
      payload = (String) Json.loads(new String(body, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      return new TokenResult.Bad("bad payload");
    }
    if (maxAgeSeconds >= 0 && nowSeconds - issuedAt > maxAgeSeconds) {
      return new TokenResult.Expired(payload, issuedAt);
    }
    return new TokenResult.Valid(payload, issuedAt);
  }

  static byte[] signature(String secret, String salt, String value) {
    var key = derivedKey(secret, salt);
    var mac = mac("HmacSHA1", key);
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }

  /** The "django-concat" derivation: hash the salt, the literal {@code signer}, and the key. */
  static byte[] derivedKey(String secret, String salt) {
    return digest(
        "SHA-1",
        salt.getBytes(StandardCharsets.UTF_8),
        "signer".getBytes(StandardCharsets.UTF_8),
        secret.getBytes(StandardCharsets.UTF_8));
  }

  static byte[] intToBytes(long value) {
    var out = new ByteArrayOutputStream();
    long remaining = value;
    var stack = new byte[8];
    int index = 0;
    while (remaining > 0) {
      stack[index++] = (byte) (remaining & 0xff);
      remaining >>= 8;
    }
    if (index == 0) {
      return new byte[0];
    }
    for (int i = index - 1; i >= 0; i--) {
      out.write(stack[i]);
    }
    return out.toByteArray();
  }

  static long bytesToInt(byte[] bytes) {
    long value = 0;
    for (byte b : bytes) {
      value = (value << 8) | (b & 0xff);
    }
    return value;
  }

  public static String urlSafe(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  public static byte[] fromUrlSafe(String value) {
    return Base64.getUrlDecoder().decode(value);
  }

  // ---------------------------------------------------------------- plumbing

  public static byte[] digest(String algorithm, byte[]... parts) {
    var digest = newDigest(algorithm);
    for (byte[] part : parts) {
      digest.update(part);
    }
    return digest.digest();
  }

  private static MessageDigest newDigest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is not available", e);
    }
  }

  public static Mac mac(String algorithm, byte[] key) {
    try {
      var mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(key, algorithm));
      return mac;
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(algorithm + " is not available", e);
    }
  }
}
