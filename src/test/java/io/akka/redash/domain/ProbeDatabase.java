package io.akka.redash.domain;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The PostgreSQL that the tests which run real SQL point a data source at.
 *
 * <p>It is a fixture, not part of either system, so its absence is not a difference between
 * them. Unchecked it arrives as a difference all the same: twenty-seven of the surface
 * walk's 187 steps answer with a failed query and the comparison reports the rebuild
 * answering unlike the original, which is a statement about the machine wearing the shape of
 * a statement about the code. {@link #require} turns that into the sentence it actually is.
 *
 * <p>The default address is where {@code redash-port/probes/docker-compose.override.yml}
 * publishes the original's own PostgreSQL, so a checkout that has the original running
 * already has this.
 */
public final class ProbeDatabase {

  public static final String DEFAULT_HOST = "127.0.0.1";
  public static final int DEFAULT_PORT = 26602;

  private ProbeDatabase() {}

  public static String host() {
    var value = System.getenv("REDASH_PROBE_PG_HOST");
    return value == null || value.isBlank() ? DEFAULT_HOST : value;
  }

  public static int port() {
    var value = System.getenv("REDASH_PROBE_PG_PORT");
    return value == null || value.isBlank() ? DEFAULT_PORT : Integer.parseInt(value);
  }

  /**
   * Refuses to start a comparison whose fixture is missing, naming the address and the two
   * variables that move it.
   */
  public static void require() {
    try (var socket = new Socket()) {
      socket.connect(new InetSocketAddress(host(), port()), 2_000);
    } catch (IOException e) {
      throw new IllegalStateException(
          "no PostgreSQL answering at " + host() + ":" + port()
              + ". These tests run real SQL through a data source pointed at it, so without"
              + " it they compare a failed query against a recording of a successful one."
              + " Start one there, or set REDASH_PROBE_PG_HOST and REDASH_PROBE_PG_PORT."
              + " README.md, 'Run the tests', has the one-line docker command.", e);
    }
  }
}
