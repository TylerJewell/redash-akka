package io.akka.redash.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The at-rest encryption redash puts a data source's options under.
 *
 * <p>redash stores those options through SQLAlchemy-Utils' `EncryptedType` with the Fernet
 * engine, which is why `manage.py database reencrypt` exists at all: the options are
 * ciphertext in the database, keyed by `REDASH_SECRET_KEY`, and changing that key means
 * rewriting every row. This reproduces the format so that the same command means the same
 * thing here.
 *
 * <p>Two details of the engine are not Fernet's own and are the library's. The key is not a
 * Fernet key — it is a passphrase, and the engine derives the 32 bytes from its SHA-256
 * digest, the first sixteen signing and the last sixteen encrypting. And the timestamp
 * Fernet carries is written but never checked on read, because a stored value has no age.
 */
public final class Fernet {

  private static final byte VERSION = (byte) 0x80;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Fernet() {}

  /** The 32 bytes the engine derives from a passphrase. */
  static byte[] keyOf(String passphrase) {
    return Crypto.digest("SHA-256", passphrase.getBytes(StandardCharsets.UTF_8));
  }

  public static String encrypt(String plaintext, String passphrase) {
    var key = keyOf(passphrase);
    var signing = Arrays.copyOfRange(key, 0, 16);
    var encryption = Arrays.copyOfRange(key, 16, 32);
    var iv = new byte[16];
    RANDOM.nextBytes(iv);
    try {
      var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryption, "AES"),
          new IvParameterSpec(iv));
      var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      var body = ByteBuffer.allocate(1 + 8 + iv.length + ciphertext.length);
      body.put(VERSION);
      body.putLong(System.currentTimeMillis() / 1000);
      body.put(iv);
      body.put(ciphertext);
      var withoutMac = body.array();

      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signing, "HmacSHA256"));
      var signature = mac.doFinal(withoutMac);

      var token = ByteBuffer.allocate(withoutMac.length + signature.length);
      token.put(withoutMac);
      token.put(signature);
      return Base64.getUrlEncoder().encodeToString(token.array());
    } catch (Exception e) {
      throw new IllegalStateException("the options could not be encrypted", e);
    }
  }

  /** The plaintext, or null when the token is not one or was written under another key. */
  public static String decrypt(String token, String passphrase) {
    try {
      var raw = Base64.getUrlDecoder().decode(token);
      if (raw.length < 1 + 8 + 16 + 32 || raw[0] != VERSION) {
        return null;
      }
      var key = keyOf(passphrase);
      var signing = Arrays.copyOfRange(key, 0, 16);
      var encryption = Arrays.copyOfRange(key, 16, 32);

      var withoutMac = Arrays.copyOfRange(raw, 0, raw.length - 32);
      var signature = Arrays.copyOfRange(raw, raw.length - 32, raw.length);
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signing, "HmacSHA256"));
      if (!MessageDigest.isEqual(signature, mac.doFinal(withoutMac))) {
        return null;
      }

      var iv = Arrays.copyOfRange(raw, 9, 25);
      var ciphertext = Arrays.copyOfRange(raw, 25, raw.length - 32);
      var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryption, "AES"),
          new IvParameterSpec(iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }

  /** Whether a stored value looks like one of these tokens rather than a plain document. */
  public static boolean looksEncrypted(Object value) {
    if (!(value instanceof CharSequence text) || text.length() < 57) {
      return false;
    }
    try {
      var raw = Base64.getUrlDecoder().decode(text.toString());
      return raw.length > 0 && raw[0] == VERSION;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
