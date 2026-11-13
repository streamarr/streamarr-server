package com.streamarr.server;

import com.streamarr.server.support.AuthTestSupportConfig;
import com.streamarr.server.support.PausedContextConnectionRelease;
import com.streamarr.server.support.RunningArtworkPrioritySchedulersConfiguration;
import com.streamarr.server.support.RunningLibraryOperationSchedulerConfiguration;
import com.streamarr.server.support.TestFixtureActorExtension;
import com.streamarr.server.support.TestFixtureAuditActorConfiguration;
import com.streamarr.server.support.TestManagedTransactionFixtureActorListener;
import com.streamarr.server.support.UnstartedSchedulerConfiguration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Test support is imported on the base class itself: per-subclass imports would fork the
// context cache.
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  AuthTestSupportConfig.class,
  PausedContextConnectionRelease.class,
  RunningArtworkPrioritySchedulersConfiguration.class,
  RunningLibraryOperationSchedulerConfiguration.class,
  TestFixtureAuditActorConfiguration.class,
  UnstartedSchedulerConfiguration.class
})
@ActiveProfiles("test")
@ExtendWith(TestFixtureActorExtension.class)
@TestExecutionListeners(
    listeners = TestManagedTransactionFixtureActorListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Isolated("Integration tests share one reusable PostgreSQL database")
@Execution(ExecutionMode.SAME_THREAD)
public abstract class AbstractIntegrationTest {

  /** The system property in which Failsafe names the fork that runs the integration tests. */
  public static final String FORK_NUMBER_PROPERTY = "streamarr.test.fork-number";

  @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:18-alpine")
          .withDatabaseName(databaseName())
          .withUsername("test")
          .withPassword("test")
          .withCommand("postgres", "-c", "max_connections=300")
          .withReuse(true);

  static {
    postgres.start();
  }

  // With reuse enabled, every JVM that asks for the same container settings gets one container,
  // so two forks with one database name would truncate each other's tables. Fork 1, and a run
  // outside Failsafe's forks, keep the container that developers already reuse.
  private static String databaseName() {
    var fork = Integer.parseInt(System.getProperty(FORK_NUMBER_PROPERTY, "1"));
    if (fork == 1) {
      return "streamarr";
    }

    return "streamarr_fork" + fork;
  }
}
