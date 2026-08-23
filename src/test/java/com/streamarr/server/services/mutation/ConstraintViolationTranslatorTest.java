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
  @DisplayName("Should return empty when a Hibernate violation has no constraint name")
  void shouldReturnEmptyWhenHibernateViolationHasNoConstraintName() {
    var cause = new ConstraintViolationException("write failed", new SQLException(), null);
    var exception = new DataIntegrityViolationException("write failed", cause);

    assertThat(translator.constraintName(exception)).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when a PostgreSQL violation has no server error")
  void shouldReturnEmptyWhenPostgresqlViolationHasNoServerError() {
    var cause = new PSQLException("write failed", PSQLState.UNKNOWN_STATE);
    var exception = new DataIntegrityViolationException("write failed", cause);

    assertThat(translator.constraintName(exception)).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when a PostgreSQL server error has no constraint name")
  void shouldReturnEmptyWhenPostgresqlServerErrorHasNoConstraintName() {
    var serverError = new ServerErrorMessage("SERROR\0C23505\0Mwrite failed\0\0");
    var exception =
        new DataIntegrityViolationException("write failed", new PSQLException(serverError));

    assertThat(translator.constraintName(exception)).isEmpty();
  }
}
