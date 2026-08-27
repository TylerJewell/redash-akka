package io.akka.redash.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The template language a query's parameters and an alert's message are written in
 * (SPEC-001 R72, R73, R74).
 *
 * <p>Two renderers, differing only in whether a value is HTML-escaped: a query's text is
 * substituted unescaped, because it is going to a database and not to a browser, and an
 * alert's subject and body are escaped, because they are.
 *
 * <p>A value is rendered the way Python renders it, not the way Java does: {@code null}
 * becomes {@code None}, a boolean becomes {@code True} or {@code False}, and a list or a
 * map becomes its Python repr with single quotes. That is observable — a date-range
 * parameter used whole rather than through {@code .start} substitutes a Python dict into
 * somebody's SQL.
 */
public final class Mustache {

  private Mustache() {}

  // ---------------------------------------------------------------- parsing

  sealed interface Node {}

  record Literal(String text) implements Node {}

  /** A {@code {{key}}} placeholder; {@code escaped} is false for triple-stache and ampersand. */
  record Variable(String key, boolean escaped) implements Node {}

  record Section(String key, boolean inverted, List<Node> body) implements Node {}

  static List<Node> parse(String template) {
    var nodes = new ArrayList<Node>();
    parseInto(template, new int[] {0}, nodes, null);
    return nodes;
  }

  private static void parseInto(String template, int[] cursor, List<Node> out, String closing) {
    var literal = new StringBuilder();
    while (cursor[0] < template.length()) {
      int open = template.indexOf("{{", cursor[0]);
      if (open < 0) {
        literal.append(template, cursor[0], template.length());
        cursor[0] = template.length();
        break;
      }
      int close = template.indexOf("}}", open + 2);
      if (close < 0) {
        literal.append(template, cursor[0], template.length());
        cursor[0] = template.length();
        break;
      }
      literal.append(template, cursor[0], open);

      var raw = template.substring(open + 2, close);
      int end = close + 2;
      boolean triple = raw.startsWith("{") && template.startsWith("}", end);
      if (triple) {
        raw = raw.substring(1);
        end += 1;
      }
      cursor[0] = end;

      var trimmed = raw.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      char sigil = trimmed.charAt(0);
      var key = trimmed.length() > 1 ? trimmed.substring(1).trim() : "";
      switch (sigil) {
        case '!' -> {
          // a comment renders nothing at all
        }
        case '#', '^' -> {
          flush(literal, out);
          var body = new ArrayList<Node>();
          parseInto(template, cursor, body, key);
          out.add(new Section(key, sigil == '^', body));
        }
        case '/' -> {
          flush(literal, out);
          if (key.equals(closing)) {
            return;
          }
        }
        case '&' -> {
          flush(literal, out);
          out.add(new Variable(key, false));
        }
        default -> {
          flush(literal, out);
          out.add(new Variable(trimmed, !triple));
        }
      }
    }
    flush(literal, out);
  }

  private static void flush(StringBuilder literal, List<Node> out) {
    if (literal.length() > 0) {
      out.add(new Literal(literal.toString()));
      literal.setLength(0);
    }
  }

  // ---------------------------------------------------------------- rendering

  public static String render(String template, Map<String, Object> context) {
    return render(template, context, false);
  }

  public static String renderEscaped(String template, Map<String, Object> context) {
    return render(template, context, true);
  }

  private static String render(String template, Map<String, Object> context, boolean escape) {
    var out = new StringBuilder();
    render(parse(template), context == null ? Map.of() : context, escape, out);
    return out.toString();
  }

  /**
   * What a lookup answers when the name is not there at all. A name that *is* there and
   * holds nothing renders the word `None`, and a name that is not renders nothing, so the
   * two cannot be the same value.
   */
  static final Object ABSENT = new Object();

  private static void render(List<Node> nodes, Object context, boolean escape, StringBuilder out) {
    for (Node node : nodes) {
      switch (node) {
        case Literal literal -> out.append(literal.text());
        case Variable variable -> {
          var value = lookup(context, variable.key());
          if (value == ABSENT) {
            break;
          }
          var text = pythonString(value);
          out.append(variable.escaped() && escape ? escapeHtml(text) : text);
        }
        case Section section -> {
          var value = lookup(context, section.key());
          if (section.inverted()) {
            if (!truthy(value)) {
              render(section.body(), context, escape, out);
            }
            break;
          }
          if (!truthy(value)) {
            break;
          }
          if (value instanceof List<?> list) {
            for (Object item : list) {
              render(section.body(), item, escape, out);
            }
          } else {
            render(section.body(), value instanceof Map ? value : context, escape, out);
          }
        }
      }
    }
  }

  /** A dotted key walks maps; {@code .} is the item a list section is currently on. */
  static Object lookup(Object context, String key) {
    if (".".equals(key)) {
      return context;
    }
    Object node = context;
    for (String part : key.split("[.]")) {
      if (!(node instanceof Map<?, ?> map) || !map.containsKey(part)) {
        return ABSENT;
      }
      node = map.get(part);
    }
    return node;
  }

  static boolean truthy(Object value) {
    if (value == ABSENT) {
      return false;
    }
    return switch (value) {
      case null -> false;
      case Boolean bool -> bool;
      case CharSequence text -> !text.isEmpty();
      case List<?> list -> !list.isEmpty();
      case Map<?, ?> map -> !map.isEmpty();
      case Number number -> number.doubleValue() != 0;
      default -> true;
    };
  }

  /** How Python's {@code str()} writes a value, which is what reaches the query text. */
  public static String pythonString(Object value) {
    return switch (value) {
      case null -> "None";
      case Boolean bool -> bool ? "True" : "False";
      case CharSequence text -> text.toString();
      case List<?> list -> {
        var out = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
          out.append(i == 0 ? "" : ", ").append(pythonRepr(list.get(i)));
        }
        yield out.append("]").toString();
      }
      case Map<?, ?> map -> {
        var out = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
          out.append(first ? "" : ", ")
              .append(pythonRepr(entry.getKey()))
              .append(": ")
              .append(pythonRepr(entry.getValue()));
          first = false;
        }
        yield out.append("}").toString();
      }
      case Double d -> Numbers.pythonNumber(d);
      case Float f -> Numbers.pythonNumber(f.doubleValue());
      default -> String.valueOf(value);
    };
  }

  /** The same, but a string is quoted — which is what makes a list render with quotes. */
  static String pythonRepr(Object value) {
    if (value instanceof CharSequence text) {
      return "'" + text.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
    return pythonString(value);
  }

  static String escapeHtml(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;");
  }

  // ---------------------------------------------------------------- key collection

  /**
   * Which names the template needs supplying (SPEC-001 R74).
   *
   * <p>Escaped placeholders and section names count, and so do the keys inside a section —
   * but an inverted section does not, because the source's collector matches two node types
   * and an inverted section is neither. A triple-stache is not collected either, for the
   * same reason: it is an unescaped node. Both are surprising and both are the behaviour.
   */
  public static List<String> keys(String template) {
    var out = new LinkedHashSet<String>();
    collect(parse(template), out);
    return new ArrayList<>(out);
  }

  private static void collect(List<Node> nodes, LinkedHashSet<String> out) {
    for (Node node : nodes) {
      switch (node) {
        case Variable variable -> {
          if (variable.escaped()) {
            out.add(variable.key());
          }
        }
        case Section section -> {
          if (!section.inverted()) {
            out.add(section.key());
            collect(section.body(), out);
          }
        }
        default -> {}
      }
    }
  }

  /** The names a supplied parameter map contributes, where a map contributes `name.key`. */
  public static List<String> suppliedNames(Map<String, Object> parameters) {
    var out = new ArrayList<String>();
    for (var entry : parameters.entrySet()) {
      if (entry.getValue() instanceof Map<?, ?> map) {
        for (Object inner : map.keySet()) {
          out.add(entry.getKey() + "." + inner);
        }
      } else {
        out.add(entry.getKey());
      }
    }
    return out;
  }

  /** Small helper so a caller can build a context without importing a map type. */
  public static Map<String, Object> context(Object... pairs) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return out;
  }
}
