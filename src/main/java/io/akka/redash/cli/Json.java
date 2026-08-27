package io.akka.redash.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reading the shapes the command line prints, without repeating the casts. */
final class Json {

  private Json() {}

  static Map<String, Object> map(Object value) {
    return io.akka.redash.domain.Json.asMap(value);
  }

  static List<Object> list(Object value) {
    return io.akka.redash.domain.Json.asList(value);
  }

  static List<String> strings(Object value) {
    var out = new ArrayList<String>();
    for (Object item : list(value)) {
      out.add(String.valueOf(item));
    }
    return out;
  }

  static String compact(Object value) {
    return io.akka.redash.domain.Json.dumps(value);
  }

  /** The two-space indentation the source's `status` and `users list --json` print. */
  static String pretty(Object value) {
    var out = new StringBuilder();
    write(value, 0, out);
    return out.toString();
  }

  private static void write(Object value, int depth, StringBuilder out) {
    var pad = "  ".repeat(depth);
    var inner = "  ".repeat(depth + 1);
    if (value instanceof Map<?, ?> document) {
      if (document.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      int index = 0;
      for (var entry : document.entrySet()) {
        out.append(inner).append('"').append(entry.getKey()).append("\": ");
        write(entry.getValue(), depth + 1, out);
        if (++index < document.size()) {
          out.append(',');
        }
        out.append('\n');
      }
      out.append(pad).append('}');
      return;
    }
    if (value instanceof List<?> items) {
      if (items.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < items.size(); i++) {
        out.append(inner);
        write(items.get(i), depth + 1, out);
        if (i + 1 < items.size()) {
          out.append(',');
        }
        out.append('\n');
      }
      out.append(pad).append(']');
      return;
    }
    out.append(io.akka.redash.domain.Json.dumps(value));
  }
}
