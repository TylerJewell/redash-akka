package io.akka.redash.queryrunner;

import io.akka.redash.domain.Json;
import io.akka.redash.domain.Numbers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * What running a query answered: a table, or the reason there is none.
 *
 * <p>The pair is deliberately not an exception. The source's runners return
 * {@code (data, error)} and every caller reads both, because a runner that answers rows
 * *and* an error is a real case — a partial read from a paged API, for one — and an
 * exception cannot carry that.
 */
public record RunResult(List<Map<String, Object>> columns, List<Map<String, Object>> rows,
    String error) {

  public static RunResult of(List<Map<String, Object>> columns, List<Map<String, Object>> rows) {
    return new RunResult(columns, rows, null);
  }

  public static RunResult failed(String message) {
    return new RunResult(null, null, message);
  }

  public boolean isFailure() {
    return error != null && columns == null;
  }

  /** The document a stored result carries: exactly two keys, in this order. */
  public Map<String, Object> data() {
    return Json.map("columns", columns, "rows", rows);
  }

  /**
   * Name each column, resolving a repeat by suffixing a rising counter — so three columns
   * called {@code a} become {@code a}, {@code a1} and {@code a2}, and a row's keys stay
   * distinct where the database's did not.
   */
  public static List<Map<String, Object>> fetchColumns(List<String> names, List<String> types) {
    var seen = new LinkedHashSet<String>();
    var counters = new HashMap<String, Integer>();
    var out = new ArrayList<Map<String, Object>>(names.size());
    for (int i = 0; i < names.size(); i++) {
      var base = names.get(i);
      var name = base;
      while (seen.contains(name)) {
        int next = counters.merge(base, 1, Integer::sum);
        name = base + next;
      }
      seen.add(name);
      var column = new LinkedHashMap<String, Object>();
      column.put("name", name);
      column.put("friendly_name", name);
      column.put("type", types.get(i));
      out.add(column);
    }
    return out;
  }

  /** Build a result from values whose types have to be guessed, which is the HTTP case. */
  public static RunResult fromRows(List<String> names, List<List<Object>> values) {
    var types = new ArrayList<String>(names.size());
    for (int c = 0; c < names.size(); c++) {
      String type = null;
      for (List<Object> row : values) {
        var cell = c < row.size() ? row.get(c) : null;
        if (cell == null) {
          continue;
        }
        var guessed = Numbers.guessType(cell);
        if (type == null) {
          type = guessed;
        } else if (!type.equals(guessed)) {
          type = Numbers.TYPE_STRING;
          break;
        }
      }
      types.add(type == null ? Numbers.TYPE_STRING : type);
    }
    var columns = fetchColumns(names, types);
    var rows = new ArrayList<Map<String, Object>>(values.size());
    for (List<Object> value : values) {
      var row = new LinkedHashMap<String, Object>();
      for (int c = 0; c < columns.size(); c++) {
        row.put(String.valueOf(columns.get(c).get("name")), c < value.size() ? value.get(c) : null);
      }
      rows.add(row);
    }
    return of(columns, rows);
  }
}
