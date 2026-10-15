package com.streamarr.server.config.persistence;

import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.repositories.audit.TransactionAuditActor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Names the calling thread's actor in each read-write transaction as the transaction begins, so
 * every write in it, whether JPA, jOOQ or a trigger, fills the audit columns from that actor.
 */
@Slf4j
@RequiredArgsConstructor
public class AuditActorTransactionListener implements TransactionExecutionListener {

  private final AuditActorSource actorSource;
  private final TransactionAuditActor transactionAuditActor;

  @Override
  public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
    if (beginFailure != null || transaction.isReadOnly()) {
      return;
    }

    actorSource.currentActor().ifPresent(this::bind);
  }

  // Spring leaves a begun transaction bound to the thread when afterBegin throws, so a failure
  // must not escape. A transaction left without an actor fails closed: the audit trigger rejects
  // its first audited write.
  private void bind(AuditActor actor) {
    try {
      transactionAuditActor.bindToCurrentTransaction(actor);
    } catch (RuntimeException failure) {
      log.error(
          "Could not name the audit actor of a transaction; its audited writes will fail.",
          failure);
    }
  }
}
