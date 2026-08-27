package io.akka.redash.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The command line, driven the way somebody at a terminal drives it (SPEC-001 R148).
 *
 * <p>Every command runs against a real service and its printed output is compared with what
 * the source prints, wording for wording — `Creating root user (…) in organization
 * default...`, `User is already an admin.`, `Deleted 1 users.` A command line is read by
 * people and by scripts, so the text is the interface.
 *
 * <p>It is one method in one order because an instance can only be set up once, and because
 * most of these commands are about what the one before them left behind — a grant that
 * answers "already an admin", a delete that answers "0 users" the second time.
 */
class CliIntegrationTest extends TestKitSupport {

  private record Run(int code, String out, String err) {}

  private Run run(String... arguments) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var cli = new Cli(
        new Client("http://127.0.0.1:" + testKit.getPort(), secret()),
        new Cli.Console(new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)));
    int code = cli.run(List.of(arguments));
    return new Run(code, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  /**
   * Run a command, and try again while what came back is the connection rather than an
   * answer.
   *
   * <p>A runtime that answers a POST before its request stream is finished with closes the
   * connection, and the command line deliberately does not re-send a POST — re-sending one
   * that changes something could carry it out twice. So the retry lives here, where it can
   * tell the two apart, and it has to: `Could not reach …` leaves exit code 1, which is
   * also what a refusal leaves, and a wait-for-code-1 loop would otherwise settle on the
   * transport failure and compare its wording against redash's.
   */
  private Run reached(String... arguments) {
    Run last = null;
    for (int attempt = 0; attempt < 20; attempt++) {
      last = run(arguments);
      if (!last.out().contains(UNREACHABLE) && !last.err().contains(UNREACHABLE)) {
        return last;
      }
    }
    return last;
  }

  private static final String UNREACHABLE = "Could not reach ";

  private static String secret() {
    return io.akka.redash.domain.Settings.fromEnvironment().secretKey();
  }

  /**
   * Wait for a command to answer what it will settle on.
   *
   * <p>Several of these read a list back through a view, which is updated after the write
   * rather than with it. What is being checked is the wording and the decision, and neither
   * changes once the view has caught up.
   */
  private Run settled(java.util.function.Predicate<Run> until, String... arguments) {
    Run last = null;
    for (int attempt = 0; attempt < 50; attempt++) {
      last = reached(arguments);
      if (until.test(last)) {
        return last;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return last;
      }
    }
    return last;
  }

  @Test
  void theWholeCommandLine() {
    // A POST arriving in the runtime's first few tens of milliseconds is answered before
    // its request stream is finished with and the connection is closed, so a client that
    // has already sent its body sees the close rather than the answer. The command line
    // deliberately does not re-send a POST — re-sending one that changes something could
    // carry it out twice — so the window is crossed here instead. `database create_tables`
    // creates nothing, so it can be repeated; two in a row have to get through, because
    // each command opens its own connection and one attempt can cross the window by luck.
    int inARow = 0;
    for (int attempt = 0; attempt < 100 && inARow < 2; attempt++) {
      inARow = run("database", "create_tables").code() == 0 ? inARow + 1 : 0;
    }
    assertEquals(2, inARow, "the runtime never answered two POSTs in a row");

    var version = reached("version");
    assertEquals(0, version.code());
    assertEquals(io.akka.redash.domain.Settings.VERSION, version.out().strip());

    var help = reached("--help");
    for (String command : List.of("database", "users", "groups", "ds", "org", "queries", "rq",
        "version", "status", "check_settings", "send_test_mail", "runserver")) {
      assertTrue(help.out().contains(command), "the help does not mention " + command);
    }

    var nonsense = reached("nonsense");
    assertEquals(2, nonsense.code());
    assertTrue(nonsense.err().contains("No such command"));

    // ---------------------------------------------------------------- users

    var root = reached("users", "create_root", "root@example.com", "Root",
        "--password", "probe-password-1");
    assertEquals(0, root.code(), root.err());
    assertTrue(root.out().contains(
        "Creating root user (root@example.com, Root) in organization default..."));
    assertTrue(root.out().contains("Login with Google Auth: False"));

    var again = settled(r -> r.code() == 1, "users", "create_root", "root@example.com", "Root",
        "--password", "probe-password-1");
    assertEquals(1, again.code(), again.out());
    assertTrue(again.out().contains("User [root@example.com] is already exists."));

    var created = reached("users", "create", "cli-user@example.com", "CLI User",
        "--password", "another-password");
    assertEquals(0, created.code(), created.err());
    assertTrue(created.out().contains("Admin: False"));
    assertTrue(created.out().contains(
        "Creating user (cli-user@example.com, CLI User) in organization default..."));

    var granted = settled(r -> r.out().contains("User updated."),
        "users", "grant_admin", "cli-user@example.com");
    assertTrue(granted.out().contains("User updated."), granted.out());
    var alreadyAdmin = settled(r -> r.out().contains("already an admin"),
        "users", "grant_admin", "cli-user@example.com");
    assertTrue(alreadyAdmin.out().contains("User is already an admin."), alreadyAdmin.out());
    var noSuchUser = reached("users", "grant_admin", "nobody@example.com");
    assertTrue(noSuchUser.out().contains("User [nobody@example.com] not found."));

    var password = settled(r -> r.code() == 0,
        "users", "password", "cli-user@example.com", "third-password");
    assertTrue(password.out().contains("User updated."), password.out());
    var missingPassword = reached("users", "password", "nobody@example.com", "x");
    assertEquals(1, missingPassword.code());
    assertTrue(missingPassword.out().contains("User [nobody@example.com] not found."));

    var listed = settled(r -> r.out().contains("cli-user@example.com"), "users", "list");
    assertTrue(listed.out().contains("Email: cli-user@example.com"), listed.out());
    assertTrue(listed.out().contains("Organization: default"));
    assertTrue(listed.out().contains("Active: True"));

    var deleted = settled(r -> r.out().contains("Deleted 1 users."),
        "users", "delete", "cli-user@example.com");
    assertTrue(deleted.out().contains("Deleted 1 users."), deleted.out());
    var deletedAgain = settled(r -> r.out().contains("Deleted 0 users."),
        "users", "delete", "cli-user@example.com");
    assertTrue(deletedAgain.out().contains("Deleted 0 users."), deletedAgain.out());

    // ---------------------------------------------------------------- groups

    var group = reached("groups", "create", "analysts");
    assertTrue(group.out().contains("Creating group (analysts)..."));
    assertTrue(group.out().contains("permissions: [create_dashboard,create_query"));

    var groups = settled(r -> r.out().contains("analysts"), "groups", "list");
    assertTrue(groups.out().contains("Name: admin"), groups.out());
    assertTrue(groups.out().contains("Name: analysts"));
    assertTrue(groups.out().contains("Type: builtin"));

    // ---------------------------------------------------------------- data sources

    var types = reached("ds", "list_types");
    assertTrue(types.out().startsWith("Enabled Query Runners:"));
    assertTrue(types.out().contains("Total of 75."), types.out());

    var source = reached("ds", "new", "probe-sqlite", "--type", "sqlite",
        "--options", "{\"dbpath\": \"/tmp/probe.db\"}");
    assertEquals(0, source.code(), source.err());
    assertTrue(source.out().contains("Creating sqlite data source (probe-sqlite) with options:"),
        source.out());
    assertTrue(source.out().contains("Id: "));

    var sources = settled(r -> r.out().contains("probe-sqlite"), "ds", "list");
    assertTrue(sources.out().contains("Name: probe-sqlite"), sources.out());
    assertTrue(sources.out().contains("Type: sqlite"));

    var edited = settled(r -> r.out().contains("Updating name"),
        "ds", "edit", "probe-sqlite", "--new-name", "probe-sqlite-2");
    assertTrue(edited.out().contains("Updating name: probe-sqlite -> probe-sqlite-2"),
        edited.out());

    var missing = reached("ds", "delete", "not-a-data-source");
    assertEquals(1, missing.code());
    assertTrue(missing.out().contains("Couldn't find data source named: not-a-data-source"));

    var removed = settled(r -> r.code() == 0, "ds", "delete", "probe-sqlite-2");
    assertTrue(removed.out().contains("Deleting data source: probe-sqlite-2 (id="),
        removed.out());

    // ---------------------------------------------------------------- organisation

    var domains = reached("org", "set_google_apps_domains", "example.com,example.org");
    assertTrue(domains.out().contains(
        "Updated list of allowed domains to: [example.com, example.org]"), domains.out());
    var shown = reached("org", "show_google_apps_domains");
    assertTrue(shown.out().contains(
        "Current list of Google Apps domains: example.com, example.org"), shown.out());
    var organizations = reached("org", "list");
    assertTrue(organizations.out().contains("Slug: default"), organizations.out());

    // ---------------------------------------------------------------- queries

    var service = new io.akka.redash.api.Service(componentClient);
    var query = service.store().insert(io.akka.redash.application.Store.QUERIES,
        io.akka.redash.domain.Json.map("org_id", 1L, "name", "tagged", "query", "SELECT 1",
            "tags", List.of(), "created_at", io.akka.redash.api.Service.now()));
    var id = String.valueOf(query.get("id"));

    assertTrue(reached("queries", "remove_tag", id, "one").out().contains("Tag is empty."));
    assertTrue(reached("queries", "add_tag", id, "one").out().contains("Tag added."));
    assertTrue(reached("queries", "remove_tag", id, "two").out().contains("Tag not found."));
    assertTrue(reached("queries", "remove_tag", id, "one").out().contains("Tag removed."));
    assertTrue(reached("queries", "add_tag", "424242", "one").out().contains("Query not found."));

    // ---------------------------------------------------------------- the scheduler

    var before = reached("rq", "healthcheck");
    assertEquals(1, before.code(), "no sweep has run, so there is no scheduler to report");
    new io.akka.redash.application.Maintenance(
        new io.akka.redash.api.Service(componentClient)).refreshQueries();
    var after = settled(r -> r.code() == 0, "rq", "healthcheck");
    assertEquals(0, after.code(), after.err());
    assertTrue(after.out().contains("RQ scheduler is healthy."));
  }

  @Test
  void parsesFlagsValuesAndPositionalsTheWayClickDoes() {
    var options = Cli.Options.parse(List.of("ana@example.com", "Ana", "--admin",
        "--password", "secret", "--groups=1,2"));
    assertEquals(List.of("ana@example.com", "Ana"), options.positional());
    assertTrue(options.flag("admin"));
    assertEquals("secret", options.text("password", null));
    assertEquals("1,2", options.text("groups", null));
  }
}
