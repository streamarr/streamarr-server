package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SchedulerName;
import com.github.kagkarlsson.scheduler.TaskResolver;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.event.SchedulerListener;
import com.github.kagkarlsson.scheduler.event.SchedulerListeners;
import com.github.kagkarlsson.scheduler.jdbc.AutodetectJdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.library.KeyframePacketScanCompletion;
import com.streamarr.server.services.library.KeyframePacketScanExecution;
import com.streamarr.server.services.library.KeyframePacketScanRequests;
import com.streamarr.server.services.library.KeyframePacketScanTask;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The db-scheduler that runs keyframe packet scans, with its own table and thread cap. A scan holds
 * its thread for the whole scan, so scans never take the threads that run probes. A scan that waits
 * for a thread stays pending in the table and is not executed. It is deliberately not a {@link
 * Scheduler} bean: the db-scheduler starter would back off from its own probe scheduler, or
 * register the scan task on it. Each start builds a new scheduler, because a stopped scheduler
 * cannot start again. Requests go through a client that works while the scheduler is stopped.
 */
public final class KeyframePacketScanScheduler
    implements KeyframePacketScanRequests, SmartLifecycle {

  public static final String TABLE_NAME = "keyframe_scan_scheduled_tasks";

  private final Task<KeyframePacketScanTaskRequest> task;
  private final SchedulerClient client;
  private final Supplier<Scheduler> newScheduler;
  private final Clock clock;
  private final boolean autoStartup;
  private Scheduler running;

  private KeyframePacketScanScheduler(
      Task<KeyframePacketScanTaskRequest> task,
      SchedulerClient client,
      Supplier<Scheduler> newScheduler,
      Clock clock,
      boolean autoStartup) {
    this.task = task;
    this.client = client;
    this.newScheduler = newScheduler;
    this.clock = clock;
    this.autoStartup = autoStartup;
  }

  /**
   * Builds the scan scheduler from the application's db-scheduler settings, overriding the table
   * and the thread cap. Executions run on the customizer's executor.
   */
  @Builder
  @SuppressWarnings("java:S107") // The builder names each argument at the call site.
  public static KeyframePacketScanScheduler create(
      @NonNull DbSchedulerProperties schedulerProperties,
      @NonNull DbSchedulerCustomizer customizer,
      @NonNull DataSource dataSource,
      @NonNull PlatformTransactionManager transactionManager,
      @NonNull KeyframePacketScanExecution execution,
      @NonNull MediaFileContainerInfoRepository outcomes,
      @NonNull ProbeSchedulingProperties properties,
      @NonNull Clock clock,
      List<SchedulerListener> listeners,
      boolean autoStartup) {
    var transactionalDataSource = new SchedulerTransactionDataSource(dataSource);
    var serializer = customizer.serializer().orElseThrow();
    var scanTasks =
        new JdbcTaskRepository(
            transactionalDataSource,
            false,
            new AutodetectJdbcCustomization(dataSource),
            TABLE_NAME,
            new TaskResolver(SchedulerListeners.NOOP, clock::instant, List.of()),
            new SchedulerName.Fixed("keyframe-scan-completion"),
            serializer,
            false,
            clock::instant);
    var completion =
        KeyframePacketScanCompletion.builder()
            .outcomes(outcomes)
            .transactionManager(transactionManager)
            .clock(clock)
            .properties(properties)
            .scanTasks(scanTasks)
            .build();
    var task = KeyframePacketScanTask.create(execution, completion, clock);
    var client =
        SchedulerClient.Builder.create(transactionalDataSource, task)
            .tableName(TABLE_NAME)
            .serializer(serializer)
            .build();
    schedulerProperties.setTableName(TABLE_NAME);
    schedulerProperties.setThreads(properties.maxConcurrentKeyframePacketScans());
    var schedulerListeners = listenersOrNone(listeners);
    Supplier<Scheduler> newScheduler =
        () ->
            DbSchedulerConfigurationSupport.buildScheduler(
                schedulerProperties,
                customizer,
                StatsRegistry.NOOP,
                clock::instant,
                dataSource,
                List.of(task),
                schedulerListeners,
                List.of());
    return new KeyframePacketScanScheduler(task, client, newScheduler, clock, autoStartup);
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
  public synchronized boolean isRunning() {
    return running != null;
  }

  @Override
  public boolean isAutoStartup() {
    return autoStartup;
  }
}
