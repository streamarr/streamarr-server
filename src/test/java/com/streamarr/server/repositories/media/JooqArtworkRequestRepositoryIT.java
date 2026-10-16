package com.streamarr.server.repositories.media;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.fixtures.SavedMediaFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.NewArtworkRequest;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Artwork Request Repository Integration Tests")
class JooqArtworkRequestRepositoryIT extends AbstractIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final Instant REQUESTED_AT = Instant.parse("2026-10-14T10:00:00Z");

  @Autowired private ArtworkRequestRepository repository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Should take no row lock when an identical request arrives")
  void shouldTakeNoRowLockWhenAnIdenticalRequestArrives() throws Exception {
    var artwork = posterOf(savedMovieId(), "/poster.jpg");
    var first = save(artwork);

    try (var _ = lockRow(requestRow(artwork))) {
      var identical = BoundedTask.start(() -> save(artwork)).await(BOUND);

      assertThat(identical).isEqualTo(new SavedArtworkRequest.Unchanged(first.generation()));
    }
  }

  @Test
  @DisplayName("Should start a new generation when a changed request waits for the row lock")
  void shouldStartANewGenerationWhenAChangedRequestWaitsForTheRowLock() throws Exception {
    var movieId = savedMovieId();
    var first = save(posterOf(movieId, "/poster.jpg"));

    try (var held = lockRow(requestRow(posterOf(movieId, "/poster.jpg")));
        var changed = BoundedTask.start(() -> save(posterOf(movieId, "/other.jpg")))) {
      awaitWaitersBehind(jdbcTemplate, held.backendPid(), 1);
      held.release();

      assertThat(changed.await(BOUND).generation()).isGreaterThan(first.generation());
    }
  }

  @Test
  @DisplayName(
      "Should keep one request when concurrent transactions request new artwork for a target")
  void shouldKeepOneRequestWhenConcurrentTransactionsRequestNewArtworkForATarget()
      throws Exception {
    var artwork = posterOf(savedMovieId(), "/poster.jpg");

    try (var first = BoundedTask.start(() -> save(artwork));
        var second = BoundedTask.start(() -> save(artwork))) {
      var generations = List.of(first.await(BOUND), second.await(BOUND));

      assertThat(generations)
          .extracting(SavedArtworkRequest::generation)
          .containsOnly(generations.getFirst().generation());
      assertThat(generations)
          .filteredOn(saved -> saved instanceof SavedArtworkRequest.NewGeneration)
          .hasSize(1);
    }
  }

  private SavedArtworkRequest save(ArtworkSources artwork) {
    return transactionTemplate.execute(
        _ ->
            repository.saveRequest(
                NewArtworkRequest.builder()
                    .artwork(artwork)
                    .refreshMode(ImageRefreshMode.PRESERVE)
                    .requestedAt(REQUESTED_AT)
                    .build()));
  }

  private RowLockTarget requestRow(ArtworkSources artwork) {
    return RowLockTarget.builder()
        .dataSource(dataSource)
        .table("artwork_request")
        .keyColumn("target_id")
        .rowId(artwork.targetId())
        .build();
  }

  private UUID savedMovieId() {
    return SavedMediaFixture.saveMovie(libraryRepository, movieRepository).getId();
  }

  private static ArtworkSources posterOf(UUID movieId, String key) {
    return ArtworkSources.builder()
        .targetId(movieId)
        .targetType(ImageEntityType.MOVIE)
        .sources(List.of(new TmdbImageSource(ImageType.POSTER, key)))
        .build();
  }
}
