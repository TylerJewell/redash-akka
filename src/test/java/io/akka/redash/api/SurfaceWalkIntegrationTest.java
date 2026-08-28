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
 * The whole HTTP surface, walked in one ordered sequence and compared against the same walk
 * over the original (SPEC-001 R149).
 *
 * <p>The walk is a sequence rather than a table because most of what redash decides depends
 * on what it has already been told — a query's version, an alert's previous state, whether a
 * favourite already exists, whether a group already holds a data source. A table of
 * independent requests would agree on every row and have compared nothing anybody worried
 * about.
 *
 * <p>One driver runs both sides. `bench/walk.py` is pointed at an address and knows nothing
 * about which system is behind it, so a difference in the transcript is a difference between
 * the systems rather than between two programs' idea of what a step means.
 *
 * <p>The transcript of the original is committed at `bench/source-walk.json`, captured from
 * redash running under its own compose file. Where that file is absent the walk still runs
 * and the comparison is skipped with a message, because a benchmark that quietly passes
 * when its baseline is missing is worse than one that says so.
 *
 * <p>The periodic jobs are switched off for every integration test — REDASH_DISABLE_SCHEDULER
 * in the build — and that is deliberate rather than convenient. The walk takes minutes on
 * this side and under a minute on the original, so a job that runs every minute fires here
 * and not there, and the two transcripts then disagree about a field that moved because of
 * elapsed time rather than because of a decision. PeriodicJobsIntegrationTest drives each of
 * the eight jobs directly instead, which is what compares them.
 */
class SurfaceWalkIntegrationTest extends TestKitSupport {

  static final Path BENCH = Path.of("..", "redash-port", "bench");

  @Test
  void theWholeSurfaceAnswersAsTheOriginalDoes() throws Exception {
    // Set the instance up the way the original's own stack is set up: through the command
    // line, before the walk starts. Doing it through the setup *page* instead would sign
    // the new administrator in, and the original's `manage.py users create_root` does not —
    // so the two event logs would differ by one login for a reason that is about how the
    // instance was installed rather than about either system.
    var cli = new io.akka.redash.cli.Cli(
        new io.akka.redash.cli.Client("http://127.0.0.1:" + testKit.getPort(),
            io.akka.redash.domain.Settings.fromEnvironment().secretKey()),
        new io.akka.redash.cli.Cli.Console(System.out, System.err));
    // A POST arriving in the runtime's first few tens of milliseconds is answered before
    // its request stream is finished with, and the client sees the connection close rather
    // than the answer. `database create_tables` creates nothing, so it can be repeated
    // until two in a row get through and the window is behind us.
    int inARow = 0;
    for (int attempt = 0; attempt < 100 && inARow < 2; attempt++) {
      inARow = cli.run(List.of("database", "create_tables")) == 0 ? inARow + 1 : 0;
    }
    assertEquals(2, inARow, "the runtime never answered two POSTs in a row");
    assertEquals(0, cli.run(List.of("users", "create_root", "admin@example.com", "Admin",
        "--password", "probe-password-1")));

    var transcript = BENCH.resolve("port-walk.json");
    // Durations rather than timings: each is one whole request timed once, which is a
    // different measurement from the windowed per-operation figures in `timings.json` and
    // is not checked by the same rules.
    var timings = BENCH.resolve("port-walk-durations.json");
    Files.deleteIfExists(transcript);

    // The address is written as an IPv4 literal rather than as `localhost`. The test kit
    // binds 127.0.0.1 only, and a client that resolves `localhost` tries ::1 first and waits
    // for that connection to fail — which on this platform costs about two seconds, on every
    // request, and would have been recorded as the rebuild being seventy times slower than
    // the original per request rather than as the name resolution it is.
    var walk = run(List.of(python(), BENCH.resolve("walk.py").toString(),
        "http://127.0.0.1:" + testKit.getPort(), transcript.toString(), timings.toString()));
    assertEquals(0, walk.code(), "the walk did not finish:\n" + walk.output());
    assertTrue(Files.exists(transcript), "the walk wrote no transcript");

    var recorded = Files.readString(transcript, StandardCharsets.UTF_8);
    assertTrue(recorded.contains("\"n\": 187"), "the walk stopped short of its last step");

    var baseline = BENCH.resolve("source-walk.json");
    if (!Files.exists(baseline)) {
      System.out.println("no transcript of the original at " + baseline.toAbsolutePath()
          + " — the surface comparison did not run");
      return;
    }
    var comparison = run(List.of(python(), BENCH.resolve("compare_walk.py").toString(),
        baseline.toString(), transcript.toString(),
        "--json", BENCH.resolve("walk-comparison.json").toString()));
    assertEquals(0, comparison.code(),
        "the two systems answered differently:\n" + comparison.output());
  }

  // ------------------------------------------------------------------ running the driver

  record Outcome(int code, String output) {}

  private static String python() {
    var configured = System.getenv("PYTHON");
    return configured == null || configured.isBlank() ? "python" : configured;
  }

  private static Outcome run(List<String> command) throws IOException, InterruptedException {
    var builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    // The two systems reach the same PostgreSQL by different addresses; the original from
    // inside its own compose network, this side from the host that published it.
    builder.environment().put("REDASH_PROBE_PG_HOST",
        orDefault("REDASH_PROBE_PG_HOST", "127.0.0.1"));
    builder.environment().put("REDASH_PROBE_PG_PORT",
        orDefault("REDASH_PROBE_PG_PORT", "26602"));
    var process = builder.start();
    var lines = new ArrayList<String>();
    try (var reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    if (!process.waitFor(20, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      return new Outcome(-1, String.join("\n", lines) + "\n(timed out)");
    }
    return new Outcome(process.exitValue(), String.join("\n", lines));
  }

  private static String orDefault(String name, String fallback) {
    var value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
