package io.akka.redash.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * What decides whether two saved queries are asking the same question (SPEC-001 R8).
 *
 * <p>Comments are removed first and whitespace second, and the order is what makes a block
 * comment containing a newline survive: the pattern has no dot-matches-newline flag, so it
 * stops at the line break, and by the time whitespace is being removed the comment is
 * ordinary text. `/* a\nb *&#47;SELECT 1` and `SELECT 1` are therefore different questions.
 */
public final class QueryHash {

  // Anchored on the pair of delimiters and non-greedy, and deliberately not multi-line:
  // a comment that spans lines is not a comment as far as this is concerned.
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/");

  private QueryHash() {}

  public static String of(String sql) {
    var withoutComments = BLOCK_COMMENT.matcher(sql).replaceAll("");
    var withoutWhitespace = withoutComments.replaceAll("\\s+", "");
    return md5Hex(withoutWhitespace);
  }

  /** The pair a stored result is filed under. Never the query text, never the query id. */
  public static String cacheKey(String queryHash, String dataSourceId) {
    return queryHash + ":" + dataSourceId;
  }

  private static String md5Hex(String value) {
    try {
      var digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
      var out = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 is not available", e);
    }
  }
}
