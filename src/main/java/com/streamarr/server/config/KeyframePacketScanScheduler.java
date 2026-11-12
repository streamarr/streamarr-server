package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.event.SchedulerListener;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.library.BitrateScanCompletion;
import com.streamarr.server.services.library.BitrateScanExecution;
import com.streamarr.server.services.library.BitrateScanPriority;
import com.streamarr.server.services.library.BitrateScanRequests;
import com.streamarr.server.services.library.BitrateScanTask;
import com.streamarr.server.services.library.KeyframePacketScanCompletion;
import com.streamarr.server.services.library.KeyframePacketScanExecution;
import com.streamarr.server.services.library.KeyframePacketScanRequests;
import com.streamarr.server.services.library.KeyframePacketScanTask;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The db-scheduler that runs keyframe packet scans and bitrate scans (ADR 0054), with its own table
 * and thread cap. A keyframe packet scan holds its thread for the whole scan and a bitrate scan for
 * one chunk, so scans never take the threads that run probes. Claims sort by priority, so a waiting
 * keyframe packet scan runs before the next bitrate scan chunk. A scan that waits for a thread
 * stays pending in the table and is not executed. It is deliberately not a {@link Scheduler} bean:
 * the db-scheduler starter would back off from its own probe scheduler, or register the scan task
 * on it. Each start builds a new scheduler, because a stopped scheduler cannot start again.
 * Requests go through a client that works while the scheduler is stopped.
 */
public final class KeyframePacketScanScheduler
    implements KeyframePacketScanRequests, BitrateScanRequests, SmartLifecycle {

  public static final String TABLE_NAME = "keyframe_scan_scheduled_tasks";

  private final Task<KeyframePacketScanTaskRequest> task;
  private final Task<BitrateScanTaskRequest> bitrateScanTask;
  private final SchedulerClient client;
  private final Supplier<Scheduler> newScheduler;
  private final Clock clock;
  private final boolean autoStartup;
  private Scheduler running;

  private KeyframePacketScanScheduler(
      Tasks tasks,
      SchedulerClient client,
      Supplier<Scheduler> newScheduler,
      Clock clock,
      boolean autoStartup) {
    this.task = tasks.keyframePacketScan();
    this.bitrateScanTask = tasks.bitrateScan();
    this.client = client;
    this.newScheduler = newScheduler;
    this.clock = clock;
    this.autoStartup = autoStartup;
  }

  /**
   * Builds the scan scheduler from the application's db-scheduler settings, overriding the table
   * and the threads. Executions run on the customizer's executor.
   */
  @Builder
  @SuppressWarnings("java:S107") // The builder names each argument at the call site.
  public static KeyframePacketScanScheduler create(
      @NonNull DbSchedulerProperties schedulerProperties,
      @NonNull DbSchedulerCustomizer customizer,
      @NonNull DataSource dataSource,
      @NonNull PlatformTransactionManager transactionManager,
      @NonNull KeyframePacketScanExecution execution,
      @NonNull BitrateScanExecution bitrateScanExecution,
      @NonNull MediaFileContainerInfoRepository outcomes,
      @NonNull BitrateScanRepository bitrateScans,
      @NonNull MeterRegistry meterRegistry,
      @NonNull ProbeSchedulingProperties properties,
      @NonNull Clock clock,
      List<SchedulerListener> listeners,
      boolean autoStartup) {
    var transactionalDataSource = new SchedulerTransactionDataSource(dataSource);
    var serializer = customizer.serializer().orElseThrow();
    var scanTasks =
        SchedulerTables.taskRepository()
            .dataSource(dataSource)
            .tableName(TABLE_NAME)
            .name("keyframe-scan-completion")
            .serializer(serializer)
            .clock(clock)
            .build();
    var completion =
        KeyframePacketScanCompletion.builder()
            .outcomes(outcomes)
            .transactionManager(transactionManager)
            .clock(clock)
            .properties(properties)
            .scanTasks(scanTasks)
            .build();
    var task = KeyframePacketScanTask.create(execution, completion, clock);
    var bitrateScanCompletion =
        BitrateScanCompletion.builder()
            .scans(bitrateScans)
            .transactionManager(transactionManager)
            .clock(clock)
            .properties(properties)
            .scanTasks(scanTasks)
            .meterRegistry(meterRegistry)
            .build();
    var bitrateScanTask =
        BitrateScanTask.create(bitrateScanExecution, bitrateScanCompletion, clock);
    var client =
        SchedulerClient.Builder.create(transactionalDataSource, task, bitrateScanTask)
            .tableName(TABLE_NAME)
            .serializer(serializer)
            .enablePriority()
            .build();
    schedulerProperties.setTableName(TABLE_NAME);
    schedulerProperties.setThreads(properties.keyframePacketScanSchedulerThreads());
    var schedulerListeners = listenersOrNone(listeners);
    Supplier<Scheduler> newScheduler =
        () ->
            DbSchedulerConfigurationSupport.buildScheduler(
                schedulerProperties,
                customizer,
                StatsRegistry.NOOP,
                clock::instant,
                dataSource,
                List.of(task, bitrateScanTask),
                schedulerListeners,
                List.of(new ReleaseAtShutdown(scanTasks)));
    return new KeyframePacketScanScheduler(
        new Tasks(task, bitrateScanTask), client, newScheduler, clock, autoStartup);
  }

  private static List<SchedulerListener> listenersOrNone(List<SchedulerListener> listeners) {
    if (listeners == null) {
      return List.of();
    }

    return List.copyOf(listeners);
  }

  @Override
  public void request(@NonNull KeyframePacketScanTaskRequest request) {
    client.scheduleIfNotExists(
        task.instance(request.mediaFileId().toString(), request), clock.instant());
  }

  @Override
  public void request(
      @NonNull BitrateScanTaskRequest request, @NonNull BitrateScanPriority priority) {
    client.scheduleIfNotExists(
        bitrateScanTask
            .instanceBuilder(request.mediaFileId().toString())
            .data(request)
            .priority(priority.value())
            .build(),
        clock.instant());
  }

  @Override
  public synchronized void start() {
    if (running != null) {
      return;
    }

    running = newScheduler.get();
    running.start();
  }

  @Override
  public synchronized void stop() {
    if (running == null) {
      return;
    }

    running.stop();
    running = null;
  }

  @Override
  public void stop(@NonNull Runnable callback) {
    BackgroundWorkStop.onItsOwnThread("keyframe-packet-scan-scheduler-stop", this::stop, callback);
  }

  @Override
  public synchronized boolean isRunning() {
    return running != null;
  }

  @Override
  public boolean isAutoStartup() {
    return autoStartup;
  }

  @Override
  public int getPhase() {
    return BackgroundWorkStop.PHASE;
  }

  private record Tasks(
      Task<KeyframePacketScanTaskRequest> keyframePacketScan,
      Task<BitrateScanTaskRequest> bitrateScan) {}
}
