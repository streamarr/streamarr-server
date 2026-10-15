package com.streamarr.server.support;

import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;

public final class ConstraintViolations {

  private ConstraintViolations() {}

  /** The stable constraint name a deferred trigger raised, wherever the driver put it. */
  public static String constraintName(Throwable failure) {
    Throwable cause = failure;
    while (cause != null) {
      if (cause instanceof ConstraintViolationException violation
          && violation.getConstraintName() != null) {
        return violation.getConstraintName();
      }

      if (cause instanceof PSQLException psql
          && psql.getServerErrorMessage() != null
          && psql.getServerErrorMessage().getConstraint() != null) {
        return psql.getServerErrorMessage().getConstraint();
      }

      cause = cause.getCause();
    }

    return null;
  }
}
