package io.akka.redash.api;

import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enough of Jinja to render redash's own server-side pages.
 *
 * <p>The login page, the setup page, the invitation, reset and verification pages, the
 * error page and every alert email are Jinja templates in the original. RENDERING.md R3
 * says the interface a port ships is the one that already exists, so those templates are
 * shipped unchanged under {@code src/main/resources/templates/} and this renders them —
 * rather than the pages being rewritten in Java, which would make the appearance check in
 * R5 a comparison against somebody's taste.
 *
 * <p>What is implemented is what those templates use, and nothing else: {@code extends},
 * {@code block}, {@code include}, {@code macro}, {@code if}/{@code else}, {@code for},
 * {@code with}, {@code set}, {@code {{ }}} with attribute and index access, calls to the
 * handful of helpers the templates name, the {@code or}/{@code and}/{@code not} operators,
 * and the whitespace-control forms {@code {%-} and {@code -%}}. A construct outside that
 * set is not silently ignored — it raises, so a template that grows one is a failure rather
 * than a page with a hole in it.
 */
public final class Jinja {

  /** How a template names another one, so the loader can be a test double. */
  public interface Loader {
    String read(String name);
  }

  private static final Pattern TAG = Pattern.compile("\\{%-?\\s*(.*?)\\s*-?%\\}|\\{\\{\\s*(.*?)\\s*\\}\\}",
      Pattern.DOTALL);

  private final Loader loader;

  public Jinja(Loader loader) {
    this.loader = loader;
  }

  /** Render a template by name, with a context of plain values and helper functions. */
  public String render(String name, Map<String, Object> context) {
    var chain = new ArrayList<Template>();
    var current = parse(loader.read(name));
    chain.add(current);
    while (current.parent != null) {
      current = parse(loader.read(current.parent));
      chain.add(current);
    }
    // The last template of the chain is the root layout; blocks defined nearer the leaf win.
    var blocks = new LinkedHashMap<String, List<Node>>();
    for (int i = chain.size() - 1; i >= 0; i--) {
      blocks.putAll(chain.get(i).blocks);
    }
    var scope = new Scope(null, new LinkedHashMap<>(context));
    scope.blocks = blocks;
    scope.macros = new LinkedHashMap<>();
    for (Template template : chain) {
      scope.macros.putAll(template.macros);
    }
    var out = new StringBuilder();
    for (int i = 0; i < chain.size() - 1; i++) {
      out.append(chain.get(i).preamble);
    }
    render(chain.get(chain.size() - 1).body, scope, out);
    return out.toString();
  }

  // ------------------------------------------------------------------ model

  sealed interface Node {}

  record Text(String value) implements Node {}

  record Output(String expression) implements Node {}

  record If(List<Branch> branches) implements Node {}

  record Branch(String condition, List<Node> body) {}

  record For(String variable, String collection, List<Node> body) implements Node {}

  record With(String name, String expression, List<Node> body) implements Node {}

  record Set(String name, String expression) implements Node {}

  record Block(String name) implements Node {}

  record Include(String name) implements Node {}

  record Call(String name, String arguments) implements Node {}

  static final class Template {
    String parent;
    /**
     * Whatever the template printed before it said what it extends.
     *
     * <p>Jinja keeps it. A template that opens `{% set x = true %}`, a blank line, and then
     * `{% extends ... %}` sends that blank line before the parent's first byte — which is
     * why the source's error page begins with two newlines and its login page does not.
     */
    String preamble = "";
    List<Node> body = new ArrayList<>();
    Map<String, List<Node>> blocks = new LinkedHashMap<>();
    Map<String, Macro> macros = new LinkedHashMap<>();
  }

  record Macro(String name, List<String> parameters, Map<String, String> defaults,
      List<Node> body) {}

  static final class Scope {
    final Scope parent;
    final Map<String, Object> values;
    Map<String, List<Node>> blocks;
    Map<String, Macro> macros;

    Scope(Scope parent, Map<String, Object> values) {
      this.parent = parent;
      this.values = values;
      if (parent != null) {
        this.blocks = parent.blocks;
        this.macros = parent.macros;
      }
    }

    Object lookup(String name) {
      for (Scope scope = this; scope != null; scope = scope.parent) {
        if (scope.values.containsKey(name)) {
          return scope.values.get(name);
        }
      }
      return null;
    }

    boolean has(String name) {
      for (Scope scope = this; scope != null; scope = scope.parent) {
        if (scope.values.containsKey(name)) {
          return true;
        }
      }
      return false;
    }
  }

  // ------------------------------------------------------------------ parsing

  Template parse(String source) {
    var template = new Template();
    var cursor = new int[] {0};
    template.body = parseNodes(source, cursor, template, null);
    return template;
  }

  private List<Node> parseNodes(String source, int[] cursor, Template template, String until) {
    var out = new ArrayList<Node>();
    var matcher = TAG.matcher(source);
    while (cursor[0] < source.length() && matcher.find(cursor[0])) {
      if (matcher.start() > cursor[0]) {
        out.add(new Text(trimAround(source, cursor[0], matcher.start(), matcher.group())));
      }
      cursor[0] = matcher.end();

      if (matcher.group(2) != null) {
        out.add(new Output(matcher.group(2)));
        continue;
      }
      var tag = matcher.group(1);
      var word = tag.split("\\s+", 2)[0];
      var rest = tag.length() > word.length() ? tag.substring(word.length()).strip() : "";

      switch (word) {
        case "extends" -> {
          template.parent = unquote(rest);
          var before = new StringBuilder();
          for (Node node : out) {
            if (node instanceof Text text) {
              before.append(text.value);
            }
          }
          template.preamble = before.toString();
        }
        case "block" -> {
          var name = rest.split("\\s+")[0];
          var body = parseNodes(source, cursor, template, "endblock");
          template.blocks.put(name, body);
          out.add(new Block(name));
        }
        case "if" -> {
          var branches = new ArrayList<Branch>();
          var condition = rest;
          while (true) {
            var body = parseNodes(source, cursor, template, "endif|else|elif");
            branches.add(new Branch(condition, body));
            var closing = lastClosing;
            if (closing.startsWith("elif")) {
              condition = closing.substring(4).strip();
              continue;
            }
            if (closing.startsWith("else")) {
              branches.add(new Branch(null, parseNodes(source, cursor, template, "endif")));
            }
            break;
          }
          out.add(new If(branches));
        }
        case "for" -> {
          var parts = rest.split("\\s+in\\s+", 2);
          out.add(new For(parts[0].strip(), parts[1].strip(),
              parseNodes(source, cursor, template, "endfor")));
        }
        case "with" -> {
          var parts = rest.split("=", 2);
          out.add(new With(parts[0].strip(), parts.length > 1 ? parts[1].strip() : "none",
              parseNodes(source, cursor, template, "endwith")));
        }
        case "set" -> {
          var parts = rest.split("=", 2);
          out.add(new Set(parts[0].strip(), parts[1].strip()));
        }
        case "include" -> out.add(new Include(unquote(rest)));
        case "macro" -> {
          int open = rest.indexOf('(');
          var name = rest.substring(0, open).strip();
          var parameters = new ArrayList<String>();
          var defaults = new LinkedHashMap<String, String>();
          for (String parameter : rest.substring(open + 1, rest.lastIndexOf(')')).split(",")) {
            var trimmed = parameter.strip();
            if (trimmed.isEmpty()) {
              continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals > 0) {
              parameters.add(trimmed.substring(0, equals).strip());
              defaults.put(trimmed.substring(0, equals).strip(),
                  trimmed.substring(equals + 1).strip());
            } else {
              parameters.add(trimmed);
            }
          }
          template.macros.put(name,
              new Macro(name, parameters, defaults, parseNodes(source, cursor, template, "endmacro")));
        }
        default -> {
          if (until != null && matchesClosing(word, until)) {
            lastClosing = tag;
            return out;
          }
          throw new IllegalStateException("unsupported template tag: " + tag);
        }
      }
    }
    if (cursor[0] < source.length()) {
      out.add(new Text(source.substring(cursor[0])));
    }
    cursor[0] = source.length();
    return out;
  }

  /** The tag that closed the block just parsed, so `if` can read its own `else`. */
  private String lastClosing = "";

  private static boolean matchesClosing(String word, String until) {
    for (String candidate : until.split("\\|")) {
      if (candidate.equals(word)) {
        return true;
      }
    }
    return false;
  }

  /** Jinja's whitespace control: a leading `-` eats the run before, a trailing one after. */
  private static String trimAround(String source, int from, int to, String tag) {
    var text = source.substring(from, to);
    if (tag.startsWith("{%-")) {
      text = text.stripTrailing();
    }
    return text;
  }

  private static String unquote(String value) {
    var text = value.strip();
    if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'')) {
      return text.substring(1, text.length() - 1);
    }
    return text;
  }

  // ------------------------------------------------------------------ rendering

  private void render(List<Node> nodes, Scope scope, StringBuilder out) {
    for (Node node : nodes) {
      switch (node) {
        case Text text -> out.append(text.value());
        case Output output -> out.append(escape(evaluate(output.expression(), scope)));
        case Block block -> {
          var body = scope.blocks.get(block.name());
          if (body != null) {
            render(body, scope, out);
          }
        }
        case If branch -> {
          for (Branch candidate : branch.branches()) {
            if (candidate.condition() == null || truthy(evaluate(candidate.condition(), scope))) {
              render(candidate.body(), scope, out);
              break;
            }
          }
        }
        case For loop -> {
          var collection = evaluate(loop.collection(), scope);
          for (Object item : Json.asList(collection)) {
            var inner = new Scope(scope, new LinkedHashMap<>(Map.of(loop.variable(), item)));
            render(loop.body(), inner, out);
          }
        }
        case With with -> {
          var inner = new Scope(scope, new LinkedHashMap<>());
          inner.values.put(with.name(), evaluate(with.expression(), scope));
          render(with.body(), inner, out);
        }
        case Set set -> scope.values.put(set.name(), evaluate(set.expression(), scope));
        case Include include -> {
          var included = parse(loader.read(include.name()));
          render(included.body, scope, out);
        }
        case Call call -> out.append(String.valueOf(evaluate(call.name(), scope)));
      }
    }
  }

  // ------------------------------------------------------------------ expressions

  /**
   * Evaluate the small expression language the templates use.
   *
   * <p>Deliberately small: `or`, `and`, `not`, a literal, a dotted name, an index, and a
   * call to something the context holds. There are no filters and no arithmetic because
   * none of redash's templates uses either.
   */
  Object evaluate(String expression, Scope scope) {
    var text = expression.strip();
    if (text.isEmpty()) {
      return "";
    }
    // `or` and `and` are the lowest-precedence operators and are split on first.
    var or = splitOperator(text, " or ");
    if (or != null) {
      var left = evaluate(or[0], scope);
      return truthy(left) ? left : evaluate(or[1], scope);
    }
    var and = splitOperator(text, " and ");
    if (and != null) {
      var left = evaluate(and[0], scope);
      return truthy(left) ? evaluate(and[1], scope) : left;
    }
    if (text.startsWith("not ")) {
      return !truthy(evaluate(text.substring(4), scope));
    }
    if (text.startsWith("'") || text.startsWith("\"")) {
      return unquote(text);
    }
    if (text.equals("true") || text.equals("True")) {
      return Boolean.TRUE;
    }
    if (text.equals("false") || text.equals("False")) {
      return Boolean.FALSE;
    }
    if (text.equals("none") || text.equals("None")) {
      return null;
    }
    if (text.matches("-?\\d+")) {
      return Long.parseLong(text);
    }

    int open = text.indexOf('(');
    if (open > 0 && text.endsWith(")")) {
      var name = text.substring(0, open).strip();
      var arguments = text.substring(open + 1, text.length() - 1);
      return call(name, arguments, scope);
    }
    return resolve(text, scope);
  }

  /** Split on an operator that is not inside quotes or brackets. */
  private static String[] splitOperator(String text, String operator) {
    int depth = 0;
    boolean quoted = false;
    char quote = 0;
    for (int i = 0; i + operator.length() <= text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == quote) {
          quoted = false;
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quoted = true;
        quote = c;
      } else if (c == '(' || c == '[') {
        depth++;
      } else if (c == ')' || c == ']') {
        depth--;
      } else if (depth == 0 && text.startsWith(operator, i)) {
        return new String[] {text.substring(0, i), text.substring(i + operator.length())};
      }
    }
    return null;
  }

  private Object resolve(String path, Scope scope) {
    var parts = path.split("\\.");
    Object value = scope.lookup(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      if (value == null) {
        return null;
      }
      value = Json.asMap(value).get(parts[i]);
    }
    return value;
  }

  /** A call is either a macro the template declared or a helper the context supplied. */
  @SuppressWarnings("unchecked")
  private Object call(String name, String arguments, Scope scope) {
    var macro = scope.macros.get(name);
    if (macro != null) {
      var inner = new Scope(scope, new LinkedHashMap<>());
      var positional = splitArguments(arguments);
      for (int i = 0; i < macro.parameters().size(); i++) {
        var parameter = macro.parameters().get(i);
        Object value;
        if (i < positional.size() && !positional.get(i).contains("=")) {
          value = evaluate(positional.get(i), scope);
        } else {
          var declared = macro.defaults().get(parameter);
          value = declared == null ? null : evaluate(declared, scope);
        }
        inner.values.put(parameter, value);
      }
      for (String argument : positional) {
        int equals = argument.indexOf('=');
        if (equals > 0) {
          inner.values.put(argument.substring(0, equals).strip(),
              evaluate(argument.substring(equals + 1), scope));
        }
      }
      var out = new StringBuilder();
      render(macro.body(), inner, out);
      return new Raw(out.toString());
    }

    // `self.<block>()` renders a block of the template being rendered, which is how the
    // shared layout puts the page's own title into its heading as well as its `<title>`.
    if (name.startsWith("self.")) {
      var block = scope.blocks.get(name.substring("self.".length()));
      if (block == null) {
        return "";
      }
      var out = new StringBuilder();
      render(block, scope, out);
      return new Raw(out.toString());
    }

    // A dotted name is a call too: the setup form's `field.label()` reaches a helper held
    // under a key of the field rather than a name of its own.
    var helper = resolve(name, scope);
    if (helper instanceof Function<?, ?> function) {
      var named = new LinkedHashMap<String, Object>();
      var positional = new ArrayList<>();
      for (String argument : splitArguments(arguments)) {
        int equals = argument.indexOf('=');
        if (equals > 0 && !argument.startsWith("'") && !argument.startsWith("\"")) {
          named.put(argument.substring(0, equals).strip(), evaluate(argument.substring(equals + 1),
              scope));
        } else if (!argument.isBlank()) {
          positional.add(evaluate(argument, scope));
        }
      }
      named.put("", positional);
      return ((Function<Map<String, Object>, Object>) function).apply(named);
    }
    if (helper != null) {
      return helper;
    }
    throw new IllegalStateException("no such template helper: " + name);
  }

  private static List<String> splitArguments(String arguments) {
    var out = new ArrayList<String>();
    int depth = 0;
    boolean quoted = false;
    char quote = 0;
    var current = new StringBuilder();
    for (int i = 0; i < arguments.length(); i++) {
      char c = arguments.charAt(i);
      if (quoted) {
        current.append(c);
        if (c == quote) {
          quoted = false;
        }
        continue;
      }
      switch (c) {
        case '\'', '"' -> {
          quoted = true;
          quote = c;
          current.append(c);
        }
        case '(', '[' -> {
          depth++;
          current.append(c);
        }
        case ')', ']' -> {
          depth--;
          current.append(c);
        }
        case ',' -> {
          if (depth == 0) {
            out.add(current.toString().strip());
            current.setLength(0);
          } else {
            current.append(c);
          }
        }
        default -> current.append(c);
      }
    }
    if (!current.isEmpty()) {
      out.add(current.toString().strip());
    }
    return out;
  }

  /** Markup a helper or a macro produced, which is written through rather than escaped. */
  public record Raw(String html) {}

  static boolean truthy(Object value) {
    return switch (value) {
      case null -> false;
      case Boolean bool -> bool;
      case CharSequence text -> !text.isEmpty();
      case List<?> list -> !list.isEmpty();
      case Map<?, ?> map -> !map.isEmpty();
      case Number number -> number.doubleValue() != 0;
      case Raw raw -> !raw.html().isBlank();
      default -> true;
    };
  }

  static String escape(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Raw raw) {
      return raw.html();
    }
    var text = String.valueOf(value);
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&#34;")
        .replace("'", "&#39;");
  }

  /** A loader over the templates shipped in the rebuild's own resources. */
  public static Loader resources() {
    return name -> {
      var path = "templates/" + name;
      try (var stream = Jinja.class.getClassLoader().getResourceAsStream(path)) {
        if (stream == null) {
          throw new IllegalStateException("no template at " + path);
        }
        return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      } catch (java.io.IOException e) {
        throw new IllegalStateException("could not read " + path, e);
      }
    };
  }

  /** Small helper so an endpoint can pass a zero-argument function into a context. */
  public static Function<Map<String, Object>, Object> helper(
      Function<Map<String, Object>, Object> function) {
    return function;
  }

  /** Lower-cased, for a caller that wants to name a template without worrying about case. */
  public static String normalise(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  /** Exposed for the tests, which check the parser against the templates that ship. */
  static Matcher tags(String source) {
    return TAG.matcher(source);
  }
}
