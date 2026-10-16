package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.LibraryRefreshFailedException;
import com.streamarr.server.fakes.WriteFailingItemResultRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.LibraryOperationFixture;
import com.streamarr.server.fixtures.MetadataFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
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
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProvider;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Library refresh transaction integration tests")
class LibraryRefreshTransactionIT extends AbstractIntegrationTest {

  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
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

  @Test
  @DisplayName(
      "Should roll back the last season with the series' metadata result when that result cannot"
          + " be saved, and keep the earlier seasons' results")
  void shouldRollBackTheLastSeasonWithTheSeriesMetadataResultWhenThatResultCannotBeSaved()
      throws Exception {
    var library = saveSeriesLibrary();
    var series = saveSeriesWithTmdbId(library);
    var operation = LibraryOperationFixture.refreshBuilder(library.getId()).build();
    var failed = new AtomicBoolean();

    refreshServiceWith(
            result ->
                result.itemType() == ImageEntityType.SERIES && failed.compareAndSet(false, true))
        .refreshLibrary(library, operation);

    assertThat(seasonMetadataOutcomes(series, operation))
        .containsExactly(Map.entry(1, "SUCCEEDED"), Map.entry(2, "SUCCEEDED"));
    assertThat(seasonRepository.findBySeriesIdAndSeasonNumber(series.getId(), 3)).isEmpty();
    assertThat(metadataResultOf(series))
        .hasValueSatisfying(
            result -> {
              assertThat(result.outcome())
                  .isInstanceOfSatisfying(
                      ItemOutcome.Failed.class,
                      outcome ->
                          assertThat(outcome.reason()).isEqualTo(ItemFailureReason.TEMPORARY));
              assertThat(result.libraryOperationId()).contains(operation.id());
            });
  }

  @Test
  @DisplayName(
      "Should keep the earlier seasons' results and fail the refresh when a later season's"
          + " results cannot be saved")
  void shouldKeepTheEarlierSeasonsResultsAndFailTheRefreshWhenALaterSeasonsResultsCannotBeSaved() {
    var library = saveSeriesLibrary();
    var series = saveSeriesWithTmdbId(library);
    var operation = LibraryOperationFixture.refreshBuilder(library.getId()).build();
    var metadataWrites = new AtomicInteger();
    var refreshService =
        refreshServiceWith(
            result -> result.step() == ItemStep.METADATA && metadataWrites.getAndIncrement() > 0);

    assertThatThrownBy(() -> refreshService.refreshLibrary(library, operation))
        .isInstanceOf(LibraryRefreshFailedException.class);
    assertThat(seasonMetadataOutcomes(series, operation))
        .containsExactly(Map.entry(1, "SUCCEEDED"));
    assertThat(metadataResultOf(series)).isEmpty();
  }

  private LibraryRefreshService refreshServiceWith(Predicate<ItemResult> failsWrite) {
    return refreshServiceBuilder()
        .seriesMetadataProviderResolver(
            new SeriesMetadataProviderResolver(List.of(new ThreeSeasonSeriesProvider())))
        .itemResults(new WriteFailingItemResultRepository(itemResults, failsWrite))
        .build();
  }

  private Optional<ItemResult> metadataResultOf(Series series) {
    return itemResults.findByItem(series.getId(), ImageEntityType.SERIES).stream()
        .filter(result -> result.step() == ItemStep.METADATA)
        .findFirst();
  }

  // Season numbers in order, each with the outcome of its metadata result for the operation.
  private Map<Integer, String> seasonMetadataOutcomes(Series series, LibraryOperation operation) {
    var outcomes = new LinkedHashMap<Integer, String>();
    jdbcTemplate.query(
        """
        SELECT season.season_number, item_result.outcome::text AS outcome
        FROM item_result
        JOIN season ON season.id = item_result.item_id
        WHERE item_result.step = 'METADATA'
          AND item_result.library_operation_id = ?
          AND season.series_id = ?
        ORDER BY season.season_number
        """,
        row -> {
          outcomes.put(row.getInt("season_number"), row.getString("outcome"));
        },
        operation.id(),
        series.getId());
    return outcomes;
  }

  private Library saveSeriesLibrary() {
    return libraryRepository.saveAndFlush(
        LibraryFixtureCreator.buildFakeLibrary().toBuilder()
            .type(MediaType.SERIES)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build());
  }

  private Series saveSeriesWithTmdbId(Library library) {
    return seriesRepository.saveAndFlush(
        Series.builder()
            .title("Stored Series")
            .titleSort("Stored Series")
            .library(library)
            .externalIds(
                Set.of(
                    ExternalIdentifier.builder()
                        .externalSourceType(ExternalSourceType.TMDB)
                        .externalId("4242")
                        .build()))
            .build());
  }

  private LibraryRefreshService refreshServiceFailingTheFirstMetadataWrite() {
    var failed = new AtomicBoolean();
    return refreshServiceBuilder()
        .movieMetadataProviderResolver(
            new MovieMetadataProviderResolver(List.of(new RefreshedMovieProvider())))
        .itemResults(
            new WriteFailingItemResultRepository(
                itemResults,
                result -> result.step() == ItemStep.METADATA && failed.compareAndSet(false, true)))
        .build();
  }

  private LibraryRefreshService.LibraryRefreshServiceBuilder refreshServiceBuilder() {
    return LibraryRefreshService.builder()
        .seriesRepository(seriesRepository)
        .movieRepository(movieRepository)
        .seriesService(seriesService)
        .movieService(movieService)
        .seriesMetadataProviderResolver(seriesMetadataProviderResolver)
        .artworkRequestService(artworkRequestService)
        .artworkRunWaiter(artworkRunWaiter)
        .itemResults(itemResults)
        .clock(Clock.systemUTC())
        .libraryOperationAdmissions(libraryOperationAdmissions)
        .transactionTemplate(transactionTemplate);
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

  /** Finds every series with three seasons of one episode each, and no artwork. */
  private static final class ThreeSeasonSeriesProvider implements SeriesMetadataProvider {

    @Override
    public MetadataSearchOutcome search(VideoFileParserResult parserResult) {
      throw new UnsupportedOperationException("A refresh never searches");
    }

    @Override
    public MetadataFetchOutcome<MetadataResult<Series>> getMetadata(
        RemoteSearchResult remoteSearchResult, Library library) {
      return new MetadataFetchOutcome.Found<>(
          MetadataFixture.<Series>metadataResultBuilder()
              .entity(Series.builder().title("Refreshed").titleSort("Refreshed").build())
              .build());
    }

    @Override
    public MetadataFetchOutcome<List<Integer>> getAvailableSeasonNumbers(
        UUID libraryId, String seriesExternalId) {
      return new MetadataFetchOutcome.Found<>(List.of(1, 2, 3));
    }

    @Override
    public MetadataFetchOutcome<SeasonDetails> getSeasonDetails(
        UUID libraryId, String seriesExternalId, int seasonNumber) {
      return new MetadataFetchOutcome.Found<>(
          SeasonDetails.builder()
              .name("Season " + seasonNumber)
              .seasonNumber(seasonNumber)
              .imageSources(List.of())
              .episodes(
                  List.of(
                      SeasonDetails.EpisodeDetails.builder()
                          .episodeNumber(1)
                          .name("Episode 1")
                          .imageSources(List.of())
                          .build()))
              .build());
    }

    @Override
    public ExternalAgentStrategy getAgentStrategy() {
      return ExternalAgentStrategy.TMDB;
    }
  }
}
