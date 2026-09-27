package com.streamarr.server.poc.adaptive;

import com.streamarr.server.services.library.admission.adaptive.AdaptiveDropSignal;
import com.streamarr.server.services.library.admission.adaptive.QueueingDelay;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Delegates to the connection pool unchanged; a connection request that times out ({@code
 * SQLTransientConnectionException} from Hikari) also marks the admitted task on the calling thread
 * as dropped. Enrichment and refresh catch database failures, so this is where the timeout is still
 * visible. {@code unwrap} and {@code isWrapperFor} reach the pool, so pool metrics still find it.
 * Every connection request's wait also counts toward the calling task's queueing time ({@link
 * QueueingDelay}).
 */
final class ConnectionTimeoutObservingDataSource extends DelegatingDataSource
    implements AutoCloseable {

  private final HikariDataSource pool;

  ConnectionTimeoutObservingDataSource(HikariDataSource pool) {
    super(pool);
    this.pool = pool;
  }

  /**
   * Spring infers the destroy method from the exposed bean, which is this wrapper, so the pool is
   * closed here as it was without the wrapper.
   */
  @Override
  public void close() {
    pool.close();
  }

  @Override
  public Connection getConnection() throws SQLException {
    var startedNanos = System.nanoTime();
    try {
      return super.getConnection();
    } catch (SQLTransientConnectionException timeout) {
      AdaptiveDropSignal.markDropped();
      throw timeout;
    } finally {
      QueueingDelay.addPoolWait(System.nanoTime() - startedNanos);
    }
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    var startedNanos = System.nanoTime();
    try {
      return super.getConnection(username, password);
    } catch (SQLTransientConnectionException timeout) {
      AdaptiveDropSignal.markDropped();
      throw timeout;
    } finally {
      QueueingDelay.addPoolWait(System.nanoTime() - startedNanos);
    }
  }
}
