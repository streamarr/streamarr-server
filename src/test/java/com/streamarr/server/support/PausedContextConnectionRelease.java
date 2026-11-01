package com.streamarr.server.support;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextPausedEvent;

// Every cached test context owns a pool against the one shared PostgreSQL container, and the
// context cache pauses a context whenever a test switches away from it. Closing the paused
// context's connections keeps the container's connection limit independent of the context count.
public class PausedContextConnectionRelease implements ApplicationListener<ContextPausedEvent> {

  private final HikariDataSource pool;

  // The observability profile's JDBC instrumentation wraps the context's pool.
  public PausedContextConnectionRelease(DataSource dataSource) throws SQLException {
    this.pool = dataSource.unwrap(HikariDataSource.class);
  }

  @Override
  public void onApplicationEvent(ContextPausedEvent event) {
    pool.getHikariPoolMXBean().softEvictConnections();
  }
}
