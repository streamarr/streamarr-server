package com.streamarr.server.repositories.audit;

import com.streamarr.server.domain.audit.AuditActor;

/** Names the actor whose writes the current transaction makes. */
public interface TransactionAuditActorBinder {

  /**
   * Names {@code actor} in the audit columns of every row the current transaction writes. The
   * caller must have begun the transaction.
   */
  void bindToCurrentTransaction(AuditActor actor);
}
