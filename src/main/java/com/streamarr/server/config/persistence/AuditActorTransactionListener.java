package com.streamarr.server.config.persistence;

import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.repositories.audit.TransactionAuditActor;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Names the calling thread's actor in each read-write transaction as the transaction begins, so
 * every write in it, whether JPA, jOOQ or a trigger, fills the audit columns from that actor.
 */
@RequiredArgsConstructor
public class AuditActorTransactionListener implements TransactionExecutionListener {

  private final AuditActorSource actorSource;
  private final TransactionAuditActor transactionAuditActor;

  @Override
  public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
    if (beginFailure != null || transaction.isReadOnly()) {
      return;
    }

    actorSource.currentActor().ifPresent(actor -> bind(transaction, actor));
  }

  // Spring leaves a begun transaction bound to the thread when afterBegin throws, so a failed
  // binding fails the transaction at commit instead.
  private void bind(TransactionExecution transaction, AuditActor actor) {
    try {
      transactionAuditActor.bindToCurrentTransaction(actor);
    } catch (DataAccessException _) {
      transaction.setRollbackOnly();
    }
  }
}
