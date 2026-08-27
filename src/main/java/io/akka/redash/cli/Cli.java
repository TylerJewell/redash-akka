package io.akka.redash.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * redash's `manage.py`, as a command line over the rebuild's HTTP surface
 * (SPEC-001 R148).
 *
 * <p>The source's command line talks to its database directly, because it runs beside the
 * server on the same host. This one talks to the service, because the state lives in the
 * service and a second writer would be a second copy of every rule. The commands, their
 * options and their printed output are the source's; where they get their answer is not,
 * and that is recorded in the README as a difference.
 *
 * <p>Seven groups and five loose commands, and nothing else:
 * `database`, `users`, `groups`, `ds`, `org`, `queries`, `rq`, plus `version`, `status`,
 * `check_settings`, `send_test_mail` and `runserver`.
 */
public final class Cli {

  /** Where a command's output goes, so a test can read it without a subprocess. */
  public record Console(PrintStream out, PrintStream err) {
    public static Console standard() {
      return new Console(System.out, System.err);
    }
  }

  private final Client client;
  private final Console console;

  public Cli(Client client, Console console) {
    this.client = client;
    this.console = console;
  }

  public static void main(String[] arguments) {
    var base = System.getenv().getOrDefault("REDASH_CLI_URL", "http://localhost:9156");
    var cli = new Cli(new Client(base), Console.standard());
    System.exit(cli.run(List.of(arguments)));
  }

  /** Run one invocation and answer the exit code it would leave. */
  public int run(List<String> arguments) {
    if (arguments.isEmpty() || arguments.get(0).equals("--help")) {
      usage();
      return 0;
    }
    var command = arguments.get(0);
    var rest = arguments.subList(1, arguments.size());
    try {
      return switch (command) {
        case "database" -> database(rest);
        case "users" -> users(rest);
        case "groups" -> groups(rest);
        case "ds" -> dataSources(rest);
        case "org" -> organization(rest);
        case "queries" -> queries(rest);
        case "rq" -> rq(rest);
        case "version" -> {
          console.out().println(io.akka.redash.domain.Settings.VERSION);
          yield 0;
        }
        case "status" -> {
          console.out().println(Json.pretty(client.get("/status.json")));
          yield 0;
        }
        case "check_settings" -> {
          var settings = io.akka.redash.domain.Settings.fromEnvironment();
          for (var entry : settings.organizationDefaults().entrySet()) {
            console.out().println(entry.getKey() + " = " + entry.getValue());
          }
          yield 0;
        }
        case "send_test_mail" -> sendTestMail(rest);
        case "runserver" -> {
          console.err().println("runserver is the service itself; start it with `mvn"
              + " exec:java` or the container image.");
          yield 1;
        }
        default -> {
          console.err().println("No such command \"" + command + "\".");
          yield 2;
        }
      };
    } catch (Client.Failure failure) {
      console.err().println(failure.getMessage());
      return 1;
    }
  }

  private void usage() {
    console.out().println("Usage: manage [OPTIONS] COMMAND [ARGS]...");
    console.out().println();
    console.out().println("  Management script for Redash");
    console.out().println();
    console.out().println("Commands:");
    for (String line : List.of(
        "  check_settings  Show the settings as Redash sees them (useful for debugging).",
        "  database        Manage the database (create/drop tables. reencrypt data.).",
        "  ds              Data sources management commands.",
        "  groups          Groups management commands.",
        "  org             Organization management commands.",
        "  queries         Queries management commands.",
        "  rq              RQ management commands.",
        "  runserver       Run a development server.",
        "  send_test_mail  Send test message to EMAIL",
        "  status",
        "  users           Users management commands.",
        "  version         Displays Redash version.")) {
      console.out().println(line);
    }
  }

  // ------------------------------------------------------------------ database

  private int database(List<String> arguments) {
    var command = arguments.isEmpty() ? "" : arguments.get(0);
    return switch (command) {
      case "create_tables" -> {
        client.post("/api/cli/database/create_tables", Map.of());
        yield 0;
      }
      case "drop_tables" -> {
        client.post("/api/cli/database/drop_tables", Map.of());
        yield 0;
      }
      case "reencrypt" -> {
        if (arguments.size() < 3) {
          console.err().println("Error: Missing argument 'NEW_SECRET'.");
          yield 2;
        }
        client.post("/api/cli/database/reencrypt", Map.of(
            "old_secret", arguments.get(1), "new_secret", arguments.get(2)));
        yield 0;
      }
      default -> unknown("database", command);
    };
  }

  // ------------------------------------------------------------------ users

  private int users(List<String> arguments) {
    var command = arguments.isEmpty() ? "" : arguments.get(0);
    var options = Options.parse(arguments.subList(Math.min(1, arguments.size()),
        arguments.size()));
    var positional = options.positional();
    return switch (command) {
      case "create" -> {
        if (positional.size() < 2) {
          console.err().println("Error: Missing argument 'NAME'.");
          yield 2;
        }
        var email = positional.get(0);
        var name = positional.get(1);
        var organization = options.text("org", "default");
        console.out().printf("Creating user (%s, %s) in organization %s...%n",
            email, name, organization);
        console.out().printf("Admin: %s%n", options.flag("admin") ? "True" : "False");
        console.out().printf("Login with Google Auth: %s%n%n",
            options.flag("google") ? "True" : "False");
        var body = new LinkedHashMap<String, Object>();
        body.put("email", email);
        body.put("name", name);
        body.put("password", options.text("password", null));
        body.put("groups", options.text("groups", null));
        body.put("is_admin", options.flag("admin"));
        body.put("google_auth", options.flag("google"));
        client.post("/api/cli/users/create", body);
        yield 0;
      }
      case "create_root" -> {
        if (positional.size() < 2) {
          console.err().println("Error: Missing argument 'NAME'.");
          yield 2;
        }
        var email = positional.get(0);
        var name = positional.get(1);
        var organization = options.text("org", "default");
        console.out().printf("Creating root user (%s, %s) in organization %s...%n",
            email, name, organization);
        console.out().printf("Login with Google Auth: %s%n%n",
            options.flag("google") ? "True" : "False");
        var answer = client.post("/api/cli/users/create_root", Map.of(
            "email", email, "name", name,
            "password", options.text("password", ""),
            "org_name", organization));
        if (Boolean.TRUE.equals(Json.map(answer).get("already_exists"))) {
          console.out().printf("User [%s] is already exists.%n", email);
          yield 1;
        }
        yield 0;
      }
      case "delete" -> {
        var answer = client.post("/api/cli/users/delete",
            Map.of("email", positional.isEmpty() ? "" : positional.get(0)));
        console.out().printf("Deleted %s users.%n", Json.map(answer).get("deleted"));
        yield 0;
      }
      case "password" -> {
        if (positional.size() < 2) {
          console.err().println("Error: Missing argument 'PASSWORD'.");
          yield 2;
        }
        var answer = client.post("/api/cli/users/password",
            Map.of("email", positional.get(0), "password", positional.get(1)));
        if (Boolean.TRUE.equals(Json.map(answer).get("updated"))) {
          console.out().println("User updated.");
          yield 0;
        }
        console.out().printf("User [%s] not found.%n", positional.get(0));
        yield 1;
      }
      case "grant_admin" -> {
        var answer = client.post("/api/cli/users/grant_admin",
            Map.of("email", positional.isEmpty() ? "" : positional.get(0)));
        var outcome = String.valueOf(Json.map(answer).get("outcome"));
        switch (outcome) {
          case "already" -> console.out().println("User is already an admin.");
          case "updated" -> console.out().println("User updated.");
          default -> console.out().printf("User [%s] not found.%n",
              positional.isEmpty() ? "" : positional.get(0));
        }
        yield 0;
      }
      case "invite" -> {
        if (positional.size() < 3) {
          console.err().println("Error: Missing argument 'INVITER_EMAIL'.");
          yield 2;
        }
        var answer = client.post("/api/cli/users/invite", Map.of(
            "email", positional.get(0), "name", positional.get(1),
            "inviter_email", positional.get(2),
            "groups", options.text("groups", ""),
            "is_admin", options.flag("admin")));
        var outcome = String.valueOf(Json.map(answer).get("outcome"));
        switch (outcome) {
          case "sent" -> console.out().printf("An invitation was sent to [%s] at [%s].%n",
              positional.get(1), positional.get(0));
          case "exists" -> console.out().printf("Cannot invite. User already exists [%s]%n",
              positional.get(0));
          default -> console.out().printf("The inviter [%s] was not found.%n",
              positional.get(2));
        }
        yield 0;
      }
      case "list" -> {
        var answer = client.get("/api/cli/users/list");
        var rows = Json.list(Json.map(answer).get("users"));
        if (options.flag("json")) {
          console.out().println(Json.pretty(Json.map(answer).get("users")));
          yield 0;
        }
        for (int i = 0; i < rows.size(); i++) {
          if (i > 0) {
            console.out().println("--------------------");
          }
          var user = Json.map(rows.get(i));
          console.out().printf("Id: %s%nName: %s%nEmail: %s%nOrganization: %s%nActive: %s%n",
              user.get("id"), user.get("name"), user.get("email"),
              Json.map(user.get("org")).get("name"),
              Boolean.TRUE.equals(user.get("active")) ? "True" : "False");
          console.out().printf("Groups: %s%n",
              String.join(", ", Json.strings(user.get("groups"))));
        }
        yield 0;
      }
      default -> unknown("users", command);
    };
  }

  // ------------------------------------------------------------------ groups

  private int groups(List<String> arguments) {
    var command = arguments.isEmpty() ? "" : arguments.get(0);
    var options = Options.parse(arguments.subList(Math.min(1, arguments.size()),
        arguments.size()));
    var positional = options.positional();
    return switch (command) {
      case "create" -> {
        var name = positional.isEmpty() ? "" : positional.get(0);
        console.out().printf("Creating group (%s)...%n", name);
        var answer = client.post("/api/cli/groups/create", Map.of(
            "name", name, "permissions", options.text("permissions", "")));
        console.out().printf("permissions: [%s]%n",
            String.join(",", Json.strings(Json.map(answer).get("permissions"))));
        yield 0;
      }
      case "change_permissions" -> {
        var id = positional.isEmpty() ? "" : positional.get(0);
        console.out().printf("Change permissions of group %s ...%n", id);
        var answer = client.post("/api/cli/groups/change_permissions", Map.of(
            "group_id", id, "permissions", options.text("permissions", "")));
        var document = Json.map(answer);
        console.out().printf("current permissions [%s] will be modify to [%s]%n",
            String.join(",", Json.strings(document.get("previous"))),
            String.join(",", Json.strings(document.get("permissions"))));
        yield 0;
      }
      case "list" -> {
        var rows = Json.list(Json.map(client.get("/api/cli/groups/list")).get("groups"));
        for (int i = 0; i < rows.size(); i++) {
          if (i > 0) {
            console.out().println("--------------------");
          }
          var group = Json.map(rows.get(i));
          console.out().printf("Id: %s%nName: %s%nType: %s%nOrganization: %s%n"
              + "Permissions: [%s]%n",
              group.get("id"), group.get("name"), group.get("type"), group.get("org_slug"),
              String.join(",", Json.strings(group.get("permissions"))));
          console.out().printf("Users: %s%n",
              String.join(", ", Json.strings(group.get("users"))));
        }
        yield 0;
      }
      default -> unknown("groups", command);
    };
  }

  // ------------------------------------------------------------------ data sources

  private int dataSources(List<String> arguments) {
    var command = arguments.isEmpty() ? "" : arguments.get(0);
    var options = Options.parse(arguments.subList(Math.min(1, arguments.size()),
        arguments.size()));
    var positional = options.positional();
    return switch (command) {
      case "list" -> {
        var rows = Json.list(Json.map(client.get("/api/cli/ds/list")).get("data_sources"));
        for (int i = 0; i < rows.size(); i++) {
          if (i > 0) {
            console.out().println("--------------------");
          }
          var dataSource = Json.map(rows.get(i));
          console.out().printf("Id: %s%nName: %s%nType: %s%nOptions: %s%n",
              dataSource.get("id"), dataSource.get("name"), dataSource.get("type"),
              Json.compact(dataSource.get("options")));
        }
        yield 0;
      }
      case "list_types" -> {
        var types = Json.strings(Json.map(client.get("/api/cli/ds/list_types")).get("types"));
        console.out().println("Enabled Query Runners:");
        for (String type : types) {
          console.out().println(type);
        }
        console.out().printf("Total of %d.%n", types.size());
        yield 0;
      }
      case "test" -> {
        var name = positional.isEmpty() ? "" : positional.get(0);
        var answer = Json.map(client.post("/api/cli/ds/test", Map.of("name", name)));
        if (Boolean.TRUE.equals(answer.get("found"))) {
          console.out().printf("Testing connection to data source: %s (id=%s)%n",
              name, answer.get("id"));
          if (Boolean.TRUE.equals(answer.get("ok"))) {
            console.out().println("Success");
            yield 0;
          }
          console.out().printf("Failure: %s%n", answer.get("message"));
          yield 1;
        }
        console.out().printf("Couldn't find data source named: %s%n", name);
        yield 1;
      }
      case "new" -> {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", positional.isEmpty() ? options.text("name", null) : positional.get(0));
        body.put("type", options.text("type", null));
        body.put("options", options.text("options", "{}"));
        var answer = Json.map(client.post("/api/cli/ds/new", body));
        if (Boolean.FALSE.equals(answer.get("valid"))) {
          console.out().println("Error: invalid configuration.");
          yield 1;
        }
        console.out().printf("Creating %s data source (%s) with options:%n%s%n",
            body.get("type"), body.get("name"), Json.compact(answer.get("options")));
        console.out().printf("Id: %s%n", answer.get("id"));
        yield 0;
      }
      case "delete" -> {
        var name = positional.isEmpty() ? "" : positional.get(0);
        var answer = Json.map(client.post("/api/cli/ds/delete", Map.of("name", name)));
        if (Boolean.TRUE.equals(answer.get("found"))) {
          console.out().printf("Deleting data source: %s (id=%s)%n", name, answer.get("id"));
          yield 0;
        }
        console.out().printf("Couldn't find data source named: %s%n", name);
        yield 1;
      }
      case "edit" -> {
        var name = positional.isEmpty() ? "" : positional.get(0);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("new_name", options.text("new-name", null));
        body.put("type", options.text("type", null));
        body.put("options", options.text("options", null));
        var answer = Json.map(client.post("/api/cli/ds/edit", body));
        if (!Boolean.TRUE.equals(answer.get("found"))) {
          console.out().printf("Couldn't find data source named: %s%n", name);
          yield 1;
        }
        for (Object change : Json.list(answer.get("changes"))) {
          var moved = Json.map(change);
          console.out().printf("Updating %s: %s -> %s%n",
              moved.get("field"), moved.get("previous"), moved.get("current"));
        }
        yield 0;
      }
      default -> unknown("ds", command);
    };
  }

  // ------------------------------------------------------------------ organisation

  private int organization(List<String> arguments) {
    var command = arguments.isEmpty() ? "" : arguments.get(0);
    var options = Options.parse(arguments.subList(Math.min(1, arguments.size()),
        arguments.size()));
    var positional = options.positional();
    return switch (command) {
      case "set_google_apps_domains" -> {
        var answer = Json.map(client.post("/api/cli/org/set_google_apps_domains",
            Map.of("domains", positional.isEmpty() ? "" : positional.get(0))));
        console.out().printf("Updated list of allowed domains to: %s%n",
            Json.strings(answer.get("domains")));
        yield 0;
      }
      case "show_google_apps_domains" -> {
        var answer = Json.map(client.get("/api/cli/org/google_apps_domains"));
        console.out().printf("Current list of Google Apps domains: %s%n",
            String.join(", ", Json.strings(answer.get("domains"))));
        yield 0;
      }
      case "create" -> {
        var name = positional.isEmpty() ? "" : positional.get(0);
        console.out().printf("Creating organization (%s)...%n", name);
        client.post("/api/cli/org/create",
            Map.of("name", name, "slug", options.text("slug", "default")));
        yield 0;
      }
      case "list" -> {
        var rows = Json.list(Json.map(client.get("/api/cli/org/list")).get("organizations"));
        for (int i = 0; i < rows.size(); i++) {
          if (i > 0) {
            console.out().println("--------------------");
          }
          var org = Json.map(rows.get(i));
          console.out().printf("Id: %s%nName: %s%nSlug: %s%n",
              org.get("id"), org.get("name"), org.get("slug"));
        }
        yield 0;
      }
      default -> unknown("org", command);
    };
  }

  // ------------------------------------------------------------------ queries

  private int queries(List<String> arguments) {
    var command = arguments.isEmpty() ? "" : arguments.get(0);
    var options = Options.parse(arguments.subList(Math.min(1, arguments.size()),
        arguments.size()));
    var positional = options.positional();
    return switch (command) {
      case "rehash" -> {
        var answer = Json.map(client.post("/api/cli/queries/rehash", Map.of()));
        for (Object change : Json.list(answer.get("changed"))) {
          var moved = Json.map(change);
          console.out().printf("Query %s has changed hash from %s to %s%n",
              moved.get("id"), moved.get("previous"), moved.get("current"));
        }
        yield 0;
      }
      case "add_tag" -> {
        if (positional.size() < 2) {
          console.err().println("Error: Missing argument 'TAG'.");
          yield 2;
        }
        var answer = Json.map(client.post("/api/cli/queries/add_tag",
            Map.of("query_id", positional.get(0), "tag", positional.get(1))));
        if (!Boolean.TRUE.equals(answer.get("found"))) {
          console.out().println("Query not found.");
          yield 1;
        }
        console.out().println("Tag added.");
        yield 0;
      }
      case "remove_tag" -> {
        if (positional.size() < 2) {
          console.err().println("Error: Missing argument 'TAG'.");
          yield 2;
        }
        var answer = Json.map(client.post("/api/cli/queries/remove_tag",
            Map.of("query_id", positional.get(0), "tag", positional.get(1))));
        var outcome = String.valueOf(answer.get("outcome"));
        switch (outcome) {
          case "removed" -> {
            console.out().println("Tag removed.");
            yield 0;
          }
          case "empty" -> {
            console.out().println("Tag is empty.");
            yield 1;
          }
          case "missing-tag" -> {
            console.out().println("Tag not found.");
            yield 1;
          }
          default -> {
            console.out().println("Query not found.");
            yield 1;
          }
        }
      }
      default -> unknown("queries", command);
    };
  }

  // ------------------------------------------------------------------ rq

  private int rq(List<String> arguments) {
    var command = arguments.isEmpty() ? "" : arguments.get(0);
    return switch (command) {
      case "scheduler", "worker" -> {
        console.out().println("The scheduler and the workers run inside the service; there"
            + " is no separate process to start.");
        yield 0;
      }
      case "healthcheck" -> {
        var answer = Json.map(client.get("/api/cli/rq/healthcheck"));
        if (Boolean.TRUE.equals(answer.get("healthy"))) {
          console.out().println("RQ scheduler is healthy.");
          yield 0;
        }
        console.err().println("No RQ scheduler is running.");
        yield 1;
      }
      default -> unknown("rq", command);
    };
  }

  private int sendTestMail(List<String> arguments) {
    var options = Options.parse(arguments);
    var positional = options.positional();
    var address = positional.isEmpty() ? null : positional.get(0);
    var answer = Json.map(client.post("/api/cli/send_test_mail",
        address == null ? Map.of() : Map.of("email", address)));
    if (answer.get("error") != null) {
      console.err().println(String.valueOf(answer.get("error")));
      return 1;
    }
    return 0;
  }

  private int unknown(String group, String command) {
    console.err().println("No such command \"" + command + "\" in group \"" + group + "\".");
    return 2;
  }

  // ------------------------------------------------------------------ options

  /** A parsed argument list: the flags and values, and what was left over. */
  record Options(Map<String, String> values, List<String> flags, List<String> positional) {

    static Options parse(List<String> arguments) {
      var values = new LinkedHashMap<String, String>();
      var flags = new ArrayList<String>();
      var positional = new ArrayList<String>();
      for (int i = 0; i < arguments.size(); i++) {
        var argument = arguments.get(i);
        if (!argument.startsWith("--")) {
          positional.add(argument);
          continue;
        }
        var name = argument.substring(2);
        int equals = name.indexOf('=');
        if (equals >= 0) {
          values.put(name.substring(0, equals), name.substring(equals + 1));
          continue;
        }
        if (i + 1 < arguments.size() && !arguments.get(i + 1).startsWith("--")
            && !VALUELESS.contains(name)) {
          values.put(name, arguments.get(++i));
          continue;
        }
        flags.add(name);
      }
      return new Options(values, flags, positional);
    }

    /** The options that never take a value, so the word after them is a positional. */
    static final java.util.Set<String> VALUELESS =
        java.util.Set.of("admin", "google", "json", "show-sql", "no-show-sql");

    String text(String name, String fallback) {
      return values.getOrDefault(name, fallback);
    }

    boolean flag(String name) {
      return flags.contains(name) || "true".equalsIgnoreCase(values.get(name));
    }
  }
}
