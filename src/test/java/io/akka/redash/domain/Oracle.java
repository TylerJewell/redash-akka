package io.akka.redash.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * What the original answered, kept where the tests can read it.
 *
 * <p>Every table under `src/test/resources/from-redash/` was printed by a probe run inside
 * a container of `getredash/redash` at the cloned commit, and is copied here unchanged
 * from `redash-port/probes/out/`. A test that asserts a hand-written answer has automated
 * the assertion and not the verification; these tests assert the original's own answer, so
 * a change in the source makes them fail rather than leaving a stale sentence in a
 * document.
 */
public final class Oracle {

  private Oracle() {}

  public static Map<String, Object> load(String name) {
    try (InputStream stream =
        Oracle.class.getClassLoader().getResourceAsStream("from-redash/" + name)) {
      if (stream == null) {
        throw new IllegalStateException("no recorded answers at from-redash/" + name);
      }
      var text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      return Json.asMap(Json.loads(text));
    } catch (IOException e) {
      throw new IllegalStateException("could not read from-redash/" + name, e);
    }
  }

  public static Map<String, Object> section(String file, String key) {
    return Json.asMap(load(file).get(key));
  }

  public static List<Object> rows(String file, String key) {
    return Json.asList(load(file).get(key));
  }
}
