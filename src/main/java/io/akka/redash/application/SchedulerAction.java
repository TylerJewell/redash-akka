package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.redash.api.Service;
import io.akka.redash.domain.Settings;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * redash's scheduler: eight jobs, each on its own interval (SPEC-001 R119).
 *
 * <p>Each pass arms the next one when it finishes rather than repeating on a fixed
 * schedule, so a pass that runs longer than its interval cannot overlap itself. The
 * intervals are the source's and are read from the same settings.
 */
@Component(id = "scheduler")
public class SchedulerAction extends TimedAction {

  private static final Logger log = LoggerFactory.getLogger(SchedulerAction.class);

  /** One periodic job: what it is called, how often it runs, and what it does. */
  public record Job(String name, Duration interval) {}

  public static List<Job> jobs(Settings settings) {
    var out = new java.util.ArrayList<Job>(List.of(
        new Job("refresh_queries", Duration.ofSeconds(30)),
        new Job("remove_ghost_locks", Duration.ofMinutes(1)),
        new Job("empty_schedules", Duration.ofMinutes(60)),
        new Job("refresh_schemas", Duration.ofMinutes(settings.schemasRefreshSchedule())),
        new Job("sync_user_details", Duration.ofMinutes(1)),
        new Job("send_aggregated_errors",
            Duration.ofMinutes(settings.sendFailureEmailInterval()))));
    if (settings.versionCheck()) {
      out.add(new Job("version_check", Duration.ofDays(1)));
    }
    if (settings.queryResultsCleanupEnabled()) {
      out.add(new Job("cleanup_query_results", Duration.ofMinutes(5)));
    }
    return List.copyOf(out);
  }

  public static String timerName(String job) {
    return "redash-periodic-" + job;
  }

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public SchedulerAction(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  /** Run one job by name, then arm its next pass. */
  public Effect run(String name) {
    var settings = Settings.fromEnvironment();
    try {
      dispatch(new Maintenance(new Service(componentClient)), name);
    } catch (RuntimeException e) {
      // A job that fails must not stop the ones after it, and must not stop itself from
      // running again: the source's scheduler re-enqueues on its own interval regardless.
      log.warn("periodic job {} failed", name, e);
    }
    for (Job job : jobs(settings)) {
      if (job.name().equals(name)) {
        timers.createSingleTimer(timerName(name), job.interval(),
            componentClient.forTimedAction()
                .method(SchedulerAction::run).deferred(name));
      }
    }
    return effects().done();
  }

  static void dispatch(Maintenance maintenance, String name) {
    switch (name) {
      case "refresh_queries" -> maintenance.refreshQueries();
      case "remove_ghost_locks" -> maintenance.removeGhostLocks();
      case "empty_schedules" -> maintenance.emptySchedules();
      case "refresh_schemas" -> maintenance.refreshSchemas();
      case "sync_user_details" -> maintenance.syncUserDetails();
      case "send_aggregated_errors" -> maintenance.sendAggregatedErrors();
      case "version_check" -> maintenance.versionCheck();
      case "cleanup_query_results" -> maintenance.cleanupQueryResults();
      default -> throw new IllegalArgumentException("no such periodic job: " + name);
    }
  }
}
