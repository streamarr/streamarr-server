package com.streamarr.server.fakes;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

/**
 * A transaction manager with no resource behind it: units run inline, and the fake counts commits
 * and rollbacks so a test can prove a translation happened only after the unit was undone.
 */
public final class FakeTransactionManager extends AbstractPlatformTransactionManager {

  private final AtomicInteger commits = new AtomicInteger();
  private final AtomicInteger rollbacks = new AtomicInteger();
  private final ThreadLocal<TransactionState> transaction =
      ThreadLocal.withInitial(TransactionState::new);

  public int commits() {
    return commits.get();
  }

  public int rollbacks() {
    return rollbacks.get();
  }

  @Override
  protected Object doGetTransaction() {
    return transaction.get();
  }

  @Override
  protected boolean isExistingTransaction(Object transaction) {
    return ((TransactionState) transaction).active;
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    var state = (TransactionState) transaction;
    state.active = true;
    state.rollbackOnly = false;
  }

  @Override
  protected void doCommit(DefaultTransactionStatus status) {
    ((TransactionState) status.getTransaction()).active = false;
    transaction.remove();
    commits.incrementAndGet();
  }

  @Override
  protected void doRollback(DefaultTransactionStatus status) {
    ((TransactionState) status.getTransaction()).active = false;
    transaction.remove();
    rollbacks.incrementAndGet();
  }

  @Override
  protected void doSetRollbackOnly(DefaultTransactionStatus status) {
    ((TransactionState) status.getTransaction()).rollbackOnly = true;
  }

  private static final class TransactionState implements SmartTransactionObject {

    private boolean active;
    private boolean rollbackOnly;

    @Override
    public boolean isRollbackOnly() {
      return rollbackOnly;
    }

    @Override
    public void flush() {
      // This transaction fake has no buffered resource to flush.
    }
  }
}
