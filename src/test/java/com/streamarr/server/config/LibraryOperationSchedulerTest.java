package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.streamarr.server.fakes.FakeLibraryOperationRepository;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.services.library.LibraryOperationExecution;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

@Tag("UnitTest")
@DisplayName("Library Operation Scheduler Tests")
class LibraryOperationSchedulerTest {

  @ParameterizedTest
  @ValueSource(strings = {"schedulerProperties", "customizer", "dataSource", "execution", "clock"})
  @DisplayName(
      "Should reject a library-operation scheduler when a required collaborator is missing")
  void shouldRejectALibraryOperationSchedulerWhenARequiredCollaboratorIsMissing(String missing) {
    var builder = builderWithout(missing);

    assertThatNullPointerException().isThrownBy(builder::build).withMessageContaining(missing);
  }

  @Test
  @DisplayName("Should reject a scheduled execution when its library operation is missing")
  void shouldRejectAScheduledExecutionWhenItsLibraryOperationIsMissing() {
    var scheduler = builderWithout("nothing").build();

    assertThatNullPointerException()
        .isThrownBy(() -> scheduler.scheduleExecution(null))
        .withMessageContaining("libraryOperationId");
  }

  @Test
  @DisplayName("Should report itself stopped when it has not started")
  void shouldReportItselfStoppedWhenItHasNotStarted() {
    var scheduler = builderWithout("nothing").build();

    assertThat(scheduler.isRunning()).isFalse();
  }

  private static LibraryOperationScheduler.LibraryOperationSchedulerBuilder builderWithout(
      String missing) {
    var execution =
        LibraryOperationExecution.builder()
            .libraryOperations(new FakeLibraryOperationRepository(new FakeLibraryRepository()))
            .build();
    var collaborators =
        Map.<String, Consumer<LibraryOperationScheduler.LibraryOperationSchedulerBuilder>>of(
            "schedulerProperties",
                builder -> builder.schedulerProperties(new DbSchedulerProperties()),
            "customizer", builder -> builder.customizer(new JavaSerializingCustomizer()),
            "dataSource", builder -> builder.dataSource(new UnreachableDataSource()),
            "execution", builder -> builder.execution(execution),
            "clock", builder -> builder.clock(Clock.systemUTC()));
    var builder = LibraryOperationScheduler.builder().threads(1);
    collaborators.forEach(
        (name, setIt) -> {
          if (!name.equals(missing)) {
            setIt.accept(builder);
          }
        });
    return builder;
  }

  private static final class JavaSerializingCustomizer implements DbSchedulerCustomizer {

    @Override
    public Optional<Serializer> serializer() {
      return Optional.of(Serializer.DEFAULT_JAVA_SERIALIZER);
    }
  }

  // Building the scheduler's client asks the database for its product name. A unit test has no
  // database, so the client falls back to its default SQL dialect.
  private static final class UnreachableDataSource extends AbstractDataSource {

    @Override
    public Connection getConnection() throws SQLException {
      throw new SQLException("A unit test has no database");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }
  }
}
