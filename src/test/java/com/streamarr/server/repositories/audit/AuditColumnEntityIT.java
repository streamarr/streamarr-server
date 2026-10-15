package com.streamarr.server.repositories.audit;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static com.streamarr.server.support.TestFixtureAuditActorConfiguration.TEST_FIXTURE_ACTOR_ID;
import static com.streamarr.server.support.TestFixtureAuditActorConfiguration.nameTestFixtureActorIn;
import static com.streamarr.server.support.security.SignedInAccount.callSignedInAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.Builder;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Audit Column Entity Integration Tests")
class AuditColumnEntityIT extends AbstractIntegrationTest {

  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Library library;
  private UUID movieId;

  @AfterEach
  void deleteMovieAndLibrary() {
    if (movieId != null) {
      movieRepository.deleteById(movieId);
    }

    if (library != null) {
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName("Should expose the trigger's audit columns on a managed entity when it is inserted")
  void shouldExposeTheTriggersAuditColumnsOnAManagedEntityWhenItIsInserted() {
    var creator = UUID.randomUUID();

    var seen = callSignedInAs(creator, this::insertMovieAndObserveIt);

    assertThat(seen.createdBy()).isEqualTo(creator);
    assertThat(seen.lastModifiedBy()).isEqualTo(creator);
    assertThat(seen.createdOn()).isEqualTo(seen.transactionTime());
    assertThat(seen.lastModifiedOn()).isEqualTo(seen.transactionTime());
  }

  @Test
  @DisplayName(
      "Should expose the trigger's modifier on a managed movie when only its movie table changes")
  void shouldExposeTheTriggersModifierOnAManagedMovieWhenOnlyItsMovieTableChanges() {
    var creator = UUID.randomUUID();
    var modifier = UUID.randomUUID();
    var inserted = callSignedInAs(creator, this::insertMovieAndObserveIt);

    var seen = callSignedInAs(modifier, this::changeTaglineAndObserveTheMovie);

    assertThat(seen.createdBy()).isEqualTo(creator);
    assertThat(seen.createdOn()).isEqualTo(inserted.transactionTime());
    assertThat(seen.lastModifiedBy()).isEqualTo(modifier);
    assertThat(seen.lastModifiedOn()).isEqualTo(seen.transactionTime());
  }

  @Test
  @DisplayName("Should leave the modifier to a concurrent writer when only the movie table changes")
  void shouldLeaveTheModifierToAConcurrentWriterWhenOnlyTheMovieTableChanges() throws Exception {
    callSignedInAs(UUID.randomUUID(), this::insertMovieAndObserveIt);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var parentFirstWriter = dataSource.getConnection()) {
      parentFirstWriter.setAutoCommit(false);
      nameTestFixtureActorIn(parentFirstWriter);
      execute(parentFirstWriter, "UPDATE base_collectable SET title = 'Retitled' WHERE id = ?");

      var movieOnlyChange =
          executor.submit(
              () -> callSignedInAs(UUID.randomUUID(), this::changeTaglineAndObserveTheMovie));
      var parentFirstWriterPid = backendPid(parentFirstWriter);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  movieOnlyChange.isDone()
                      || waitersBehind(jdbcTemplate, parentFirstWriterPid, "%") == 1);

      execute(parentFirstWriter, "UPDATE movie SET tagline = 'Parent first' WHERE id = ?");
      parentFirstWriter.commit();

      movieOnlyChange.get(10, TimeUnit.SECONDS);
    }

    assertThat(
            dsl.select(BASE_COLLECTABLE.LAST_MODIFIED_BY)
                .from(BASE_COLLECTABLE)
                .where(BASE_COLLECTABLE.ID.eq(movieId))
                .fetchSingle()
                .value1())
        .isEqualTo(TEST_FIXTURE_ACTOR_ID);
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, movieId);
      statement.executeUpdate();
    }
  }

  private ObservedAuditColumns insertMovieAndObserveIt() {
    return transactionTemplate.execute(
        _ -> {
          library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
          var movie =
              movieRepository.saveAndFlush(
                  Movie.builder().title("Audited").titleSort("Audited").library(library).build());
          movieId = movie.getId();
          return observe(movie);
        });
  }

  private ObservedAuditColumns changeTaglineAndObserveTheMovie() {
    return transactionTemplate.execute(
        _ -> {
          var movie = movieRepository.findById(movieId).orElseThrow();
          movie.setTagline("Changed in the movie table only");
          return observe(movieRepository.saveAndFlush(movie));
        });
  }

  private ObservedAuditColumns observe(Movie movie) {
    return ObservedAuditColumns.builder()
        .createdBy(movie.getCreatedBy())
        .createdOn(movie.getCreatedOn())
        .lastModifiedBy(movie.getLastModifiedBy())
        .lastModifiedOn(movie.getLastModifiedOn())
        .transactionTime(dsl.select(DSL.currentOffsetDateTime()).fetchSingle().value1().toInstant())
        .build();
  }

  @Builder
  private record ObservedAuditColumns(
      UUID createdBy,
      Instant createdOn,
      UUID lastModifiedBy,
      Instant lastModifiedOn,
      Instant transactionTime) {}
}
