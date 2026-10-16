package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.library.LibraryOperationExecution;
import com.streamarr.server.support.UnstartedSchedulerConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Library operation scheduler integration tests")
class LibraryOperationSchedulerIT extends AbstractIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(20);
  // Far longer than any test, so after its first poll the scheduler runs an execution within a
  // test only when it checks for due executions at once.
  private static final Duration POLLING_INTERVAL = Duration.ofHours(1);

  @Autowired private LibraryOperationScheduler contextScheduler;
  @Autowired private DbSchedulerCustomizer customizer;
  @Autowired private DataSource dataSource;
  @Autowired private LibraryOperationExecution execution;
  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final BlockingQueue<String> completedLibraryOperationIds = new LinkedBlockingQueue<>();
  private LibraryOperationScheduler scheduler;

  // The test runs its own scheduler on the shared table, so the context's scheduler must not pick
  // the test's executions.
  @BeforeEach
  void setUp() {
    contextScheduler.stop();
    deleteScheduledLibraryOperations();
    scheduler = newScheduler();
  }

  @AfterEach
  void tearDown() {
    scheduler.stop();
    deleteScheduledLibraryOperations();
    contextScheduler.start();
  }

  @Test
  @DisplayName(
      "Should run an execution scheduled outside a transaction before the scheduler's next poll")
  void shouldRunAnExecutionScheduledOutsideATransactionBeforeTheSchedulersNextPoll()
      throws Exception {
    startAndAwaitTheFirstPoll();
    var libraryOperationId = UUID.randomUUID();

    scheduler.scheduleExecution(libraryOperationId);

    assertThat(nextCompletedLibraryOperationId()).isEqualTo(libraryOperationId.toString());
    assertThat(scheduledLibraryOperations()).isZero();
  }

  @Test
  @DisplayName("Should keep running after a second start and stop when it stops once")
  void shouldKeepRunningAfterASecondStartAndStopWhenItStopsOnce() throws Exception {
    startAndAwaitTheFirstPoll();

    scheduler.start();

    assertThat(scheduler.isRunning()).isTrue();
    var libraryOperationId = UUID.randomUUID();
    scheduler.scheduleExecution(libraryOperationId);
    assertThat(nextCompletedLibraryOperationId()).isEqualTo(libraryOperationId.toString());
    scheduler.stop();
    assertThat(scheduler.isRunning()).isFalse();
  }

  // The first poll picks an execution scheduled before the start. Once that execution completes,
  // the scheduler waits out its polling interval.
  private void startAndAwaitTheFirstPoll() throws InterruptedException {
    var firstLibraryOperationId = UUID.randomUUID();
    scheduler.scheduleExecution(firstLibraryOperationId);
    scheduler.start();
    assertThat(nextCompletedLibraryOperationId()).isEqualTo(firstLibraryOperationId.toString());
  }

  private String nextCompletedLibraryOperationId() throws InterruptedException {
    return completedLibraryOperationIds.poll(BOUND.toSeconds(), TimeUnit.SECONDS);
  }

  // Two threads let the first poll pick every due execution, so its completion triggers no
  // second check.
  private LibraryOperationScheduler newScheduler() {
    var properties = UnstartedSchedulerConfiguration.schedulerProperties(environment);
    properties.setPollingInterval(POLLING_INTERVAL);
    properties.setShutdownMaxWait(Duration.ofMillis(200));
    return LibraryOperationScheduler.builder()
        .schedulerProperties(properties)
        .customizer(customizer)
        .dataSource(dataSource)
        .execution(execution)
        .threads(2)
        .clock(Clock.systemUTC())
        .listeners(
            List.of(
                new AbstractSchedulerListener() {
                  @Override
                  public void onExecutionComplete(ExecutionComplete complete) {
                    completedLibraryOperationIds.add(complete.getExecution().taskInstance.getId());
                  }
                }))
        .build();
  }

  private int scheduledLibraryOperations() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + LibraryOperationScheduler.TABLE_NAME, Integer.class);
  }

  private void deleteScheduledLibraryOperations() {
    jdbcTemplate.update("DELETE FROM " + LibraryOperationScheduler.TABLE_NAME);
  }
}
