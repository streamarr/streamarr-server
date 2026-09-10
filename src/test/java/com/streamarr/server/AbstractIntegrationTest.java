package com.streamarr.server;

import com.streamarr.server.support.AuthTestSupportConfig;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

// AuthTestSupport is imported on the base class itself: per-subclass imports would fork the
// context cache.
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthTestSupportConfig.class)
@ActiveProfiles("test")
@Isolated("Integration tests share one reusable PostgreSQL database")
@Execution(ExecutionMode.SAME_THREAD)
public abstract class AbstractIntegrationTest {

  @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:18-alpine")
          .withDatabaseName("streamarr")
          .withUsername("test")
          .withPassword("test")
          .withCommand("postgres", "-c", "max_connections=300")
          .withReuse(true);

  static {
    postgres.start();
  }
}
