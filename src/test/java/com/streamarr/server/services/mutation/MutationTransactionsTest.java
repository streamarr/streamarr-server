package com.streamarr.server.services.mutation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.FakeTransactionManager;
import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Mutation Transactions Tests")
class MutationTransactionsTest {

  private final FakeTransactionManager transactionManager = new FakeTransactionManager();
  private final MutationTransactions transactions =
      new MutationTransactions(transactionManager, new ConstraintViolationTranslator());

  @Test
  @DisplayName("Should commit and return accepted when the write unit completes")
  void shouldCommitAndReturnAcceptedWhenWriteUnitCompletes() {
    var outcome = transactions.<String, String>write(() -> "written", _ -> Optional.empty());

    assertThat(outcome).isEqualTo(Outcome.accepted("written"));
    assertThat(transactionManager.commits()).isEqualTo(1);
    assertThat(transactionManager.rollbacks()).isZero();
  }

  @Test
  @DisplayName("Should translate a mapped constraint into a rejection when the write rolls back")
  void shouldTranslateMappedConstraintIntoRejectionWhenWriteRollsBack() {
    var outcome =
        transactions.<String, String>write(
            () -> {
              throw violation("uq_thing");
            },
            constraint -> "uq_thing".equals(constraint) ? Optional.of("taken") : Optional.empty());

    assertThat(outcome).isEqualTo(Outcome.rejected("taken"));
    assertThat(transactionManager.rollbacks()).isEqualTo(1);
    assertThat(transactionManager.commits()).isZero();
  }

  @Test
  @DisplayName("Should propagate a constraint violation when the constraint is unmapped")
  void shouldPropagateConstraintViolationWhenConstraintIsUnmapped() {
    assertThatThrownBy(
            () ->
                transactions.<String, String>write(
                    () -> {
                      throw violation("fk_other");
                    },
                    _ -> Optional.empty()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(transactionManager.rollbacks()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should propagate a violation when the constraint name is missing")
  void shouldPropagateViolationWhenConstraintNameIsMissing() {
    assertThatThrownBy(
            () ->
                transactions.<String, String>write(
                    () -> {
                      throw new DataIntegrityViolationException("no constraint here");
                    },
                    _ -> Optional.of("never")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should reject a write when the caller already owns a transaction")
  void shouldRejectWriteWhenCallerAlreadyOwnsTransaction() {
    var outerTransaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                outerTransaction.execute(
                    _ ->
                        transactions.<String, String>write(
                            () -> {
                              throw violation("uq_thing");
                            },
                            _ -> Optional.of("taken"))))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  private static DataIntegrityViolationException violation(String constraint) {
    var message = "duplicate key value violates unique constraint \"" + constraint + "\"";
    return new DataIntegrityViolationException(
        message,
        new ConstraintViolationException(message, new SQLException(message, "23505"), constraint));
  }
}
