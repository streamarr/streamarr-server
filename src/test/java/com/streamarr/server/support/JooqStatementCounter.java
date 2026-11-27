package com.streamarr.server.support;

import java.util.concurrent.atomic.AtomicLong;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;

/**
 * Counts the statements jOOQ runs itself. A query that jOOQ only builds, for Hibernate to run, is
 * not one of them: Hibernate counts it.
 */
public class JooqStatementCounter implements ExecuteListenerProvider, ExecuteListener {

  private final AtomicLong executed = new AtomicLong();

  @Override
  public ExecuteListener provide() {
    return this;
  }

  @Override
  public void executeStart(ExecuteContext context) {
    executed.incrementAndGet();
  }

  long executed() {
    return executed.get();
  }
}
