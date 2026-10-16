package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ArtworkResultCounts;
import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.LibraryOperationFixture;
import com.streamarr.server.fixtures.MetadataFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.ArtworkRunWaiter;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Movie refresh transaction integration tests")
class MovieRefreshTransactionIT extends AbstractIntegrationTest {

  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private MovieService movieService;
  @Autowired private SeriesService seriesService;
  @Autowired private SeriesMetadataProviderResolver seriesMetadataProviderResolver;
  @Autowired private ArtworkRequestService artworkRequestService;
  @Autowired private ArtworkRunWaiter artworkRunWaiter;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private LibraryOperationAdmissions libraryOperationAdmissions;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName(
      "Should keep a movie's metadata and request no artwork when its metadata result cannot be"
          + " saved")
  void shouldKeepAMoviesMetadataAndRequestNoArtworkWhenItsMetadataResultCannotBeSaved()
      throws Exception {
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder()
                .externalAgentStrategy(ExternalAgentStrategy.TMDB)
                .build());
    var movie = saveMovieWithTmdbId(library, "Original", "27205");
    var operation = LibraryOperationFixture.refreshBuilder(library.getId()).build();

    refreshServiceFailingTheFirstMetadataWrite().refreshLibrary(library, operation);

    assertThat(movieRepository.findById(movie.getId()).orElseThrow().getTitle())
        .isEqualTo("Original");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM artwork_request WHERE target_id = ?",
                Integer.class,
                movie.getId()))
        .isZero();
    assertThat(itemResults.findByItem(movie.getId(), ImageEntityType.MOVIE))
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.step()).isEqualTo(ItemStep.METADATA);
              assertThat(result.outcome())
                  .isInstanceOfSatisfying(
                      ItemOutcome.Failed.class,
                      failed -> assertThat(failed.reason()).isEqualTo(ItemFailureReason.TEMPORARY));
              assertThat(result.libraryOperationId()).contains(operation.id());
            });
  }

  private LibraryRefreshService refreshServiceFailingTheFirstMetadataWrite() {
    return LibraryRefreshService.builder()
        .seriesRepository(seriesRepository)
        .movieRepository(movieRepository)
        .seriesService(seriesService)
        .movieService(movieService)
        .seriesMetadataProviderResolver(seriesMetadataProviderResolver)
        .movieMetadataProviderResolver(
            new MovieMetadataProviderResolver(List.of(new RefreshedMovieProvider())))
        .artworkRequestService(artworkRequestService)
        .artworkRunWaiter(artworkRunWaiter)
        .itemResults(new FirstMetadataWriteFails(itemResults))
        .clock(Clock.systemUTC())
        .libraryOperationAdmissions(libraryOperationAdmissions)
        .transactionTemplate(transactionTemplate)
        .build();
  }

  private Movie saveMovieWithTmdbId(Library library, String title, String tmdbId) {
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title(title)
            .titleSort(title)
            .library(library)
            .externalIds(
                Set.of(
                    ExternalIdentifier.builder()
                        .externalSourceType(ExternalSourceType.TMDB)
                        .externalId(tmdbId)
                        .build()))
            .build());
  }

  /** Finds every movie with a new title and a poster. */
  private static final class RefreshedMovieProvider implements MetadataProvider<Movie> {

    @Override
    public MetadataSearchOutcome search(VideoFileParserResult parserResult) {
      throw new UnsupportedOperationException("A refresh never searches");
    }

    @Override
    public MetadataFetchOutcome<MetadataResult<Movie>> getMetadata(
        RemoteSearchResult remoteSearchResult, Library library) {
      return new MetadataFetchOutcome.Found<>(
          MetadataFixture.<Movie>metadataResultBuilder()
              .entity(Movie.builder().title("Refreshed").titleSort("Refreshed").build())
              .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
              .build());
    }

    @Override
    public ExternalAgentStrategy getAgentStrategy() {
      return ExternalAgentStrategy.TMDB;
    }
  }

  /** Fails the first write of a metadata result, as a lost connection would. */
  private static final class FirstMetadataWriteFails implements ItemResultRepository {

    private final ItemResultRepository delegate;
    private final AtomicBoolean failed = new AtomicBoolean();

    private FirstMetadataWriteFails(ItemResultRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean trySave(ItemResult result) {
      failIfFirstMetadataWrite(result);
      return delegate.trySave(result);
    }

    @Override
    public void overwrite(ItemResult result) {
      failIfFirstMetadataWrite(result);
      delegate.overwrite(result);
    }

    @Override
    public boolean trySaveArtworkResults(ArtworkStepsAttempt attempt, List<ItemResult> results) {
      return delegate.trySaveArtworkResults(attempt, results);
    }

    @Override
    public List<ItemResult> findByItem(UUID itemId, ImageEntityType itemType) {
      return delegate.findByItem(itemId, itemType);
    }

    @Override
    public Set<UUID> findItemIdsWithMetadataResultForLibraryOperation(UUID libraryOperationId) {
      return delegate.findItemIdsWithMetadataResultForLibraryOperation(libraryOperationId);
    }

    @Override
    public ArtworkResultCounts countArtworkOutcomesForLibraryOperation(UUID libraryOperationId) {
      return delegate.countArtworkOutcomesForLibraryOperation(libraryOperationId);
    }

    @Override
    public Map<ImageEntityType, ArtworkResultCounts> countArtworkOutcomesRecordedSince(
        Instant since) {
      return delegate.countArtworkOutcomesRecordedSince(since);
    }

    private void failIfFirstMetadataWrite(ItemResult result) {
      if (result.step() == ItemStep.METADATA && failed.compareAndSet(false, true)) {
        throw new DataAccessResourceFailureException("simulated connection loss");
      }
    }
  }
}
