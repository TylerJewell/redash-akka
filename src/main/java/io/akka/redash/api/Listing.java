package io.akka.redash.api;

import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordering, paging, tag filtering and search — the four things every list endpoint does
 * before it serialises anything (SPEC-001 R56 to R61).
 *
 * <p>Three of these carry a rule that is easy to get subtly wrong and that the original's
 * own tests pin down. An unrecognised `order` falls back to the default rather than being
 * refused. Every order is **nulls last**, so a query that has never run sorts after one
 * that has, whichever direction was asked for. And the out-of-range page check is skipped
 * when the total is zero, so an empty list answers page 1 rather than a refusal.
 */
public final class Listing {

  private Listing() {}

  // ------------------------------------------------------------------ ordering

  /** The orderings `/api/queries` accepts, and the field each one reads. */
  public static final Map<String, String> QUERY_ORDER = orderMap(
      "name", "lowercase_name",
      "created_at", "created_at",
      "schedule", "interval",
      "runtime", "query_results-runtime",
      "executed_at", "query_results-retrieved_at",
      "created_by", "users-name",
      "starred_at", "favorites-created_at");

  public static final Map<String, String> DASHBOARD_ORDER = orderMap(
      "name", "lowercase_name",
      "created_at", "created_at",
      "starred_at", "favorites-created_at");

  public static final Map<String, String> USER_ORDER = orderMap(
      "name", "name",
      "active_at", "active_at",
      "created_at", "created_at",
      "groups", "group_ids");

  /** Each entry is offered in both directions, the descending one prefixed with a hyphen. */
  private static Map<String, String> orderMap(String... pairs) {
    var out = new LinkedHashMap<String, String>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out.put(pairs[i], pairs[i + 1]);
      out.put("-" + pairs[i], "-" + pairs[i + 1]);
    }
    return out;
  }

  /**
   * Order a list the way the source's own sorter does.
   *
   * @param requested what the caller asked for, possibly empty or unrecognised
   * @param fallback whether an unrecognised order falls back to the default; a search
   *     result does not, because the database has already ordered it by rank
   */
  public static List<Map<String, Object>> order(List<Map<String, Object>> items,
      String requested, String defaultOrder, Map<String, String> allowed, boolean fallback) {
    var asked = requested == null ? "" : requested.strip();
    if (asked.isEmpty() && !fallback) {
      return items;
    }
    var selected = allowed.get(asked);
    if (selected == null) {
      if (!fallback) {
        return items;
      }
      selected = defaultOrder;
    }
    boolean descending = selected.startsWith("-");
    var field = descending ? selected.substring(1) : selected;

    Comparator<Map<String, Object>> comparator = Comparator.comparing(
        item -> sortKey(item, field), Listing::compareNullsLast);
    if (descending) {
      comparator = comparator.reversed();
    }
    var out = new ArrayList<>(items);
    out.sort(comparator);
    return out;
  }

  /**
   * The value an ordering reads out of a serialised row.
   *
   * <p>A qualified name means a related table's column, and the serialiser has already
   * flattened those onto the row — a query carries its runner's `runtime` and
   * `retrieved_at` and its author's name — so the lookup is a name translation rather than
   * a join.
   */
  static Comparable<?> sortKey(Map<String, Object> item, String field) {
    var value = switch (field) {
      case "lowercase_name" -> {
        var name = item.get("name");
        yield name == null ? null : String.valueOf(name).toLowerCase(Locale.ROOT);
      }
      case "interval" -> Json.asMap(item.get("schedule")).get("interval");
      case "query_results-runtime" -> item.get("runtime");
      case "query_results-retrieved_at" -> item.get("retrieved_at");
      case "users-name" -> Json.asMap(item.get("user")).get("name");
      case "favorites-created_at" -> item.get("starred_at");
      case "group_ids" -> String.valueOf(item.get("groups"));
      default -> item.get(field);
    };
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value instanceof Boolean bool) {
      return bool ? 1d : 0d;
    }
    return String.valueOf(value);
  }

  /**
   * Nulls last, in both directions.
   *
   * <p>The reversal a descending order applies would otherwise move them to the front, so
   * the comparator answers on the absence itself rather than on a substitute value.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static int compareNullsLast(Comparable a, Comparable b) {
    if (a == null && b == null) {
      return 0;
    }
    if (a == null) {
      return 1;
    }
    if (b == null) {
      return -1;
    }
    if (a.getClass() != b.getClass()) {
      return String.valueOf(a).compareTo(String.valueOf(b));
    }
    return a.compareTo(b);
  }

  // ------------------------------------------------------------------ paging

  /**
   * The pager's document, and the three refusals it makes.
   *
   * <p>The order the checks run in is what a caller sees: a page below one is refused
   * before the range is looked at, and the range is only looked at when there is anything
   * to page over.
   */
  public static Map<String, Object> paginate(List<Map<String, Object>> items, int page,
      int pageSize, Function<Map<String, Object>, Map<String, Object>> serializer) {
    int count = items.size();
    if (page < 1) {
      throw Http.badRequest("Page must be positive integer.");
    }
    if (count > 0 && (long) (page - 1) * pageSize + 1 > count) {
      throw Http.badRequest("Page is out of range.");
    }
    if (pageSize > 250 || pageSize < 1) {
      throw Http.badRequest("Page size is out of range (1-250).");
    }
    int from = Math.min((page - 1) * pageSize, count);
    int to = Math.min(from + pageSize, count);
    var window = items.subList(from, to);
    var results = new ArrayList<Map<String, Object>>(window.size());
    for (Map<String, Object> item : window) {
      results.add(serializer == null ? item : serializer.apply(item));
    }
    return Json.map("count", (long) count, "page", (long) page,
        "page_size", (long) pageSize, "results", results);
  }

  // ------------------------------------------------------------------ tags

  /** A row passes when it carries **every** tag asked for, not any of them. */
  public static List<Map<String, Object>> filterByTags(List<Map<String, Object>> items,
      List<String> tags) {
    if (tags == null || tags.isEmpty()) {
      return items;
    }
    var out = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> item : items) {
      var carried = new ArrayList<String>();
      for (Object tag : Json.asList(item.get("tags"))) {
        carried.add(String.valueOf(tag));
      }
      if (carried.containsAll(tags)) {
        out.add(item);
      }
    }
    return out;
  }

  /** Every tag in use, with how many rows carry it, ordered by the tag. */
  public static Map<String, Object> tagCounts(List<Map<String, Object>> items) {
    var counts = new java.util.TreeMap<String, Long>();
    for (Map<String, Object> item : items) {
      for (Object tag : Json.asList(item.get("tags"))) {
        counts.merge(String.valueOf(tag), 1L, Long::sum);
      }
    }
    var out = new ArrayList<Map<String, Object>>(counts.size());
    counts.forEach((name, count) -> out.add(Json.map("name", name, "count", count)));
    return Json.map("tags", out);
  }

  // ------------------------------------------------------------------ search

  private static final Pattern TERM =
      Pattern.compile("(?:([^:\\s]+):)?(?:\"([^\"]+)\"|(\\S+))");

  private static final Pattern LEXEME = Pattern.compile("[\\p{L}\\p{N}_]+");

  /**
   * The multi-byte search: a conjunction of case-insensitive substring matches, with a
   * field prefix and a quoted phrase both honoured, ordered by identity.
   */
  public static List<Map<String, Object>> substringSearch(List<Map<String, Object>> items,
      String term) {
    var conditions = new ArrayList<Map.Entry<String, String>>();
    Matcher matcher = TERM.matcher(term);
    while (matcher.find()) {
      var key = matcher.group(1);
      var value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
      conditions.add(Map.entry(key == null ? "" : key, value));
    }
    var out = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> item : items) {
      boolean all = true;
      for (var condition : conditions) {
        if (!matches(item, condition.getKey(), condition.getValue())) {
          all = false;
          break;
        }
      }
      if (all) {
        out.add(item);
      }
    }
    out.sort(Comparator.comparing(item -> ((Number) item.getOrDefault("id", 0L)).longValue()));
    return out;
  }

  private static boolean matches(Map<String, Object> item, String key, String value) {
    var needle = value.toLowerCase(Locale.ROOT);
    return switch (key) {
      case "id" -> String.valueOf(item.get("id")).equals(value);
      case "name" -> contains(item.get("name"), needle);
      case "query" -> contains(item.get("query"), needle);
      case "description" -> contains(item.get("description"), needle);
      default -> contains(item.get("name"), needle) || contains(item.get("description"), needle);
    };
  }

  private static boolean contains(Object field, String needle) {
    return field != null && String.valueOf(field).toLowerCase(Locale.ROOT).contains(needle);
  }

  /**
   * The ranked full-text search (SPEC-001 D-7).
   *
   * <p>PostgreSQL's `simple` dictionary lower-cases a token and does nothing else — no
   * stemming, no stop words — so a lexeme here is the lower-cased run of letters, digits and
   * underscores. A document matches when it carries **every** lexeme of the term, and ranks
   * by the weights the source declares: the name is A, the identity B, the description C
   * and the query text D. A tie is broken by identity ascending, which the database leaves
   * unspecified.
   */
  public static List<Map<String, Object>> rankedSearch(List<Map<String, Object>> items,
      String term) {
    var wanted = lexemes(term);
    if (wanted.isEmpty()) {
      return List.of();
    }
    var scored = new ArrayList<Map.Entry<Double, Map<String, Object>>>();
    for (Map<String, Object> item : items) {
      Map<String, Double> weights = new LinkedHashMap<>();
      addWeights(weights, lexemes(text(item.get("name"))), 1.0);
      addWeights(weights, lexemes(String.valueOf(item.get("id"))), 0.4);
      addWeights(weights, lexemes(text(item.get("description"))), 0.2);
      addWeights(weights, lexemes(text(item.get("query"))), 0.1);
      double rank = 0;
      boolean all = true;
      for (String lexeme : wanted) {
        var weight = weights.get(lexeme);
        if (weight == null) {
          all = false;
          break;
        }
        rank += weight;
      }
      if (all) {
        scored.add(Map.entry(rank, item));
      }
    }
    scored.sort(Comparator
        .<Map.Entry<Double, Map<String, Object>>>comparingDouble(entry -> -entry.getKey())
        .thenComparingLong(entry -> ((Number) entry.getValue().getOrDefault("id", 0L)).longValue()));
    var out = new ArrayList<Map<String, Object>>(scored.size());
    for (var entry : scored) {
      out.add(entry.getValue());
    }
    return out;
  }

  private static void addWeights(Map<String, Double> weights, List<String> lexemes, double weight) {
    for (String lexeme : lexemes) {
      weights.merge(lexeme, weight, Math::max);
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  static List<String> lexemes(String text) {
    var out = new ArrayList<String>();
    Matcher matcher = LEXEME.matcher(text == null ? "" : text);
    while (matcher.find()) {
      out.add(matcher.group().toLowerCase(Locale.ROOT));
    }
    return out;
  }
}
