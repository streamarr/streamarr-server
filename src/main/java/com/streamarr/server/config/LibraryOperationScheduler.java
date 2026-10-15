package com.streamarr.server.config;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerConfigurationSupport;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.event.SchedulerListener;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.exceptions.LibraryOperationNotScheduledException;
import com.streamarr.server.services.library.LibraryOperationExecution;
import com.streamarr.server.services.library.LibraryOperationScheduling;
import com.streamarr.server.services.library.LibraryOperationTaskDefinition;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The db-scheduler that runs library operations, with its own table and thread cap. An operation
 * holds its thread while it waits for probes and artwork, so operations never take the threads that
 * run probes or artwork steps. It is deliberately not a {@link Scheduler} bean: the db-scheduler
 * starter would back off from its own probe scheduler, or register the operation task on the probe
 * scheduler. Each start builds a new scheduler, because a stopped scheduler cannot start again.
 * Executions are scheduled through a client that works while the scheduler is stopped.
 */
public final class LibraryOperationScheduler implements LibraryOperationScheduling, SmartLifecycle {

  public static final String TABLE_NAME = "library_operation_scheduled_tasks";

  private final Task<UUID> task;
  private final SchedulerClient client;
  private final Supplier<Scheduler> newScheduler;
  private final Clock clock;
  private final boolean autoStartup;
  private Scheduler running;

  private LibraryOperationScheduler(
      Task<UUID> task,
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
   * Builds the scheduler from the application's db-scheduler settings, overriding the table and the
   * thread cap. Executions run on the customizer's executor, which runs them as the System actor.
   */
  @Builder
  @SuppressWarnings("java:S107") // The builder names each argument at the call site.
  public static LibraryOperationScheduler create(
      @NonNull DbSchedulerProperties schedulerProperties,
      @NonNull DbSchedulerCustomizer customizer,
      @NonNull DataSource dataSource,
      @NonNull LibraryOperationExecution execution,
      int threads,
      @NonNull Clock clock,
      List<SchedulerListener> listeners,
      boolean autoStartup) {
    var task = LibraryOperationTaskDefinition.create(execution, clock);
    var client =
        SchedulerClient.Builder.create(new SchedulerTransactionDataSource(dataSource), task)
            .tableName(TABLE_NAME)
            .serializer(customizer.serializer().orElseThrow())
            .build();
    schedulerProperties.setTableName(TABLE_NAME);
    schedulerProperties.setThreads(threads);
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
    return new LibraryOperationScheduler(task, client, newScheduler, clock, autoStartup);
  }

  private static List<SchedulerListener> listenersOrNone(List<SchedulerListener> listeners) {
    if (listeners == null) {
      return List.of();
    }

    return List.copyOf(listeners);
  }

  /**
   * Schedules the execution in the caller's transaction. Once the transaction commits, the running
   * scheduler checks for due executions at once instead of at its next poll.
   */
  @Override
  public void scheduleExecution(@NonNull UUID libraryOperationId) {
    try {
      client.scheduleIfNotExists(
          LibraryOperationTaskDefinition.instance(task, libraryOperationId), clock.instant());
    } catch (RuntimeException e) {
      // The client reports a failed insert with its own unchecked types, including shaded JDBC
      // exceptions that Spring cannot translate.
      throw new LibraryOperationNotScheduledException(libraryOperationId, e);
    }

    checkForDueExecutionsAfterCommit();
  }

  private void checkForDueExecutionsAfterCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      checkForDueExecutions();
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            checkForDueExecutions();
          }
        });
  }

  private synchronized void checkForDueExecutions() {
    if (running == null) {
      return;
    }

    running.triggerCheckForDueExecutions();
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
