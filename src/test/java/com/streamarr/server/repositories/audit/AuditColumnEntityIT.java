package com.streamarr.server.repositories.audit;

import static com.streamarr.server.support.security.SignedInAccount.callSignedInAs;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Managed entities see the audit columns the trigger wrote, within the writing transaction. */
@Tag("IntegrationTest")
@DisplayName("Audit Column Entity Integration Tests")
class AuditColumnEntityIT extends AbstractIntegrationTest {

  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

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
