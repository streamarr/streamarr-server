package com.streamarr.server.services.mutation;

import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Names the PostgreSQL constraint behind a write failure so a mutation can turn a race it lost (a
 * unique index, a deferred trigger) into the same typed rejection a pre-check would have given.
 * Keyed by constraint name on purpose: names are stable contracts in the migrations, messages are
 * not.
 */
@Component
public class ConstraintViolationTranslator {

  public Optional<String> constraintName(DataIntegrityViolationException exception) {
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof ConstraintViolationException violation
          && violation.getConstraintName() != null) {
        return Optional.of(violation.getConstraintName());
      }
      cause = cause.getCause();
    }
    return Optional.empty();
  }
}
