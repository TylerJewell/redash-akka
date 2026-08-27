package io.akka.redash.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One field of the setup form, as its template addresses it.
 *
 * <p>The source's templates use a form library whose fields are both callable and
 * attributed — `{{ field.label() }}`, `{{ field(class='form-control') }}`, `field.errors` —
 * and the templates are shipped unchanged, so a field here has to be both a map and a
 * function. That is the whole reason for this class: it is a shape the vendored templates
 * require, not a design.
 */
final class FormField extends LinkedHashMap<String, Object>
    implements Function<Map<String, Object>, Object> {

  private final String name;
  private final String label;
  private final String type;
  private final Object value;

  FormField(String name, String label, String type, Object value, List<String> errors) {
    this.name = name;
    this.label = label;
    this.type = type;
    this.value = value;
    put("id", name);
    put("name", name);
    put("data", value);
    put("errors", errors);
    put("label", (Function<Map<String, Object>, Object>) arguments ->
        new Jinja.Raw("<label for=\"" + name + "\">" + escape(label) + "</label>"));
  }

  /** The input element itself, with whatever attributes the template passed. */
  @Override
  public Object apply(Map<String, Object> arguments) {
    var out = new StringBuilder("<input");
    var attributes = new LinkedHashMap<String, Object>();
    attributes.put("id", name);
    attributes.put("name", name);
    attributes.put("type", type);
    arguments.forEach((key, argument) -> {
      if (!key.isEmpty()) {
        attributes.put("class".equals(key) ? "class" : key, argument);
      }
    });
    if ("checkbox".equals(type)) {
      attributes.put("value", "y");
      if (Boolean.TRUE.equals(value)) {
        attributes.put("checked", "");
      }
    } else {
      attributes.put("value", value == null ? "" : value);
    }
    attributes.forEach((key, argument) -> {
      out.append(' ').append(key);
      if (argument != null && !String.valueOf(argument).isEmpty()) {
        out.append("=\"").append(escape(String.valueOf(argument))).append('"');
      }
    });
    return new Jinja.Raw(out.append('>').toString());
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&#34;");
  }
}
