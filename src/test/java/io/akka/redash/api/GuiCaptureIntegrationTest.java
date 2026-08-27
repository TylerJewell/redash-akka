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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The rebuilt interface, seeded and photographed (RENDERING.md R1, R3, R5).
 *
 * <p>It runs only when `REDASH_CAPTURE` is set, because it needs a browser and takes
 * minutes: the eight screens the manifest declares are each loaded twice — once cold and
 * once warm, with the web fonts awaited — and then the page is left open for a minute with
 * the network log running, which is what R1.1 is checked against.
 *
 * <p>The same scripts run against the original. That is the whole point: a screen captured
 * by one program and compared against a screen captured by another compares two capture
 * scripts as much as two interfaces.
 */
class GuiCaptureIntegrationTest extends TestKitSupport {

  static final Path PROBES = Path.of("..", "redash-port", "probes", "gui");
  static final Path GUI = Path.of("..", "redash-port", "gui");

  @Test
  @EnabledIfEnvironmentVariable(named = "REDASH_CAPTURE", matches = ".+")
  void everyDeclaredScreenIsCapturedFromTheRebuild() throws Exception {
    var base = "http://127.0.0.1:" + testKit.getPort();

    var cli = new io.akka.redash.cli.Cli(
        new io.akka.redash.cli.Client(base,
            io.akka.redash.domain.Settings.fromEnvironment().secretKey()),
        new io.akka.redash.cli.Cli.Console(System.out, System.err));
    cli.run(List.of("database", "create_tables"));
    assertEquals(0, cli.run(List.of("users", "create_root", "admin@example.com", "Admin",
        "--password", "probe-password-1")));

    var seeded = run(List.of(python(), PROBES.resolve("seed.py").toString(), base,
        "--pg-host", orDefault("REDASH_PROBE_PG_HOST", "127.0.0.1"),
        "--pg-port", orDefault("REDASH_PROBE_PG_PORT", "26602")));
    assertEquals(0, seeded.code(), seeded.output());
    assertTrue(seeded.output().contains("'ok'") || seeded.output().contains("triggered"),
        "the seed did not leave the alerts in the states it says it did:\n" + seeded.output());

    var ported = GUI.resolve("ported");
    Files.createDirectories(ported);
    var captured = run(List.of(python(), PROBES.resolve("capture_all.py").toString(),
        base, ported.toAbsolutePath().toString(), "--twice"));
    assertEquals(0, captured.code(), captured.output());

    // Each screen is photographed twice here for the same reason the original's screens are:
    // a side that disagrees with itself makes every region the comparison later reports
    // unreadable. `toolkit/gui_audit.py --stability` is what reads the pairs.

    var idle = run(List.of(python(), PROBES.resolve("idle_probe.py").toString(),
        base, "/alerts", GUI.resolve("idle-network.json").toString(), "62"));
    assertEquals(0, idle.code(),
        "the page asked a data endpoint again while nobody touched it:\n" + idle.output());
    System.out.println(idle.output());
  }

  // ------------------------------------------------------------------ running a script

  record Outcome(int code, String output) {}

  private static String python() {
    var configured = System.getenv("PYTHON");
    return configured == null || configured.isBlank() ? "python" : configured;
  }

  private static String orDefault(String name, String fallback) {
    var value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
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
    if (!process.waitFor(30, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      return new Outcome(-1, String.join("\n", lines) + "\n(timed out)");
    }
    return new Outcome(process.exitValue(), String.join("\n", lines));
  }
}
