package io.akka.redash.application;

import akka.javasdk.client.ComponentClient;
import io.akka.redash.domain.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read and write side of the eighteen tables, as the endpoints see it.
 *
 * <p>Everything above this layer works in plain documents — a map of field name to a value
 * a JSON reader gives back unchanged — because that is what the HTTP surface exchanges and
 * what redash's own handlers work in. The entity below stores exactly that, and the view
 * beside it indexes the few columns anything filters on.
 *
 * <p>Reading a list goes through the view and then filters in Java. That is a real
 * difference from the original, which asks a relational database, and it is written down
 * rather than hidden: see the README's differences list.
 */
public final class Store {

  // The eighteen tables, named once. A typo in a table name is otherwise a row that is
  // written and never found again.
  public static final String ORGANIZATIONS = "organization";
  public static final String USERS = "user";
  public static final String GROUPS = "group";
  public static final String DATA_SOURCES = "data_source";
  public static final String QUERIES = "query";
  public static final String QUERY_RESULTS = "query_result";
  public static final String DASHBOARDS = "dashboard";
  public static final String WIDGETS = "widget";
  public static final String VISUALIZATIONS = "visualization";
  public static final String ALERTS = "alert";
  public static final String ALERT_SUBSCRIPTIONS = "alert_subscription";
  public static final String DESTINATIONS = "destination";
  public static final String QUERY_SNIPPETS = "query_snippet";
  public static final String FAVORITES = "favorite";
  public static final String EVENTS = "event";
  public static final String API_KEYS = "api_key";
  public static final String ACCESS_PERMISSIONS = "access_permission";
  public static final String CHANGES = "change";

  // Four more that are not redash tables but are the things it keeps in redis.
  public static final String JOBS = "job";
  public static final String LOCKS = "lock";
  public static final String TRACKER = "tracker";
  public static final String STATE = "state";

  /** Every table redash keeps in its database, which is what its size is measured over. */
  public static final java.util.List<String> TABLES = java.util.List.of(
      ORGANIZATIONS, USERS, GROUPS, DATA_SOURCES, QUERIES, QUERY_RESULTS, DASHBOARDS,
      WIDGETS, VISUALIZATIONS, ALERTS, ALERT_SUBSCRIPTIONS, DESTINATIONS, QUERY_SNIPPETS,
      FAVORITES, EVENTS, API_KEYS, ACCESS_PERMISSIONS, CHANGES);

  private final ComponentClient client;

  /**
   * The two tables whose options are held as ciphertext.
   *
   * <p>redash declares an `encrypted_options` column on exactly these two, keyed by
   * `REDASH_SECRET_KEY`, which is why its command line has a `reencrypt`. Everything above
   * this layer works in the plain document; the encryption happens on the way in and the
   * decryption on the way out, so nothing else has to remember.
   */
  private static final java.util.Set<String> ENCRYPTED_OPTIONS =
      java.util.Set.of(DATA_SOURCES, DESTINATIONS);

  private final String optionsKey;

  public Store(ComponentClient client) {
    this(client, io.akka.redash.domain.Settings.fromEnvironment().dataSourceSecretKey());
  }

  public Store(ComponentClient client, String optionsKey) {
    this.client = client;
    this.optionsKey = optionsKey;
  }

  public ComponentClient client() {
    return client;
  }

  // ------------------------------------------------------------------ identity

  public static String key(String table, Object id) {
    return table + ":" + id;
  }

  /** The next identifier for a table, which is one more than the last one handed out. */
  public long nextId(String table) {
    return client.forKeyValueEntity(table).method(SequenceEntity::next).invoke();
  }

  /** Make sure the counter is past an identifier that was chosen rather than allocated. */
  public void reserveId(String table, long id) {
    client.forKeyValueEntity(table).method(SequenceEntity::atLeast).invoke(id);
  }

  // ------------------------------------------------------------------ writes

  /** Create a row under an identifier this call allocates, and answer the whole document. */
  public Map<String, Object> insert(String table, Map<String, Object> fields) {
    long id = nextId(table);
    return create(table, id, fields);
  }

  public Map<String, Object> create(String table, Object id, Map<String, Object> fields) {
    var document = new LinkedHashMap<String, Object>();
    document.put("id", id instanceof Number n ? n.longValue() : id);
    document.putAll(sealed(table, fields));
    var state = client.forEventSourcedEntity(key(table, id))
        .method(RecordEntity::create).invoke(new RecordEntity.Write(document));
    hold(table, id);
    return opened(table, state.fields());
  }

  public Map<String, Object> update(String table, Object id, Map<String, Object> fields) {
    var state = client.forEventSourcedEntity(key(table, id))
        .method(RecordEntity::update).invoke(new RecordEntity.Write(sealed(table, fields)));
    return opened(table, state.fields());
  }

  /** Write whether or not it is there, which is what redash's redis-backed values do. */
  public Map<String, Object> put(String table, Object id, Map<String, Object> fields) {
    var document = new LinkedHashMap<String, Object>();
    document.put("id", id);
    document.putAll(sealed(table, fields));
    var state = client.forEventSourcedEntity(key(table, id))
        .method(RecordEntity::put).invoke(new RecordEntity.Write(document));
    hold(table, id);
    return opened(table, state.fields());
  }

  public void delete(String table, Object id) {
    client.forEventSourcedEntity(key(table, id)).method(RecordEntity::delete).invoke();
    client.forKeyValueEntity(table).method(SequenceEntity::release).invoke(String.valueOf(id));
  }

  /** Record that the table holds this row, so a list read straight afterwards contains it. */
  private void hold(String table, Object id) {
    client.forKeyValueEntity(table).method(SequenceEntity::hold).invoke(String.valueOf(id));
  }

  // ------------------------------------------------------------------ reads

  /** One row, or null when it is not there or has been deleted. */
  public Map<String, Object> find(String table, Object id) {
    if (id == null) {
      return null;
    }
    var state = client.forEventSourcedEntity(key(table, id)).method(RecordEntity::get).invoke();
    return state.exists() ? opened(table, state.fields()) : null;
  }

  public boolean exists(String table, Object id) {
    return find(table, id) != null;
  }

  public List<Map<String, Object>> all(String table) {
    return rows(table, ignored -> true);
  }

  public List<Map<String, Object>> byOrg(String table, long orgId) {
    return rows(table, row -> number(row.get("org_id")) == orgId);
  }

  public List<Map<String, Object>> byParent(String table, long parentId) {
    return rows(table, row -> number(row.get("parent_id")) == parentId);
  }

  public List<Map<String, Object>> byGroupKey(String table, String groupKey) {
    return rows(table, row -> groupKey.equals(row.get("group_key")));
  }

  public List<Map<String, Object>> byOwner(String table, long ownerId) {
    return rows(table, row -> number(row.get("user_id")) == ownerId);
  }

  /**
   * Every row of a table the caller can see, filtered by one column.
   *
   * <p>Both the membership and the bodies come from entities, and no view is involved.
   * That is the whole of it: redash reads its own writes, because its list endpoints are a
   * SQL query over the table that was just written, and a view here is updated *after* the
   * write — for **updates** as much as for inserts. Reading the bodies from a view left a
   * renamed user still showing the old name, a tag added a moment ago missing from the tag
   * list, and a data source just added to a group absent from that group's list; eight walk
   * steps, all of them a read that followed a write.
   *
   * <p>What it costs is one read of this table's sequence entity plus one read per row,
   * where redash makes one query. That is in the README's differences list as a difference
   * in how a list scales rather than in what it answers.
   *
   * <p>The filtering is done here rather than in the view's query for the same reason
   * `RecordsView` says: what redash asks the database is a join across three tables with a
   * ranked full-text vector, and none of that is expressible in a view query. Doing it in
   * one place keeps the two consistent.
   */
  private List<Map<String, Object>> rows(String table,
      java.util.function.Predicate<Map<String, Object>> matches) {
    var held = client.forKeyValueEntity(table).method(SequenceEntity::ids).invoke();
    var out = new ArrayList<Map<String, Object>>(held.size());
    for (String id : held) {
      var state = client.forEventSourcedEntity(key(table, id))
          .method(RecordEntity::get).invoke();
      if (!state.exists()) {
        continue;
      }
      var row = opened(table, state.fields());
      if (matches.test(row)) {
        out.add(row);
      }
    }
    return out;
  }

  private static long number(Object value) {
    return value instanceof Number n ? n.longValue() : 0;
  }

  // ------------------------------------------------------------------ encrypted options

  /** The document as it is stored: the options field turned into one ciphertext string. */
  Map<String, Object> sealed(String table, Map<String, Object> fields) {
    if (!ENCRYPTED_OPTIONS.contains(table) || !fields.containsKey("options")) {
      return fields;
    }
    var out = new LinkedHashMap<>(fields);
    var options = fields.get("options");
    out.put("options", options == null
        ? null
        : io.akka.redash.domain.Fernet.encrypt(Json.dumps(options), optionsKey));
    return out;
  }

  /** The document as everything above this layer wants it: the options back as a map. */
  Map<String, Object> opened(String table, Map<String, Object> fields) {
    if (!ENCRYPTED_OPTIONS.contains(table)) {
      return fields;
    }
    var stored = fields.get("options");
    if (!io.akka.redash.domain.Fernet.looksEncrypted(stored)) {
      return fields;
    }
    var plain = io.akka.redash.domain.Fernet.decrypt(String.valueOf(stored), optionsKey);
    var out = new LinkedHashMap<>(fields);
    // A row written under a different key stays visibly unreadable rather than becoming
    // empty: an empty configuration looks exactly like a data source nobody filled in.
    out.put("options", plain == null
        ? Json.map("__could_not_decrypt", true)
        : Json.asMap(Json.loads(plain)));
    return out;
  }
}
