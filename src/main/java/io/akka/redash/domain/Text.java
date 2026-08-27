package io.akka.redash.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** The small string rules the surface depends on being exact. */
public final class Text {

  /**
   * Anything outside the lower-case ASCII alphanumerics, underscore and hyphen becomes a
   * single hyphen. Runs collapse, and the ends are not trimmed — so a name with a leading
   * space slugs with a leading hyphen, and a name of nothing but hyphens is unchanged.
   */
  private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9_\\-]+");

  /** The characters a download filename may not carry, replaced by a space before joining. */
  private static final Pattern UNSAFE_FILENAME = Pattern.compile("[<>:\"\\\\/|?*]+");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Unicode control characters, which the source strips out of a query's name first. */
  private static final Pattern CONTROL = Pattern.compile("\\p{C}");

  private Text() {}

  public static String slugify(String value) {
    return NOT_SLUG.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("-");
  }

  public static String toFilename(String value) {
    var spaced = UNSAFE_FILENAME.matcher(value).replaceAll(" ");
    var joined = WHITESPACE.matcher(spaced).replaceAll("_");
    int start = 0;
    int end = joined.length();
    while (start < end && joined.charAt(start) == '_') {
      start++;
    }
    while (end > start && joined.charAt(end - 1) == '_') {
      end--;
    }
    return joined.substring(start, end);
  }

  public static String stripControl(String value) {
    return CONTROL.matcher(value).replaceAll("");
  }

  /** Both halves of what a downloaded file is called: the stem and the retrieval date. */
  public static String downloadFilename(String queryName, Long queryId, String resultId,
      String retrievedAtDate, String filetype) {
    String stem;
    if (queryName != null) {
      var cleaned = stripControl(queryName);
      stem = cleaned.isEmpty() ? String.valueOf(queryId) : toFilename(cleaned);
    } else {
      stem = resultId;
    }
    return stem + "_" + retrievedAtDate + "." + filetype;
  }
}
