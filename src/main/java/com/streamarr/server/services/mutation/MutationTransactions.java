package com.streamarr.server.services.mutation;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the write of a mutation in one transaction and translates a constraint violation into a
 * typed rejection only after the transaction has rolled back (ADR 0026): a value returned from
 * inside the transaction would commit, so the write unit throws and the conversion happens here,
 * outside it. Events published inside the unit reach AFTER_COMMIT listeners only when the unit
 * commits.
 */
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
   */
  public <T, R> Outcome<T, R> write(Supplier<T> write, Function<String, Optional<R>> rejectionFor) {
    try {
      return Outcome.accepted(transactionTemplate.execute(_ -> write.get()));
    } catch (DataIntegrityViolationException e) {
      var rejection = translator.constraintName(e).flatMap(rejectionFor);
      if (rejection.isEmpty()) {
        throw e;
      }
      return Outcome.rejected(rejection.get());
    }
  }
}
