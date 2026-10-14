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
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException;
import com.github.kagkarlsson.scheduler.jdbc.AutodetectJdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkSteps;
import com.streamarr.server.services.ArtworkStepsCompletion;
import com.streamarr.server.services.ArtworkStepsTask;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One db-scheduler for artwork steps, with its own table and thread cap. It is deliberately not a
 * {@link Scheduler} bean: the db-scheduler starter would back off from its own probe scheduler, or
 * register the artwork task on it. Each start builds a new scheduler, because a stopped scheduler
 * cannot start again; requests are recorded through a client that works while it is stopped.
 */
public final class ArtworkScheduler {

  private final Task<ArtworkItem> task;
  private final SchedulerClient client;
  private final Supplier<Scheduler> newScheduler;
  private final Clock clock;
  private Scheduler running;

  private ArtworkScheduler(
      Task<ArtworkItem> task,
      SchedulerClient client,
      Supplier<Scheduler> newScheduler,
      Clock clock) {
    this.task = task;
    this.client = client;
    this.newScheduler = newScheduler;
    this.clock = clock;
  }

  /**
   * Builds an artwork scheduler from the application's db-scheduler settings, overriding the table
   * and the thread cap. Executions run on the customizer's executor, which runs them as the System
   * actor.
   */
  @Builder
  @SuppressWarnings("java:S107") // The builder names each argument at the call site.
  public static ArtworkScheduler create(
      @NonNull DbSchedulerProperties schedulerProperties,
      @NonNull DbSchedulerCustomizer customizer,
      @NonNull DataSource dataSource,
      @NonNull PlatformTransactionManager transactionManager,
      @NonNull ArtworkSteps steps,
      @NonNull ArtworkRequestRepository requests,
      @NonNull String tableName,
      @NonNull String taskName,
      int threads,
      @NonNull Clock clock,
      List<SchedulerListener> listeners) {
    var transactionalDataSource = new SchedulerTransactionDataSource(dataSource);
    var serializer = customizer.serializer().orElseThrow();
    var artworkTasks =
        new JdbcTaskRepository(
            transactionalDataSource,
            false,
            new AutodetectJdbcCustomization(dataSource),
            tableName,
            new TaskResolver(SchedulerListeners.NOOP, clock::instant, List.of()),
            new SchedulerName.Fixed(taskName + "-completion"),
            serializer,
            false,
            clock::instant);
    var completion =
        ArtworkStepsCompletion.builder()
            .requests(requests)
            .transactionManager(transactionManager)
            .artworkTasks(artworkTasks)
            .clock(clock)
            .build();
    var task = ArtworkStepsTask.create(taskName, steps, completion);
    var client =
        SchedulerClient.Builder.create(transactionalDataSource, task)
            .tableName(tableName)
            .serializer(serializer)
            .build();
    schedulerProperties.setTableName(tableName);
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
    return new ArtworkScheduler(task, client, newScheduler, clock);
  }

  private static List<SchedulerListener> listenersOrNone(List<SchedulerListener> listeners) {
    if (listeners == null) {
      return List.of();
    }

    return List.copyOf(listeners);
  }

  /**
   * Schedules the item's artwork steps to run now in the caller's transaction. A pending execution
   * that waits to retry runs now instead. A running execution checks the item's request again when
   * it completes, so it is left alone. Once the transaction commits, the running scheduler checks
   * for due executions at once instead of at its next poll.
   */
  public void scheduleSteps(ArtworkItem item) {
    var instance = task.instance(ArtworkStepsTask.instanceId(item), item);
    var now = clock.instant();
    checkForDueExecutionsAfterCommit();
    if (client.scheduleIfNotExists(instance, now)) {
      return;
    }

    try {
      client.reschedule(instance, now);
    } catch (TaskInstanceCurrentlyExecutingException _) {
      // The running execution's completion reads the request again.
    } catch (TaskInstanceNotFoundException _) {
      client.scheduleIfNotExists(instance, now);
    }
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

  public synchronized void start() {
    if (running != null) {
      return;
    }

    running = newScheduler.get();
    running.start();
  }

  public synchronized void stop() {
    if (running == null) {
      return;
    }

    running.stop();
    running = null;
  }

  public synchronized boolean isRunning() {
    return running != null;
  }
}
