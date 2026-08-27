package io.akka.redash.destinations;

import io.akka.redash.domain.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One kind of place an alert can be sent, and whether this rebuild sends to it.
 *
 * <p>All twelve of redash's destinations are registered, because `/api/destinations/types`
 * is a wire format the front end draws its forms from and a shorter list is a different
 * answer. Seven of them post a message into somebody else's chat product, which this run
 * excludes; those carry {@code delivers = false} and their `notify` does nothing. The other
 * five deliver (SPEC-001 D-5).
 */
public record DestinationType(
    String type,
    String name,
    String icon,
    boolean delivers,
    Map<String, Object> configurationSchema) {

  /** What `/api/destinations/types` answers for this type. */
  public Map<String, Object> asDocument() {
    var out = new LinkedHashMap<String, Object>();
    out.put("name", name);
    out.put("type", type);
    out.put("icon", icon);
    out.put("configuration_schema", configurationSchema);
    return out;
  }

  public boolean accepts(Map<String, Object> options) {
    return io.akka.redash.domain.Configuration.isValid(options, configurationSchema);
  }

  public Map<String, Object> mask(Map<String, Object> options) {
    return io.akka.redash.domain.Configuration.mask(options, configurationSchema);
  }

  /** A short summary, for a caller that only wants to name the type. */
  public Map<String, Object> summary() {
    return Json.map("type", type, "name", name, "icon", icon, "delivers", delivers);
  }
}
