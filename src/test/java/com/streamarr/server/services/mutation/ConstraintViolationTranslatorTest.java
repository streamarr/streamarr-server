package com.streamarr.server.services.mutation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("UnitTest")
@DisplayName("Constraint Violation Translator Tests")
class ConstraintViolationTranslatorTest {

  private final ConstraintViolationTranslator translator = new ConstraintViolationTranslator();

  @Test
  @DisplayName("Should ignore a Hibernate violation without a constraint name")
  void shouldIgnoreHibernateViolationWithoutConstraintName() {
    var cause = new ConstraintViolationException("write failed", new SQLException(), null);
    var exception = new DataIntegrityViolationException("write failed", cause);

    assertThat(translator.constraintName(exception)).isEmpty();
  }

  @Test
  @DisplayName("Should ignore a PostgreSQL violation without a server error")
  void shouldIgnorePostgresqlViolationWithoutServerError() {
    var cause = new PSQLException("write failed", PSQLState.UNKNOWN_STATE);
    var exception = new DataIntegrityViolationException("write failed", cause);

    assertThat(translator.constraintName(exception)).isEmpty();
  }

  @Test
  @DisplayName("Should ignore a PostgreSQL server error without a constraint name")
  void shouldIgnorePostgresqlServerErrorWithoutConstraintName() {
    var serverError = new ServerErrorMessage("SERROR\0C23505\0Mwrite failed\0\0");
    var exception =
        new DataIntegrityViolationException("write failed", new PSQLException(serverError));

    assertThat(translator.constraintName(exception)).isEmpty();
  }
}
