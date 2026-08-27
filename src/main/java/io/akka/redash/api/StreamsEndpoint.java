package io.akka.redash.api;

import akka.NotUsed;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.redash.application.Store;
import io.akka.redash.domain.Access;
import io.akka.redash.domain.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the reused front end reads instead of asking again on a timer
 * (RENDERING.md R1, SPEC-001 R154).
 *
 * <p>Each stream sends the **current state first** and changes after it, so the first
 * render needs no separate round trip (R1.4) and a reconnect closes the gap by re-sending
 * everything rather than resuming from a position the client would have to remember (R1.3).
 * That is a decision the original has no answer for — it fetches once — and it is in the
 * README's differences list as one.
 *
 * <p>A frame is the whole list rather than a delta. redash's screens each draw one list and
 * re-render it whole; sending a delta would mean the client reassembling a list the server
 * already has, and the reconnect path would then need a position. The cost is bandwidth on
 * a large list, which is stated in the README rather than hidden.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/streams")
public class StreamsEndpoint extends ApiBase {

  /**
   * How often the stream looks for a change.
   *
   * <p>This is a poll **inside the server**, not in the client, and the difference is the
   * whole of R1: the browser holds one connection and is told when something moves, rather
   * than asking on a timer and being told nothing most of the time. The interval is short
   * because it costs a view read rather than a request.
   */
  static final Duration INTERVAL = Duration.ofMillis(200);

  /** The gap after which a keep-alive is sent, so an idle stream is not closed for it. */
  static final Duration KEEP_ALIVE = Duration.ofSeconds(20);

  public StreamsEndpoint(ComponentClient client) {
    super(new Service(client));
  }

  @Get("/alerts")
  public HttpResponse alerts() {
    var caller = caller();
    return stream(() -> {
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> alert : store().byOrg(Store.ALERTS, 1L)) {
        var query = service.queryById(alert.get("query_id"));
        if (!service.hasAccessToQuery(caller, query, Access.VIEW_ONLY)) {
          continue;
        }
        var author = service.userById(alert.get("user_id"));
        var queryDocument = query == null ? null : Serializers.query(query,
            service.userById(query.get("user_id")),
            service.userById(query.get("last_modified_by_id")),
            service.parameterized(query).isSafe(), false, null, null);
        out.add(Serializers.alert(alert, queryDocument, author));
      }
      return out;
    });
  }

  /**
   * The queries list, in one of the four sets the page can be pointed at.
   *
   * <p>`all`, `my`, `favorites` and `archive` are four different questions in the source
   * too — four different endpoints — and the page picks one from a prop. Making them one
   * stream with a parameter keeps the page's own code unchanged.
   */
  @Get("/queries")
  public HttpResponse queries() {
    var caller = caller();
    var set = queryParam("set") == null ? "all" : queryParam("set");
    return stream(() -> {
      var favourites = service.favouritesOf("Query", caller.id());
      var out = new ArrayList<Map<String, Object>>();
      boolean archived = "archive".equals(set);
      for (Map<String, Object> query : service.visibleQueries(caller, !archived, archived)) {
        if ("my".equals(set)
            && !Objects.equals(Service.numberOrNull(query.get("user_id")), caller.id())) {
          continue;
        }
        if ("favorites".equals(set)
            && !favourites.containsKey(Service.number(query.get("id")))) {
          continue;
        }
        var latest = store().find(Store.QUERY_RESULTS, query.get("latest_query_data_id"));
        var document = Serializers.query(query, service.userById(query.get("user_id")),
            null, service.parameterized(query).isSafe(), true, latest, null);
        Serializers.withFavourite(document,
            favourites.get(Service.number(query.get("id"))));
        out.add(document);
      }
      return out;
    });
  }

  @Get("/dashboards")
  public HttpResponse dashboards() {
    var caller = caller();
    var set = queryParam("set") == null ? "all" : queryParam("set");
    return stream(() -> {
      var favourites = service.favouritesOf("Dashboard", caller.id());
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> dashboard : store().byOrg(Store.DASHBOARDS, 1L)) {
        if (Boolean.TRUE.equals(dashboard.get("is_archived"))) {
          continue;
        }
        boolean owned = Objects.equals(
            Service.numberOrNull(dashboard.get("user_id")), caller.id());
        if (Boolean.TRUE.equals(dashboard.get("is_draft")) && !owned) {
          continue;
        }
        if ("my".equals(set) && !owned) {
          continue;
        }
        if ("favorites".equals(set)
            && !favourites.containsKey(Service.number(dashboard.get("id")))) {
          continue;
        }
        var document = Serializers.dashboard(dashboard,
            service.userById(dashboard.get("user_id")), null);
        Serializers.withFavourite(document,
            favourites.get(Service.number(dashboard.get("id"))));
        out.add(document);
      }
      return out;
    });
  }

  @Get("/data_sources")
  public HttpResponse dataSources() {
    var caller = caller();
    caller.require("list_data_sources");
    return stream(() -> {
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> dataSource : service.allDataSources()) {
        if (!caller.has("admin")
            && !Access.hasAccessToGroups(service.groupsOf(dataSource), caller.permissions(),
                caller.groupIds(), Access.VIEW_ONLY)) {
          continue;
        }
        var groups = service.groupsOf(dataSource);
        boolean viewOnly = true;
        for (Long groupId : caller.groupIds()) {
          if (groups.containsKey(groupId) && !Boolean.TRUE.equals(groups.get(groupId))) {
            viewOnly = false;
            break;
          }
        }
        out.add(Serializers.dataSource(dataSource, service.runnerFor(dataSource), false,
            viewOnly));
      }
      out.sort(java.util.Comparator.comparing(
          row -> String.valueOf(row.get("name")).toLowerCase(java.util.Locale.ROOT)));
      return out;
    });
  }

  @Get("/users")
  public HttpResponse users() {
    var caller = caller();
    caller.require("list_users");
    var set = queryParam("set") == null ? "active" : queryParam("set");
    return stream(() -> {
      var groups = service.groupsById();
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> user : service.allUsers()) {
        boolean disabled = user.get("disabled_at") != null;
        if (disabled != "disabled".equals(set)) {
          continue;
        }
        boolean pending = Boolean.TRUE.equals(user.get("is_invitation_pending"));
        if ("active".equals(set) && pending) {
          continue;
        }
        if ("pending".equals(set) && !pending) {
          continue;
        }
        var document = Serializers.user(user, false);
        var expanded = new ArrayList<Map<String, Object>>();
        var seen = new java.util.LinkedHashSet<Long>();
        for (Object groupId : Json.asList(user.get("groups"))) {
          long id = Service.number(groupId);
          if (!seen.add(id)) {
            continue;
          }
          var group = groups.get(id);
          if (group != null) {
            expanded.add(Json.map("id", group.get("id"), "name", group.get("name")));
          }
        }
        document.put("groups", expanded);
        out.add(document);
      }
      return out;
    });
  }

  @Get("/groups")
  public HttpResponse groups() {
    var caller = caller();
    return stream(() -> {
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> group : service.allGroups()) {
        if (caller.has("admin")
            || caller.groupIds().contains(Service.number(group.get("id")))) {
          out.add(Serializers.group(group));
        }
      }
      return out;
    });
  }

  @Get("/destinations")
  public HttpResponse destinations() {
    var caller = caller();
    return stream(() -> {
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> destination : store().byOrg(Store.DESTINATIONS, 1L)) {
        out.add(Serializers.destination(destination,
            io.akka.redash.destinations.Destinations.get(
                String.valueOf(destination.get("type"))), false));
      }
      return out;
    });
  }

  @Get("/query_snippets")
  public HttpResponse querySnippets() {
    var caller = caller();
    return stream(() -> {
      var out = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> snippet : store().byOrg(Store.QUERY_SNIPPETS, 1L)) {
        out.add(Serializers.snippet(snippet, service.userById(snippet.get("user_id"))));
      }
      return out;
    });
  }

  // ------------------------------------------------------------------ shared

  /**
   * Where a stream's own reads happen.
   *
   * <p>Gathering a frame is a handful of blocking component calls, and a stream stage runs
   * on the dispatcher the runtime serves everything else from. Doing the reads there stalls
   * every request in the service behind however many streams are open, so they are done on
   * a pool of their own. It is bounded: a stream that cannot keep up waits rather than
   * starting a thread.
   */
  private static final java.util.concurrent.ExecutorService READS =
      java.util.concurrent.Executors.newFixedThreadPool(8, runnable -> {
        var thread = new Thread(runnable, "redash-stream-read");
        thread.setDaemon(true);
        return thread;
      });

  /**
   * What a stream sends: the state now, and then only what changed.
   *
   * @param stillAllowed re-resolves the caller on every tick. A session is checked once per
   *     request everywhere else, and a stream is one request that lasts as long as the page
   *     is open — so a person who is disabled, or whose password changes, would otherwise
   *     keep receiving until they closed the tab. The stream ends instead.
   */
  private HttpResponse stream(java.util.function.Supplier<List<Map<String, Object>>> state) {
    var cookie = header("Cookie");
    var authorization = header("Authorization");
    var apiKey = queryParam("api_key");
    java.util.function.Supplier<Boolean> stillAllowed = () ->
        Sessions.resolve(service, cookie, authorization, apiKey, null).isAuthenticated();

    var previous = new java.util.concurrent.atomic.AtomicReference<String>();
    Source<String, NotUsed> frames = Source
        .tick(Duration.ZERO, INTERVAL, "")
        .mapMaterializedValue(ignored -> NotUsed.getInstance())
        .mapAsync(1, ignored -> java.util.concurrent.CompletableFuture.supplyAsync(
            () -> stillAllowed.get() ? Json.dumps(state.get()) : null, READS))
        .takeWhile(java.util.Objects::nonNull)
        .filter(frame -> !frame.equals(previous.getAndSet(frame)))
        .keepAlive(KEEP_ALIVE, () -> "");
    return decorate(eventStream(frames));
  }

  /**
   * A frame on the wire, written rather than serialised.
   *
   * <p>The frames are already JSON text. Handing them to the runtime's own server-sent-event
   * helper renders each one to JSON a second time, so the client's `JSON.parse` yields the
   * string rather than the list — and iterating a string gives its characters, which is a
   * page of rows with no fields on them rather than an error anybody can read. The keep-alive
   * has the same problem in reverse: the client recognises it as an empty `data:` line, and
   * a serialised empty string is `""`.
   */
  private static HttpResponse eventStream(Source<String, NotUsed> frames) {
    var bytes = frames.map(frame -> akka.util.ByteString.fromString(
        "data:" + frame + "\n\n", java.nio.charset.StandardCharsets.UTF_8));
    return HttpResponse.create().withEntity(
        akka.http.javadsl.model.HttpEntities.createChunked(
            akka.http.javadsl.model.ContentTypes.create(
                akka.http.javadsl.model.MediaTypes.TEXT_EVENT_STREAM),
            bytes));
  }
}
