package com.streamarr.server.fakes;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager with no resource behind it: units run inline, and the fake counts commits
 * and rollbacks so a test can prove a translation happened only after the unit was undone.
 */
public final class FakeTransactionManager extends AbstractPlatformTransactionManager {

  private final AtomicInteger commits = new AtomicInteger();
  private final AtomicInteger rollbacks = new AtomicInteger();

  public int commits() {
    return commits.get();
  }

  public int rollbacks() {
    return rollbacks.get();
  }

  @Override
  protected Object doGetTransaction() {
    return new Object();
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    // nothing to begin
  }

  @Override
  protected void doCommit(DefaultTransactionStatus status) {
    commits.incrementAndGet();
  }

  @Override
  protected void doRollback(DefaultTransactionStatus status) {
    rollbacks.incrementAndGet();
  }
}
