package com.streamarr.server.services.mutation;

import com.streamarr.server.exceptions.ResourceBusyException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the write of a mutation in one transaction and translates a constraint violation into a
 * typed rejection only after the transaction has rolled back (ADR 0026): a value returned from
 * inside the transaction would commit, so the write unit throws and the conversion happens here,
 * outside it. Events published inside the unit reach AFTER_COMMIT listeners only when the unit
 * commits. This module owns that transaction and refuses an ambient one rather than joining it and
 * returning before the caller's rollback completes. A bounded row-lock wait that runs out becomes
 * {@link ResourceBusyException} so the client is told to retry instead of receiving a 500.
 */
@Slf4j
@Component
public class MutationTransactions {

  private final TransactionTemplate transactionTemplate;
  private final ConstraintViolationTranslator translator;

  public MutationTransactions(
      PlatformTransactionManager transactionManager, ConstraintViolationTranslator translator) {
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.translator = translator;
  }

  /**
   * @param write the transactional unit; it must decide before it writes and throw to undo
   * @param rejectionFor maps a violated constraint name to the rejection it means; an unmapped
   *     constraint is a defect and the failure propagates
   * @throws IllegalTransactionStateException when the caller already owns a transaction
   */
  @SuppressWarnings("unchecked")
  public <T, R> Outcome<T, R> write(Supplier<T> write, Function<String, Optional<R>> rejectionFor) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "MutationTransactions.write must own its transaction");
    }

    try {
      return Outcome.accepted(transactionTemplate.execute(_ -> write.get()));
    } catch (MutationRejection rejected) {
      return Outcome.rejected((R) rejected.rejection());
    } catch (DataIntegrityViolationException e) {
      var rejection = translator.constraintName(e).flatMap(rejectionFor);
      if (rejection.isEmpty()) {
        throw e;
      }

      return Outcome.rejected(rejection.get());
    } catch (PessimisticLockingFailureException e) {
      // A bounded lock wait ran out: contention the caller may retry, not a defect to trace.
      log.warn("Mutation gave up waiting for a row lock: {}", e.getMessage());
      throw new ResourceBusyException(e);
    }
  }
}
