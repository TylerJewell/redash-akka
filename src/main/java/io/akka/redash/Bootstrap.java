package io.akka.redash;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.redash.application.SchedulerAction;
import io.akka.redash.domain.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What has to happen before the first request arrives.
 *
 * <p>redash runs three processes — a web server, a worker and a scheduler — and the third
 * exists only to put the periodic jobs on a clock. Here they are timers armed at startup,
 * so a deployment is one process rather than three.
 *
 * <p>Nothing is created here. An instance with no organisation is what `/setup` is for, and
 * creating one at startup would mean an instance could never be set up by the person who
 * installs it.
 */
@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

  /** The environment variable that stops the timers, for a benchmark or a one-shot run. */
  public static final String DISABLE_SCHEDULER = "REDASH_DISABLE_SCHEDULER";

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public Bootstrap(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  @Override
  public void onStartup() {
    var settings = Settings.fromEnvironment();
    log.info("redash {} starting", Settings.VERSION);

    if (settings.flag(DISABLE_SCHEDULER, "false")) {
      log.info("the periodic jobs are switched off");
      return;
    }
    for (SchedulerAction.Job job : SchedulerAction.jobs(settings)) {
      timers.createSingleTimer(
          SchedulerAction.timerName(job.name()),
          job.interval(),
          componentClient.forTimedAction().method(SchedulerAction::run)
              .deferred(job.name()));
      log.info("periodic job {} armed, first pass in {}", job.name(), job.interval());
    }
  }
}
