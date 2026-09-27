package com.streamarr.server.poc;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Live Hikari pool numbers read straight from {@link HikariPoolMXBean} on every call. Hikari's own
 * Micrometer gauges are cached for up to a second, too coarse for a 250 ms sampler.
 */
@Component
public class HikariLive {

  private final HikariDataSource hikari;

  public HikariLive(DataSource dataSource) {
    this.hikari = unwrap(dataSource);
  }

  private static HikariDataSource unwrap(DataSource dataSource) {
    try {
      return dataSource.isWrapperFor(HikariDataSource.class)
          ? dataSource.unwrap(HikariDataSource.class)
          : null;
    } catch (SQLException _) {
      return null;
    }
  }

  /** Connections in use; -1 before the pool starts or without Hikari. */
  public int active() {
    var pool = pool();
    return pool == null ? -1 : pool.getActiveConnections();
  }

  /** Threads blocked waiting for a connection; -1 before the pool starts or without Hikari. */
  public int awaiting() {
    var pool = pool();
    return pool == null ? -1 : pool.getThreadsAwaitingConnection();
  }

  /**
   * Connections the pool could still hand out: its maximum size minus connections in use.
   * Unbounded before the pool starts or without Hikari.
   */
  public int spare() {
    var pool = pool();
    return pool == null ? Integer.MAX_VALUE : hikari.getMaximumPoolSize() - pool.getActiveConnections();
  }

  private HikariPoolMXBean pool() {
    return hikari == null ? null : hikari.getHikariPoolMXBean();
  }
}
