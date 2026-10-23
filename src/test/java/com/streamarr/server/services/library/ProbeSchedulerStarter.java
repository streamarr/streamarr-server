package com.streamarr.server.services.library;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.event.SchedulerListener;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.streamarr.server.config.ProbeCapacityJdbcCustomization;
import com.streamarr.server.config.ProbeSchedulingConfiguration;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.streaming.ProbeCapacity;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import com.streamarr.server.support.UnstartedSchedulerConfiguration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import lombok.Builder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

// Starts a probe scheduler against the test database from the application's scheduler customization
// and scheduler listeners, as the db-scheduler starter would. The test profile turns the starter
// off. The scheduler picks probes up to the probe capacity it reads and its own thread count. A
// test whose fake producer stands in for the workers reads the capacity of FAKE_WORKERS; a test
// with a real worker session reads the context's ProbeCapacitySource.
@Builder(toBuilder = true)
record ProbeSchedulerStarter(
    DbSchedulerCustomizer customizer,
    ProbeCapacitySource probeCapacity,
    PlaybackProbeClaims playbackProbeClaims,
    ProbeTaskCompletion completion,
    DataSource dataSource,
    Environment environment,
    List<SchedulerListener> applicationListeners,
    int threads) {

  static final ProbeCapacitySource FAKE_WORKERS =
      () ->
          ProbeCapacity.builder()
              .backgroundProbeCapacity(1_000)
              .freePlaybackProbeUnits(1_000)
              .freeBackgroundProbeUnits(1_000)
              .freeKeyframePacketScanUnits(1_000)
              .build();

  static final int THREADS = 2;

  record StartedProbeScheduler(Scheduler scheduler, SchedulerClient client) {}

  static ProbeSchedulerStarter fromContext(ApplicationContext context) {
    return ProbeSchedulerStarter.builder()
        .customizer(context.getBean(DbSchedulerCustomizer.class))
        .probeCapacity(FAKE_WORKERS)
        .playbackProbeClaims(context.getBean(PlaybackProbeClaims.class))
        .completion(context.getBean(ProbeTaskCompletion.class))
        .dataSource(context.getBean(DataSource.class))
        .environment(context.getEnvironment())
        .applicationListeners(List.copyOf(context.getBeansOfType(SchedulerListener.class).values()))
        .threads(THREADS)
        .build();
  }

  StartedProbeScheduler start(
      ProbeExecution execution, AbstractSchedulerListener listener, Clock clock) {
    var task = MediaProbeTask.create(execution, completion, clock);
    var properties = UnstartedSchedulerConfiguration.schedulerProperties(environment);
    properties.setThreads(threads);
    var listeners = new ArrayList<SchedulerListener>(applicationListeners);
    listeners.add(listener);
    var serializer = customizer.serializer().orElseThrow();
    var scheduler =
        DbSchedulerConfigurationSupport.buildScheduler(
            properties,
            pickingCustomizer(),
            StatsRegistry.NOOP,
            clock::instant,
            dataSource,
            List.of(task),
            listeners,
            List.of());
    var client = SchedulerClient.Builder.create(dataSource, task).serializer(serializer).build();
    scheduler.start();
    return new StartedProbeScheduler(scheduler, client);
  }

  /**
   * The application's scheduler customization, claiming probes and keyframe packet scans up to the
   * probe capacity that this starter reads.
   */
  DbSchedulerCustomizer pickingCustomizer() {
    return new ProbeSchedulingConfiguration()
        .dbSchedulerCustomizer(
            customizer.serializer().orElseThrow(),
            dataSource,
            ProbeCapacityJdbcCustomization.builder()
                .capacitySource(probeCapacity)
                .playbackProbeClaims(playbackProbeClaims)
                .probeSchedulerUpperLimit(threads)
                .scanSchedulerUpperLimit(
                    Binder.get(environment)
                        .bindOrCreate("probe", ProbeSchedulingProperties.class)
                        .keyframePacketScanSchedulerThreads())
                .build());
  }
}
