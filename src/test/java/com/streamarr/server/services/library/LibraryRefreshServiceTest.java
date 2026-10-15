package com.streamarr.server.services.library;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.exceptions.LibraryRefreshFailedException;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.fakes.CountingSleeper;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.fakes.FixedLimitAdmissions;
import com.streamarr.server.fakes.GatedImageDownloader;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.ArtworkFixture;
import com.streamarr.server.fixtures.CreditServiceFixture;
import com.streamarr.server.fixtures.MetadataFixture;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.concurrency.AdaptiveAdmissions;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.metadata.tmdb.TmdbApiException;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

@Tag("UnitTest")
@DisplayName("Library Refresh Service Tests")
class LibraryRefreshServiceTest {

  private FakeSeriesRepository seriesRepository;
  private FakeMovieRepository movieRepository;
  private FakeSeasonRepository seasonRepository;
  private FakeEpisodeRepository episodeRepository;
  private SeriesMetadataProviderResolver seriesProviderResolver;
  private MovieMetadataProviderResolver movieProviderResolver;
  private LibraryRefreshService refreshService;
  private FakeImageRepository imageRepository;
  private FakeItemResultRepository itemResults;
  private MutableClock clock;
  private GatedImageDownloader imageDownloader;
  private FakeItemResultRepository artworkResults;
  private ArtworkFixture artwork;
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @BeforeEach
  void setUp() {
    seriesRepository = new FakeSeriesRepository();
    movieRepository = new FakeMovieRepository();
    seasonRepository = new FakeSeasonRepository();
    episodeRepository = new FakeEpisodeRepository();
    imageRepository = new FakeImageRepository();
    imageDownloader = new GatedImageDownloader(createTestImage(600, 900));
    artworkResults = new FakeItemResultRepository();
    artwork =
        ArtworkFixture.builder()
            .imageRepository(imageRepository)
            .imageDownloader(imageDownloader)
            .itemResults(artworkResults)
            .build();
    seriesProviderResolver = mock(SeriesMetadataProviderResolver.class);
    movieProviderResolver = mock(MovieMetadataProviderResolver.class);
    itemResults = new FakeItemResultRepository();
    clock = new MutableClock();
    refreshService = refreshServiceWith(artwork, admissionsWith(new AdaptiveAdmissions()));
  }

  private LibraryRefreshService refreshServiceWith(
      ArtworkFixture artwork, LibraryOperationAdmissions admissions) {
    var artworkRequestService = artwork.artworkRequestService();
    var creditService =
        CreditServiceFixture.creditServiceBuilder()
            .artworkRequestService(artworkRequestService)
            .build();
    var fileSystem = Jimfs.newFileSystem(Configuration.unix());
    var imageService =
        new ImageService(
            imageRepository,
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            fileSystem,
            new FakeItemResultRepository());

    var seriesService =
        new SeriesService(
            seriesRepository,
            creditService,
            new PaginationService(),
            artworkRequestService,
            imageService,
            seasonRepository,
            episodeRepository,
            null,
            null,
            null,
            null);

    var movieService =
        new MovieService(
            movieRepository,
            creditService,
            new PaginationService(),
            artworkRequestService,
            imageService,
            null,
            null,
            null,
            null,
            null,
            null);

    return LibraryRefreshService.builder()
        .seriesRepository(seriesRepository)
        .movieRepository(movieRepository)
        .seriesService(seriesService)
        .movieService(movieService)
        .seriesMetadataProviderResolver(seriesProviderResolver)
        .movieMetadataProviderResolver(movieProviderResolver)
        .artworkRequestService(artworkRequestService)
        .artworkRunWaiter(artwork.artworkRunWaiter())
        .itemResults(itemResults)
        .clock(clock)
        .libraryOperationAdmissions(admissions)
        .build();
  }

  private LibraryOperationAdmissions admissionsWith(AdaptiveAdmissions admissions) {
    return LibraryOperationAdmissions.builder()
        .meterRegistry(meterRegistry)
        .admissions(admissions)
        .build();
  }

  private double refreshReports() {
    return meterRegistry
        .find("streamarr.library.admission.reports")
        .tag("operation", "refresh")
        .counters()
        .stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private double refreshReports(String report) {
    return meterRegistry
        .find("streamarr.library.admission.reports")
        .tag("operation", "refresh")
        .tag("report", report)
        .counters()
        .stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private double refreshAdmissions() {
    var admitted =
        meterRegistry
            .find("streamarr.library.admission.admitted")
            .tag("operation", "refresh")
            .counter();
    if (admitted == null) {
      return 0;
    }

    return admitted.count();
  }

  @Nested
  @DisplayName("Refresh admission")
  class RefreshAdmissionTests {

    @Test
    @DisplayName("Should admit each movie through the limit when refreshing")
    void shouldAdmitEachMovieThroughTheLimitWhenRefreshing() {
      var library = buildMovieLibrary();
      saveMovieWithTmdbId("Inception", "27205", library);
      saveMovieWithTmdbId("Interstellar", "157336", library);
      var reportsAtEachFetch = new CopyOnWriteArrayList<Double>();
      when(movieProviderResolver.getMetadata(any(), eq(library)))
          .thenAnswer(
              invocation -> {
                reportsAtEachFetch.add(refreshReports());
                var tmdbId = invocation.getArgument(0, RemoteSearchResult.class).externalId();
                return new MetadataFetchOutcome.Found<>(
                    MetadataFixture.<Movie>metadataResultBuilder()
                        .entity(Movie.builder().title("Refreshed " + tmdbId).build())
                        .build());
              });
      var service = refreshServiceWith(artwork, admissionsWith(new FixedLimitAdmissions(1)));

      service.refreshLibrary(library);

      assertThat(reportsAtEachFetch).containsExactly(0.0, 1.0);
      assertThat(refreshAdmissions()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should admit each series through the limit when refreshing")
    void shouldAdmitEachSeriesThroughTheLimitWhenRefreshing() {
      var library = buildSeriesLibrary();
      saveSeriesWithTmdbId("Breaking Bad", "1396", library);
      saveSeriesWithTmdbId("Better Call Saul", "60059", library);
      var reportsAtEachFetch = new CopyOnWriteArrayList<Double>();
      when(seriesProviderResolver.getMetadata(any(), eq(library)))
          .thenAnswer(
              invocation -> {
                reportsAtEachFetch.add(refreshReports());
                var tmdbId = invocation.getArgument(0, RemoteSearchResult.class).externalId();
                return new MetadataFetchOutcome.Found<>(
                    MetadataFixture.<Series>metadataResultBuilder()
                        .entity(Series.builder().title("Refreshed " + tmdbId).build())
                        .build());
              });
      when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any()))
          .thenReturn(new MetadataFetchOutcome.Found<>(List.of()));
      var service = refreshServiceWith(artwork, admissionsWith(new FixedLimitAdmissions(1)));

      service.refreshLibrary(library);

      assertThat(reportsAtEachFetch).containsExactly(0.0, 1.0);
      assertThat(refreshAdmissions()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should hold a refreshed movie's admission until its poster result is recorded")
    void shouldHoldARefreshedMoviesAdmissionUntilItsPosterResultIsRecorded() throws Exception {
      var library = buildMovieLibrary();
      saveMovieWithTmdbId("Inception", "27205", library);
      stubMovieMetadataWithPoster("27205", library);
      var resultCheckSleeper = new CountingSleeper();
      var heldArtwork =
          ArtworkFixture.builder()
              .imageRepository(imageRepository)
              .imageDownloader(new GatedImageDownloader(createTestImage(600, 900)))
              .sleeper(resultCheckSleeper)
              .build();
      heldArtwork.scheduling().holdScheduledSteps();
      var inFlightWhilePosterPending = new AtomicReference<Double>();
      resultCheckSleeper.onSleep(
          _ -> {
            inFlightWhilePosterPending.compareAndSet(null, refreshTasksInFlight());
            heldArtwork.scheduling().runPendingSteps();
          });
      var service = refreshServiceWith(heldArtwork, admissionsWith(new AdaptiveAdmissions()));

      BoundedTask.runWithin(Duration.ofSeconds(5), () -> service.refreshLibrary(library));

      assertThat(inFlightWhilePosterPending).hasValue(1.0);
      assertThat(heldArtwork.scheduling().pendingTargets()).isEmpty();
    }

    @Test
    @DisplayName("Should refresh every movie when a refresh task cannot read its artwork results")
    void shouldRefreshEveryMovieWhenARefreshTaskCannotReadItsArtworkResults() throws Exception {
      var library = buildMovieLibrary();
      var first = saveMovieWithTmdbId("Inception", "27205", library);
      var second = saveMovieWithTmdbId("Interstellar", "157336", library);
      stubMovieMetadataWithPoster("27205", library);
      stubMovieMetadataWithPoster("157336", library);
      artwork
          .requests()
          .failNextArtworkStateReadWith(
              new CannotGetJdbcConnectionException(
                  "simulated pool timeout",
                  new SQLTransientConnectionException("connection not available")));
      var service = refreshServiceWith(artwork, admissionsWith(new FixedLimitAdmissions(1)));

      BoundedTask.runWithin(Duration.ofSeconds(5), () -> service.refreshLibrary(library));

      assertThat(itemResults.findByItem(first.getId(), ImageEntityType.MOVIE)).isNotEmpty();
      assertThat(itemResults.findByItem(second.getId(), ImageEntityType.MOVIE)).isNotEmpty();
      assertThat(refreshReports("drop")).isEqualTo(1);
    }

    @Test
    @DisplayName("Should admit no further title and fail the refresh when a refresh task throws")
    void shouldAdmitNoFurtherTitleAndFailTheRefreshWhenARefreshTaskThrows() {
      var library = buildMovieLibrary();
      saveMovieWithTmdbId("Inception", "27205", library);
      saveMovieWithTmdbId("Interstellar", "157336", library);
      var fetches = new AtomicInteger();
      when(movieProviderResolver.getMetadata(any(), eq(library)))
          .thenAnswer(
              _ -> {
                fetches.incrementAndGet();
                return new MetadataFetchOutcome.NotFound<>();
              });
      var failure = new DataAccessResourceFailureException("database unavailable");
      itemResults.failWritesWith(failure);
      var service = refreshServiceWith(artwork, admissionsWith(new FixedLimitAdmissions(1)));

      assertThatThrownBy(() -> service.refreshLibrary(library))
          .isInstanceOf(LibraryRefreshFailedException.class)
          .hasRootCause(failure);
      assertThat(fetches).hasValue(1);
      assertThat(refreshAdmissions()).isEqualTo(1);
    }

    @Test
    @DisplayName(
        "Should fail the refresh and keep the interrupt when the refreshing thread is interrupted")
    void shouldFailTheRefreshAndKeepTheInterruptWhenTheRefreshingThreadIsInterrupted() {
      var library = buildMovieLibrary();
      saveMovieWithTmdbId("Inception", "27205", library);
      stubMovieMetadata("27205", library);

      try {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> refreshService.refreshLibrary(library))
            .isInstanceOf(LibraryRefreshFailedException.class)
            .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
    }

    @Test
    @DisplayName("Should take no admission for a title without a TMDB id")
    void shouldTakeNoAdmissionForATitleWithoutATmdbId() {
      var library = buildMovieLibrary();
      movieRepository.save(Movie.builder().title("No TMDB ID").library(library).build());

      refreshService.refreshLibrary(library);

      assertThat(refreshAdmissions()).isZero();
    }

    private double refreshTasksInFlight() {
      return meterRegistry
          .get("streamarr.library.admission.in_flight")
          .tag("operation", "refresh")
          .gauge()
          .value();
    }
  }

  @Test
  @DisplayName("Should record succeeded metadata when a movie refreshes")
  void shouldRecordSucceededMetadataWhenAMovieRefreshes() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    stubMovieMetadata("27205", library);

    refreshService.refreshLibrary(library);

    assertThat(itemResults.find(movie.getId(), ItemStep.METADATA, null))
        .contains(
            ItemResult.builder()
                .itemId(movie.getId())
                .itemType(ImageEntityType.MOVIE)
                .step(ItemStep.METADATA)
                .outcome(new ItemOutcome.Succeeded())
                .attemptedAt(clock.instant())
                .build());
  }

  @Test
  @DisplayName("Should record a temporary failure with its detail when the movie fetch fails")
  void shouldRecordATemporaryFailureWithItsDetailWhenTheMovieFetchFails() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));

    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(movie.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "IOException: connection reset"));
  }

  @Test
  @DisplayName("Should record a misconfiguration when the provider rejects the credentials")
  void shouldRecordAMisconfigurationWhenTheProviderRejectsTheCredentials() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Failed<>(new TmdbApiException(401, "Invalid API key")));

    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(movie.getId()))
        .isInstanceOfSatisfying(
            ItemOutcome.Failed.class,
            failed -> assertThat(failed.reason()).isEqualTo(ItemFailureReason.MISCONFIGURED));
  }

  @Test
  @DisplayName("Should record unavailable metadata when the provider no longer has the movie")
  void shouldRecordUnavailableMetadataWhenTheProviderNoLongerHasTheMovie() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.NotFound<>());

    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(movie.getId())).isEqualTo(new ItemOutcome.Unavailable());
  }

  @Test
  @DisplayName("Should record a temporary failure when refreshing a movie throws")
  void shouldRecordATemporaryFailureWhenRefreshingAMovieThrows() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenThrow(new IllegalStateException("database hiccup"));

    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(movie.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(
                ItemFailureReason.TEMPORARY, "IllegalStateException: database hiccup"));
  }

  @Test
  @DisplayName("Should resolve the metadata failure when a later refresh succeeds")
  void shouldResolveTheMetadataFailureWhenALaterRefreshSucceeds() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));
    refreshService.refreshLibrary(library);

    clock.advance(Duration.ofMinutes(5));
    stubMovieMetadata("27205", library);
    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(movie.getId())).isEqualTo(new ItemOutcome.Succeeded());
  }

  @Test
  @DisplayName("Should record the series as failed when a season fetch fails")
  void shouldRecordTheSeriesAsFailedWhenASeasonFetchFails() {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1, 2)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(new MetadataFetchOutcome.Found<>(seasonDetails(2)));

    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(series.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(
                ItemFailureReason.TEMPORARY, "Season 1: IOException: connection reset"));
    assertThat(seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId()))
        .extracting(Season::getSeasonNumber)
        .containsExactly(2);
  }

  @Test
  @DisplayName("Should record the series as failed when a listed season is no longer found")
  void shouldRecordTheSeriesAsFailedWhenAListedSeasonIsNoLongerFound() {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1, 2)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(new MetadataFetchOutcome.Found<>(seasonDetails(1)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));
    refreshService.refreshLibrary(library);

    clock.advance(Duration.ofMinutes(5));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(new MetadataFetchOutcome.NotFound<>());
    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(series.getId()))
        .isEqualTo(new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "Season 2: not found"));
  }

  @Test
  @DisplayName("Should record the series as failed when its season list cannot be fetched")
  void shouldRecordTheSeriesAsFailedWhenItsSeasonListCannotBeFetched() {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));

    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(series.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(
                ItemFailureReason.TEMPORARY, "Season list: IOException: connection reset"));
  }

  @Test
  @DisplayName("Should record succeeded series metadata when every season refreshes")
  void shouldRecordSucceededSeriesMetadataWhenEverySeasonRefreshes() {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(new MetadataFetchOutcome.Found<>(seasonDetails(1)));

    refreshService.refreshLibrary(library);

    assertThat(metadataOutcome(series.getId())).isEqualTo(new ItemOutcome.Succeeded());
  }

  @Test
  @DisplayName("Should report the database error when a refresh result cannot be recorded")
  void shouldReportTheDatabaseErrorWhenARefreshResultCannotBeRecorded() {
    var library = buildMovieLibrary();
    saveMovieWithTmdbId("Inception", "27205", library);
    stubMovieMetadata("27205", library);
    var failure = new DataAccessResourceFailureException("database unavailable");
    itemResults.failWritesWith(failure);

    assertThatThrownBy(() -> refreshService.refreshLibrary(library)).hasRootCause(failure);
  }

  @Test
  @DisplayName("Should refresh all series with fresh TMDB metadata when refreshing library")
  void shouldRefreshAllSeriesWithFreshTmdbMetadataWhenRefreshingLibrary() {
    var library = buildSeriesLibrary();
    var series1 = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    var series2 = saveSeriesWithTmdbId("Better Call Saul", "60059", library);

    stubSeriesMetadata("1396", "Breaking Bad (Updated)", library);
    stubSeriesMetadata("60059", "Better Call Saul (Updated)", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any()))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of()));

    refreshService.refreshLibrary(library);

    assertThat(seriesRepository.findById(series1.getId()).orElseThrow().getTitle())
        .isEqualTo("Breaking Bad (Updated)");
    assertThat(seriesRepository.findById(series2.getId()).orElseThrow().getTitle())
        .isEqualTo("Better Call Saul (Updated)");
  }

  @Test
  @DisplayName("Should skip series without TMDB ID when refreshing library")
  void shouldSkipSeriesWithoutTmdbIdWhenRefreshingLibrary() {
    var library = buildSeriesLibrary();
    var series =
        seriesRepository.save(Series.builder().title("No TMDB ID").library(library).build());

    refreshService.refreshLibrary(library);

    assertThat(seriesRepository.findById(series.getId()).orElseThrow().getTitle())
        .isEqualTo("No TMDB ID");
  }

  @Test
  @DisplayName("Should continue refreshing when one series metadata fetch returns empty")
  void shouldContinueRefreshingWhenOneSeriesMetadataFetchReturnsEmpty() {
    var library = buildSeriesLibrary();
    var failingSeries = saveSeriesWithTmdbId("Failing Series", "99999", library);
    var series2 = saveSeriesWithTmdbId("Working Series", "1396", library);

    when(seriesProviderResolver.getMetadata(argThatHasExternalId("99999"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("simulated fetch failure")));
    stubSeriesMetadata("1396", "Working Series (Updated)", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any()))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of()));

    refreshService.refreshLibrary(library);

    assertThat(seriesRepository.findById(failingSeries.getId()).orElseThrow().getTitle())
        .isEqualTo("Failing Series");
    assertThat(seriesRepository.findById(series2.getId()).orElseThrow().getTitle())
        .isEqualTo("Working Series (Updated)");
  }

  @Test
  @DisplayName("Should continue refreshing when one series throws exception")
  void shouldContinueRefreshingWhenOneSeriesThrowsException() {
    var library = buildSeriesLibrary();
    var explodingSeries = saveSeriesWithTmdbId("Exploding Series", "99999", library);
    var series2 = saveSeriesWithTmdbId("Working Series", "1396", library);

    when(seriesProviderResolver.getMetadata(argThatHasExternalId("99999"), eq(library)))
        .thenThrow(new RuntimeException("TMDB API timeout"));
    stubSeriesMetadata("1396", "Working Series (Updated)", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any()))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of()));

    refreshService.refreshLibrary(library);

    assertThat(seriesRepository.findById(explodingSeries.getId()).orElseThrow().getTitle())
        .isEqualTo("Exploding Series");
    assertThat(seriesRepository.findById(series2.getId()).orElseThrow().getTitle())
        .isEqualTo("Working Series (Updated)");
  }

  @Test
  @DisplayName("Should skip series when series has only non-TMDB external IDs")
  void shouldSkipSeriesWhenSeriesHasOnlyNonTmdbExternalIds() {
    var library = buildSeriesLibrary();
    var series =
        seriesRepository.save(
            Series.builder()
                .title("IMDB-only Series")
                .library(library)
                .externalIds(
                    Set.of(
                        ExternalIdentifier.builder()
                            .externalSourceType(ExternalSourceType.IMDB)
                            .externalId("tt1234567")
                            .build()))
                .build());

    refreshService.refreshLibrary(library);

    assertThat(seriesRepository.findById(series.getId()).orElseThrow().getTitle())
        .isEqualTo("IMDB-only Series");
  }

  @Test
  @DisplayName("Should refresh seasons and episodes when refreshing series")
  void shouldRefreshSeasonsAndEpisodesWhenRefreshingSeries() {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);

    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1)));

    var seasonDetails =
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .overview("The first season")
            .airDate(LocalDate.of(2008, 1, 20))
            .imageSources(List.of())
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(1)
                        .name("Pilot")
                        .overview("A chemistry teacher turns to crime.")
                        .imageSources(List.of())
                        .build()))
            .build();
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(new MetadataFetchOutcome.Found<>(seasonDetails));

    refreshService.refreshLibrary(library);

    var seasons = seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId());
    assertThat(seasons).hasSize(1);
    assertThat(seasons.getFirst().getTitle()).isEqualTo("Season 1");

    var episodes = episodeRepository.findBySeasonIdOrderByEpisodeNumber(seasons.getFirst().getId());
    assertThat(episodes).hasSize(1);
    assertThat(episodes.getFirst().getTitle()).isEqualTo("Pilot");
  }

  @Test
  @DisplayName(
      "Should propagate image refresh mode when refreshing series season and episode artwork")
  void shouldPropagateImageRefreshModeWhenRefreshingSeriesSeasonAndEpisodeArtwork() {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    imageRepository.save(
        imageBuilder(series.getId())
            .entityType(ImageEntityType.SERIES)
            .key("/old-series.jpg")
            .path("series/old")
            .build());
    var freshSeries = Series.builder().title("Breaking Bad").titleSort("breaking bad").build();
    when(seriesProviderResolver.getMetadata(argThatHasExternalId("1396"), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                MetadataFixture.<Series>metadataResultBuilder()
                    .entity(freshSeries)
                    .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/series.jpg")))
                    .build()));
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                SeasonDetails.builder()
                    .name("Season 1")
                    .seasonNumber(1)
                    .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/season.jpg")))
                    .episodes(
                        List.of(
                            SeasonDetails.EpisodeDetails.builder()
                                .episodeNumber(1)
                                .name("Pilot")
                                .imageSources(
                                    List.of(new TmdbImageSource(ImageType.STILL, "/episode.jpg")))
                                .build()))
                    .build()));

    refreshService.refreshLibrary(library, ImageRefreshMode.REFRESH_IF_CHANGED);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(imagesOf(series.getId(), ImageEntityType.SERIES))
                  .extracting(Image::getKey)
                  .containsOnly("/series.jpg");
              var season =
                  seasonRepository.findBySeriesIdAndSeasonNumber(series.getId(), 1).orElseThrow();
              assertThat(imagesOf(season.getId(), ImageEntityType.SEASON))
                  .extracting(Image::getKey)
                  .containsOnly("/season.jpg");
              var episode =
                  episodeRepository.findBySeasonIdAndEpisodeNumber(season.getId(), 1).orElseThrow();
              assertThat(imagesOf(episode.getId(), ImageEntityType.EPISODE))
                  .extracting(Image::getKey)
                  .containsOnly("/episode.jpg");
            });
  }

  @Test
  @DisplayName("Should keep the refresh running when required artwork is not saved")
  void shouldKeepTheRefreshRunningWhenRequiredArtworkIsNotSaved() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    stubMovieMetadataWithPoster("27205", library);
    imageDownloader.holdPathsStartingWith("/poster");

    try (var refresh =
        BoundedTask.start(
            () -> {
              refreshService.refreshLibrary(library);
              return !imagesOf(movie.getId(), ImageEntityType.MOVIE).isEmpty();
            })) {
      imageDownloader.awaitHeldDownloads(1, Duration.ofSeconds(5));
      imageDownloader.releaseHeldDownloads();

      assertThat(refresh.await(Duration.ofSeconds(5)))
          .as("poster saved when the refresh returned")
          .isTrue();
    } finally {
      imageDownloader.releaseHeldDownloads();
    }
  }

  @Test
  @DisplayName(
      "Should finish the refresh when a retried artwork step stores the result that failed to save")
  void shouldFinishTheRefreshWhenARetriedArtworkStepStoresTheResultThatFailedToSave()
      throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    stubMovieMetadataWithPoster("27205", library);
    artworkResults.failArtworkResultWritesWith(
        new DataAccessResourceFailureException("database unavailable"), 1);

    try (var refresh =
        BoundedTask.start(
            () -> {
              refreshService.refreshLibrary(library);
              return artworkResults.findByItem(movie.getId(), ImageEntityType.MOVIE).stream()
                  .filter(result -> result.step() == ItemStep.ARTWORK)
                  .map(ItemResult::outcome)
                  .toList();
            })) {
      artwork.scheduling().awaitUnfinishedAttempt(Duration.ofSeconds(5));
      artwork.scheduling().runPendingSteps();

      assertThat(refresh.await(Duration.ofSeconds(5)))
          .containsExactlyInAnyOrder(new ItemOutcome.Succeeded(), new ItemOutcome.Unavailable());
    }
  }

  @Test
  @DisplayName("Should refresh all movies with fresh TMDB metadata when refreshing library")
  void shouldRefreshAllMoviesWithFreshTmdbMetadataWhenRefreshingLibrary() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);

    var freshMovie =
        Movie.builder().title("Inception (Updated)").titleSort("inception (updated)").build();
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(freshMovie, List.of(), Map.of(), Map.of())));

    refreshService.refreshLibrary(library);

    assertThat(movieRepository.findById(movie.getId()).orElseThrow().getTitle())
        .isEqualTo("Inception (Updated)");
  }

  @Test
  @DisplayName(
      "Should replace movie artwork and fetch person and company artwork when a refresh forces"
          + " image refresh")
  void shouldReplaceMovieArtworkAndFetchPersonAndCompanyArtworkWhenARefreshForcesImageRefresh() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    var stored =
        imageRepository.save(
            imageBuilder(movie.getId()).key("/poster.jpg").path("movie/poster").build());
    var person = Person.builder().name("Leonardo DiCaprio").sourceId("actor-1").build();
    var company = Company.builder().name("Warner Bros.").sourceId("studio-1").build();
    var freshMovie =
        Movie.builder()
            .title("Inception")
            .titleSort("inception")
            .cast(List.of(person))
            .studios(Set.of(company))
            .build();
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                MetadataFixture.<Movie>metadataResultBuilder()
                    .entity(freshMovie)
                    .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
                    .personImageSources(
                        Map.of(
                            "actor-1",
                            List.of(new TmdbImageSource(ImageType.PROFILE, "/actor.jpg"))))
                    .companyImageSources(
                        Map.of(
                            "studio-1",
                            List.of(new TmdbImageSource(ImageType.LOGO, "/studio.jpg"))))
                    .build()));

    refreshService.refreshLibrary(library, ImageRefreshMode.FORCE_REFRESH);

    assertThat(artworkResults.findAll())
        .filteredOn(result -> result.step() == ItemStep.ARTWORK)
        .filteredOn(result -> result.itemType() != ImageEntityType.MOVIE)
        .extracting(ItemResult::itemType, ItemResult::outcome)
        .containsExactlyInAnyOrder(
            tuple(ImageEntityType.PERSON, new ItemOutcome.Succeeded()),
            tuple(ImageEntityType.COMPANY, new ItemOutcome.Succeeded()));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(imagesOf(movie.getId(), ImageEntityType.MOVIE))
                    .extracting(Image::getId)
                    .isNotEmpty()
                    .doesNotContain(stored.getId()));
  }

  @Test
  @DisplayName("Should skip movie without TMDB ID when refreshing library")
  void shouldSkipMovieWithoutTmdbIdWhenRefreshingLibrary() {
    var library = buildMovieLibrary();
    var movie = movieRepository.save(Movie.builder().title("No TMDB ID").library(library).build());

    refreshService.refreshLibrary(library);

    assertThat(movieRepository.findById(movie.getId()).orElseThrow().getTitle())
        .isEqualTo("No TMDB ID");
  }

  @Test
  @DisplayName("Should throw UnsupportedMediaTypeException when library has OTHER type")
  void shouldThrowUnsupportedMediaTypeExceptionForOtherType() {
    var library =
        Library.builder()
            .id(UUID.randomUUID())
            .name("Other Media")
            .type(MediaType.OTHER)
            .status(LibraryStatus.HEALTHY)
            .backend(LibraryBackend.LOCAL)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();

    assertThatThrownBy(() -> refreshService.refreshLibrary(library))
        .isInstanceOf(UnsupportedMediaTypeException.class);
  }

  @Test
  @DisplayName("Should skip season when season details fetch returns empty")
  void shouldSkipSeasonWhenSeasonDetailsFetchReturnsEmpty() {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);

    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1, 2)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("simulated fetch failure")));

    var seasonDetails =
        SeasonDetails.builder()
            .name("Season 2")
            .seasonNumber(2)
            .overview("The second season")
            .imageSources(List.of())
            .episodes(List.of())
            .build();
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(new MetadataFetchOutcome.Found<>(seasonDetails));

    refreshService.refreshLibrary(library);

    var seasons = seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId());
    assertThat(seasons).hasSize(1);
    assertThat(seasons.getFirst().getTitle()).isEqualTo("Season 2");
  }

  @Test
  @DisplayName("Should skip movie when metadata fetch returns empty")
  void shouldSkipMovieWhenMetadataFetchReturnsEmpty() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);

    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("simulated fetch failure")));

    refreshService.refreshLibrary(library);

    assertThat(movieRepository.findById(movie.getId()).orElseThrow().getTitle())
        .isEqualTo("Inception");
  }

  @Test
  @DisplayName("Should continue refreshing movies when one movie fails with exception")
  void shouldContinueRefreshingMoviesWhenOneMovieFails() {
    var library = buildMovieLibrary();
    var failingMovie = saveMovieWithTmdbId("Failing Movie", "99999", library);
    var movie2 = saveMovieWithTmdbId("Working Movie", "27205", library);

    when(movieProviderResolver.getMetadata(argThatHasExternalId("99999"), eq(library)))
        .thenThrow(new RuntimeException("simulated failure"));

    var freshMovie = Movie.builder().title("Working Movie (Updated)").build();
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(freshMovie, List.of(), Map.of(), Map.of())));

    refreshService.refreshLibrary(library);

    assertThat(movieRepository.findById(failingMovie.getId()).orElseThrow().getTitle())
        .isEqualTo("Failing Movie");
    assertThat(movieRepository.findById(movie2.getId()).orElseThrow().getTitle())
        .isEqualTo("Working Movie (Updated)");
  }

  @Test
  @DisplayName("Should complete successfully when series library has no items")
  void shouldCompleteSuccessfullyWhenSeriesLibraryHasNoItems() {
    var library = buildSeriesLibrary();

    refreshService.refreshLibrary(library);

    assertThat(seriesRepository.findAll()).isEmpty();
    assertThat(seasonRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should complete successfully when movie library has no items")
  void shouldCompleteSuccessfullyWhenMovieLibraryHasNoItems() {
    var library = buildMovieLibrary();

    refreshService.refreshLibrary(library);

    assertThat(movieRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should abort remaining season refresh when season details fetch throws exception")
  void shouldAbortRemainingSeasonRefreshWhenSeasonDetailsFetchThrowsException() {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);

    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1, 2)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenThrow(new RuntimeException("API failure"));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                SeasonDetails.builder()
                    .name("Season 2")
                    .seasonNumber(2)
                    .imageSources(List.of())
                    .episodes(List.of())
                    .build()));

    refreshService.refreshLibrary(library);

    assertThat(seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId())).isEmpty();
  }

  private List<Image> imagesOf(UUID entityId, ImageEntityType entityType) {
    return imageRepository.findByEntityIdAndEntityType(entityId, entityType);
  }

  private Library buildSeriesLibrary() {
    return Library.builder()
        .id(UUID.randomUUID())
        .name("TV Shows")
        .type(MediaType.SERIES)
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .build();
  }

  private Library buildMovieLibrary() {
    return Library.builder()
        .id(UUID.randomUUID())
        .name("Movies")
        .type(MediaType.MOVIE)
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .build();
  }

  private Series saveSeriesWithTmdbId(String title, String tmdbId, Library library) {
    return seriesRepository.save(
        Series.builder()
            .title(title)
            .library(library)
            .externalIds(
                Set.of(
                    ExternalIdentifier.builder()
                        .externalSourceType(ExternalSourceType.TMDB)
                        .externalId(tmdbId)
                        .build()))
            .build());
  }

  private Movie saveMovieWithTmdbId(String title, String tmdbId, Library library) {
    return movieRepository.save(
        Movie.builder()
            .title(title)
            .library(library)
            .externalIds(
                Set.of(
                    ExternalIdentifier.builder()
                        .externalSourceType(ExternalSourceType.TMDB)
                        .externalId(tmdbId)
                        .build()))
            .build());
  }

  private ItemOutcome metadataOutcome(UUID itemId) {
    return itemResults.find(itemId, ItemStep.METADATA, null).orElseThrow().outcome();
  }

  private void stubMovieMetadataWithPoster(String tmdbId, Library library) {
    when(movieProviderResolver.getMetadata(argThatHasExternalId(tmdbId), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                MetadataFixture.<Movie>metadataResultBuilder()
                    .entity(Movie.builder().title("Inception").titleSort("inception").build())
                    .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
                    .build()));
  }

  private void stubMovieMetadata(String tmdbId, Library library) {
    var freshMovie = Movie.builder().title("Refreshed").titleSort("refreshed").build();
    when(movieProviderResolver.getMetadata(argThatHasExternalId(tmdbId), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(freshMovie, List.of(), Map.of(), Map.of())));
  }

  private static SeasonDetails seasonDetails(int seasonNumber) {
    return SeasonDetails.builder()
        .name("Season " + seasonNumber)
        .seasonNumber(seasonNumber)
        .imageSources(List.of())
        .episodes(List.of())
        .build();
  }

  private void stubSeriesMetadata(String tmdbId, String freshTitle, Library library) {
    var freshSeries =
        Series.builder().title(freshTitle).titleSort(freshTitle.toLowerCase()).build();
    when(seriesProviderResolver.getMetadata(argThatHasExternalId(tmdbId), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(freshSeries, List.of(), Map.of(), Map.of())));
  }

  private static <T> T argThatHasExternalId(String externalId) {
    return argThat(
        arg -> {
          if (arg instanceof RemoteSearchResult rsr) {
            return externalId.equals(rsr.externalId());
          }
          return false;
        });
  }
}
