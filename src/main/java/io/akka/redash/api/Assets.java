package io.akka.redash.api;

import io.akka.redash.domain.Json;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Where a bundled asset actually lives.
 *
 * <p>The front end's build writes hashed filenames and a manifest mapping the plain name to
 * the hashed one; the server-rendered pages ask for the plain name. A name the manifest does
 * not know is answered unchanged, which is what the source does and what makes a page still
 * render against an unbuilt front end.
 */
final class Assets {

  /** Read once, by the class loader, so two requests cannot both read the file. */
  private static final class Held {
    static final Map<String, Object> MANIFEST = load();
  }

  private Assets() {}

  static String resolve(String name) {
    var resolved = Held.MANIFEST.get(name);
    return resolved == null ? name : String.valueOf(resolved);
  }

  private static Map<String, Object> load() {
    try (var stream = Assets.class.getClassLoader()
        .getResourceAsStream("static-resources/static/asset-manifest.json")) {
      if (stream == null) {
        return Map.of();
      }
      var text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      return Json.asMap(Json.loads(text));
    } catch (java.io.IOException e) {
      return Map.of();
    }
  }
}
