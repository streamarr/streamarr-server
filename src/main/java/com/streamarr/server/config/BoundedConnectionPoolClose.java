package com.streamarr.server.config;

import com.streamarr.server.services.audit.SystemWork;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;

/**
 * Closes the connection pool before Spring does, and stops waiting for the close after one second.
 * The pool's own close waits for the database: up to its login timeout for a connection it is
 * opening, then up to 10 s each for the connections in use and for their close. A database that
 * stopped or does not answer would hold the shutdown that long. Past the bound the close is
 * interrupted, which the pool takes as the end of its close. The pool then skips the rest of its
 * close: its idle housekeeping thread, a connection it is still opening and the connections in use
 * end with the process at the latest. Spring's own call to close then finds the pool closed.
 */
@Slf4j
public final class BoundedConnectionPoolClose implements DestructionAwareBeanPostProcessor {

  private static final Duration CLOSE_BOUND = Duration.ofSeconds(1);

  @Override
  public void postProcessBeforeDestruction(Object bean, String beanName) {
    if (bean instanceof HikariDataSource pool) {
      closeWithinTheBound(pool);
    }
  }

  @Override
  public boolean requiresDestruction(Object bean) {
    return bean instanceof HikariDataSource;
  }

  private static void closeWithinTheBound(HikariDataSource pool) {
    var closing =
        SystemWork.newThreadFactoryRunningAsSystemActor("connection-pool-close-")
            .newThread(pool::close);
    closing.start();
    try {
      if (closing.join(CLOSE_BOUND)) {
        return;
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }

    log.warn("The connection pool did not close within {}; the shutdown goes on", CLOSE_BOUND);
    closing.interrupt();
  }
}
