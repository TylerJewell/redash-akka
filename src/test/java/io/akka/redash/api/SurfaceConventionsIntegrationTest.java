package io.akka.redash.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The parts of the surface a JSON body does not show, compared against the original
 * (SPEC-001 R11, R12, R16, R17, R18, R147, R151, R152).
 *
 * <p>The walk compares statuses and documents. It cannot compare who a request was
 * attributed to when the answer is the same either way, nor the headers every answer
 * carries, nor what happens to a page request that arrives without a session — those are
 * decided before a handler runs and are invisible in a body. `probes/probe_16_surface.py`
 * asked the running original all of them and its answers are committed at
 * `src/test/resources/from-redash/probe_16_surface.json`; `bench/replay_surface.py` puts the
 * same questions to this side and compares. `probes/probe_17_login.py` does the same for
 * three families the walk cannot reach at all, because the walk signs in once and stays
 * signed in: the refusals the login page makes and their exact wording, the reduction a
 * `next` parameter goes through over fourteen shapes, and where the cross-site guard
 * actually bites.
 *
 * <p>One of those answers is a surprise worth naming: redash's CSRF enforcement is off in
 * the deployment its own compose file produces, so a session-authenticated POST carrying no
 * token, and one carrying a wrong token, both succeed. The token cookie is still set on
 * every response. This side does the same, and the recorded answers are why.
 */
class SurfaceConventionsIntegrationTest extends TestKitSupport {

  static final Path BENCH = Path.of("..", "redash-port", "bench");
  static final Path RECORDED =
      Path.of("src", "test", "resources", "from-redash", "probe_16_surface.json");

  @Test
  void theConventionsAroundEveryAnswerMatchTheOriginal() throws Exception {
    var base = "http://127.0.0.1:" + testKit.getPort();
    var cli = new io.akka.redash.cli.Cli(
        new io.akka.redash.cli.Client(base,
            io.akka.redash.domain.Settings.fromEnvironment().secretKey()),
        new io.akka.redash.cli.Cli.Console(System.out, System.err));
    cli.run(List.of("database", "create_tables"));
    assertEquals(0, cli.run(List.of("users", "create_root", "admin@example.com", "Admin",
        "--password", "probe-password-1")));

    assertTrue(Files.exists(RECORDED),
        "no recorded answers from the original at " + RECORDED.toAbsolutePath());

    var replay = run(List.of(python(), BENCH.resolve("replay_surface.py").toString(),
        base, RECORDED.toString(),
        "--json", BENCH.resolve("port-surface.json").toString()));
    assertEquals(0, replay.code(),
        "the two systems answered differently:\n" + replay.output());
    System.out.println(replay.output());

    // Probe 17's three families: what the login page refuses and in what words, what a
    // `next` parameter is reduced to over fourteen shapes, and where the cross-site guard
    // actually bites. None of them is reachable from the walk, which signs in once and
    // stays signed in.
    var login = Path.of("src", "test", "resources", "from-redash", "probe_17_login.json");
    assertTrue(Files.exists(login),
        "no recorded answers from the original at " + login.toAbsolutePath());
    var replayedLogin = run(List.of(python(), BENCH.resolve("replay_login.py").toString(),
        base, login.toString(),
        "--json", BENCH.resolve("port-login.json").toString()));
    assertEquals(0, replayedLogin.code(),
        "the two systems answered differently:" + System.lineSeparator()
            + replayedLogin.output());
    System.out.println(replayedLogin.output());
  }

  record Outcome(int code, String output) {}

  private static String python() {
    var configured = System.getenv("PYTHON");
    return configured == null || configured.isBlank() ? "python" : configured;
  }

  private static Outcome run(List<String> command) throws IOException, InterruptedException {
    var builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    var process = builder.start();
    var lines = new ArrayList<String>();
    try (var reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    if (!process.waitFor(10, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      return new Outcome(-1, String.join("\n", lines) + "\n(timed out)");
    }
    return new Outcome(process.exitValue(), String.join("\n", lines));
  }
}
