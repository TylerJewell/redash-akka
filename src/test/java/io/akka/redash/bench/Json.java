package io.akka.redash.bench;

import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to write the benchmark's answers.
 *
 * <p>The comparison reads these with Python, so what matters is that the shapes match the
 * source side's — a list of strings for a sequence, a list of `{case, answer}` objects for
 * a table. Nothing here parses.
 */
final class Json {

  static String write(Object value) {
    var out = new StringBuilder();
    write(value, out, 0);
    out.append('\n');
    return out.toString();
  }

  private static void write(Object value, StringBuilder out, int depth) {
    var pad = "  ".repeat(depth + 1);
    var closePad = "  ".repeat(depth);
    switch (value) {
      case null -> out.append("null");
      case String s -> quote(s, out);
      case Boolean b -> out.append(b);
      case Number n -> out.append(n);
      case Map<?, ?> map -> {
        if (map.isEmpty()) {
          out.append("{}");
          return;
        }
        out.append("{\n");
        boolean first = true;
        for (var entry : map.entrySet()) {
          if (!first) {
            out.append(",\n");
          }
          first = false;
          out.append(pad);
          quote(String.valueOf(entry.getKey()), out);
          out.append(": ");
          write(entry.getValue(), out, depth + 1);
        }
        out.append('\n').append(closePad).append('}');
      }
      case List<?> list -> {
        if (list.isEmpty()) {
          out.append("[]");
          return;
        }
        out.append("[\n");
        boolean first = true;
        for (Object item : list) {
          if (!first) {
            out.append(",\n");
          }
          first = false;
          out.append(pad);
          write(item, out, depth + 1);
        }
        out.append('\n').append(closePad).append(']');
      }
      default -> quote(String.valueOf(value), out);
    }
  }

  private static void quote(String value, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private Json() {}
}
