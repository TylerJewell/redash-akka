package io.akka.redash.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import java.time.Duration;
import java.time.Instant;

/**
 * What drives the sweep in ordinary use: a firing that books the next one before it does
 * the work, so a sweep that throws does not end the schedule.
 *
 * <p>The interval is the original's own thirty seconds. Nothing here decides which queries
 * refresh — that is {@link RefreshPipeline#sweep}, which the benchmark and the tests call
 * directly so that comparing one decision does not mean waiting for a timer.
 */
@Component(id = "sweep-timer")
public class SweepTimer extends TimedAction {

  public static final String TIMER_NAME = "redash-sweep";
  public static final Duration EVERY = Duration.ofSeconds(30);

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;

  public SweepTimer(ComponentClient componentClient, TimerScheduler timerScheduler) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
  }

  public record Tick(int round) {}

  public Effect sweep(Tick tick) {
    timerScheduler.createSingleTimer(
        TIMER_NAME,
        EVERY,
        componentClient.forTimedAction().method(SweepTimer::sweep).deferred(new Tick(tick.round() + 1)));
    new RefreshPipeline(componentClient, DataSourceRegistry.all()).sweep(Instant.now());
    return effects().done();
  }
}
