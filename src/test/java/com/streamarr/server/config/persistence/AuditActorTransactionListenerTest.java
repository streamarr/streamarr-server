package com.streamarr.server.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.repositories.audit.TransactionAuditActor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

    listenerResolving(actor).afterBegin(transaction, null);

    assertThat(transactionAuditActor.boundActors).containsExactly(actor);
    assertThat(transaction.isRollbackOnly()).isFalse();
  }

  @Test
  @DisplayName("Should bind nothing when no actor resolves")
  void shouldBindNothingWhenNoActorResolves() {
    listenerResolvingNoActor().afterBegin(readWriteTransaction(), null);

    assertThat(transactionAuditActor.boundActors).isEmpty();
  }

  @Test
  @DisplayName("Should bind nothing when the transaction is read-only")
  void shouldBindNothingWhenTheTransactionIsReadOnly() {
    listenerResolving(new AuditActor.System()).afterBegin(readOnlyTransaction(), null);

    assertThat(transactionAuditActor.boundActors).isEmpty();
  }

  @Test
  @DisplayName("Should bind nothing when the transaction failed to begin")
  void shouldBindNothingWhenTheTransactionFailedToBegin() {
    listenerResolving(new AuditActor.System())
        .afterBegin(
            readWriteTransaction(), new CannotCreateTransactionException("connection refused"));

    assertThat(transactionAuditActor.boundActors).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bindingFailures")
  @DisplayName("Should leave the transaction to the audit trigger when binding the actor fails")
  void shouldLeaveTheTransactionToTheAuditTriggerWhenBindingTheActorFails(
      RuntimeException failure) {
    transactionAuditActor.failure = failure;
    var transaction = readWriteTransaction();
    var listener = listenerResolving(new AuditActor.System());

    assertThatCode(() -> listener.afterBegin(transaction, null)).doesNotThrowAnyException();

    assertThat(transactionAuditActor.boundActors).isEmpty();
    assertThat(transaction.isRollbackOnly()).isFalse();
  }

  private static Stream<RuntimeException> bindingFailures() {
    return Stream.of(
        new DataAccessResourceFailureException("connection closed"),
        new DataAccessException("set_config failed"),
        new IllegalStateException("binder unavailable"));
  }

  private AuditActorTransactionListener listenerResolving(AuditActor actor) {
    return new AuditActorTransactionListener(() -> Optional.of(actor), transactionAuditActor);
  }

  private AuditActorTransactionListener listenerResolvingNoActor() {
    return new AuditActorTransactionListener(Optional::empty, transactionAuditActor);
  }

  private static DefaultTransactionStatus readWriteTransaction() {
    return new DefaultTransactionStatus(
        "audit-actor-test", new Object(), true, true, false, false, false, null);
  }

  private static DefaultTransactionStatus readOnlyTransaction() {
    return new DefaultTransactionStatus(
        "audit-actor-test", new Object(), true, true, false, true, false, null);
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
