package com.streamarr.server.services.mutation;

import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
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
      // A deferred trigger fails at commit, where Hibernate reports the raw driver error without
      // wrapping it; the constraint name is still on the server error message.
      if (cause instanceof PSQLException psql
          && psql.getServerErrorMessage() != null
          && psql.getServerErrorMessage().getConstraint() != null) {
        return Optional.of(psql.getServerErrorMessage().getConstraint());
      }

      cause = cause.getCause();
    }

    return Optional.empty();
  }
}
