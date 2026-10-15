package com.streamarr.server.config.persistence;

import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.repositories.audit.TransactionAuditActorBinder;
import io.micrometer.core.instrument.MeterRegistry;
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

  private static final String BINDING_FAILURE_METRIC = "streamarr.audit.actor_binding_failures";

  private final AuditActorSource actorSource;
  private final TransactionAuditActorBinder auditActorBinder;
  private final MeterRegistry meterRegistry;

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
      auditActorBinder.bindToCurrentTransaction(actor);
    } catch (RuntimeException failure) {
      meterRegistry.counter(BINDING_FAILURE_METRIC).increment();
      log.error(
          "Could not name the audit actor of a transaction; its audited writes will fail.",
          failure);
    }
  }
}
