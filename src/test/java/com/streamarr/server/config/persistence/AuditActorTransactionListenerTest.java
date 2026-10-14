package com.streamarr.server.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.repositories.audit.TransactionAuditActor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.DefaultTransactionStatus;

@Tag("UnitTest")
@DisplayName("Audit Actor Transaction Listener Tests")
class AuditActorTransactionListenerTest {

  private final FakeTransactionAuditActor transactionAuditActor = new FakeTransactionAuditActor();

  @Test
  @DisplayName("Should bind the resolved actor when a read-write transaction begins")
  void shouldBindTheResolvedActorWhenAReadWriteTransactionBegins() {
    var actor = new AuditActor.Account(UUID.randomUUID());
    var transaction = readWriteTransaction();

    listenerResolving(Optional.of(actor)).afterBegin(transaction, null);

    assertThat(transactionAuditActor.boundActors).containsExactly(actor);
    assertThat(transaction.isRollbackOnly()).isFalse();
  }

  @Test
  @DisplayName("Should bind nothing when no actor resolves")
  void shouldBindNothingWhenNoActorResolves() {
    listenerResolving(Optional.empty()).afterBegin(readWriteTransaction(), null);

    assertThat(transactionAuditActor.boundActors).isEmpty();
  }

  @Test
  @DisplayName("Should bind nothing when the transaction is read-only")
  void shouldBindNothingWhenTheTransactionIsReadOnly() {
    listenerResolving(Optional.of(new AuditActor.System())).afterBegin(transaction(true), null);

    assertThat(transactionAuditActor.boundActors).isEmpty();
  }

  @Test
  @DisplayName("Should bind nothing when the transaction failed to begin")
  void shouldBindNothingWhenTheTransactionFailedToBegin() {
    listenerResolving(Optional.of(new AuditActor.System()))
        .afterBegin(
            readWriteTransaction(), new CannotCreateTransactionException("connection refused"));

    assertThat(transactionAuditActor.boundActors).isEmpty();
  }

  @Test
  @DisplayName("Should mark the transaction rollback-only when binding the actor fails")
  void shouldMarkTheTransactionRollbackOnlyWhenBindingTheActorFails() {
    transactionAuditActor.failure = new DataAccessResourceFailureException("connection closed");
    var transaction = readWriteTransaction();

    listenerResolving(Optional.of(new AuditActor.System())).afterBegin(transaction, null);

    assertThat(transaction.isRollbackOnly()).isTrue();
  }

  private AuditActorTransactionListener listenerResolving(Optional<AuditActor> actor) {
    return new AuditActorTransactionListener(() -> actor, transactionAuditActor);
  }

  private static DefaultTransactionStatus readWriteTransaction() {
    return transaction(false);
  }

  private static DefaultTransactionStatus transaction(boolean readOnly) {
    return new DefaultTransactionStatus(
        "audit-actor-test", new Object(), true, true, false, readOnly, false, null);
  }

  private static final class FakeTransactionAuditActor implements TransactionAuditActor {

    private final List<AuditActor> boundActors = new ArrayList<>();
    private RuntimeException failure;

    @Override
    public void bindToCurrentTransaction(AuditActor actor) {
      if (failure != null) {
        throw failure;
      }

      boundActors.add(actor);
    }
  }
}
