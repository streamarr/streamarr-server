package com.streamarr.server.services.library;

import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fixtures.AdmissionEndFixture.neverReached;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FixedLimitAdmissions;
import com.streamarr.server.fakes.GatedImageDownloader;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.ArtworkFixture;
import com.streamarr.server.fixtures.CreditServiceFixture;
import com.streamarr.server.fixtures.LibraryOperationFixture;
import com.streamarr.server.fixtures.MetadataFixture;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.LibraryMoviesList;
import com.streamarr.server.services.LibrarySeriesList;
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
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.transaction.support.TransactionTemplate;

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
            new FakeItemResultRepository(),
            _ -> {});

    var seriesService =
        new SeriesService(
            seriesRepository,
            creditService,
            new LibrarySeriesList(seriesRepository),
            artworkRequestService,
            imageService,
            seasonRepository,
            episodeRepository,
            null,
            null,
            null,
            null,
            _ -> {});

    var movieService =
        new MovieService(
            movieRepository,
            creditService,
            new LibraryMoviesList(movieRepository),
            artworkRequestService,
            imageService,
            null,
            null,
            null,
            null,
            null,
            null,
            _ -> {});

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
        .transactionTemplate(new TransactionTemplate(new FakeTransactionManager()))
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
    void shouldAdmitEachMovieThroughTheLimitWhenRefreshing() throws Exception {
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

      service.refreshLibrary(library, refreshOf(library), neverReached());

      assertThat(reportsAtEachFetch).containsExactly(0.0, 1.0);
      assertThat(refreshAdmissions()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should admit each series through the limit when refreshing")
    void shouldAdmitEachSeriesThroughTheLimitWhenRefreshing() throws Exception {
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

      service.refreshLibrary(library, refreshOf(library), neverReached());

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

      BoundedTask.runWithin(
          Duration.ofSeconds(5),
          () -> service.refreshLibrary(library, refreshOf(library), neverReached()));

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
          .failNextCompletionEvidenceReadWith(
              new CannotGetJdbcConnectionException(
                  "simulated pool timeout",
                  new SQLTransientConnectionException("connection not available")));
      var service = refreshServiceWith(artwork, admissionsWith(new FixedLimitAdmissions(1)));

      BoundedTask.runWithin(
          Duration.ofSeconds(5),
          () -> service.refreshLibrary(library, refreshOf(library), neverReached()));

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
      var refresh = refreshOf(library);

      assertThatThrownBy(() -> service.refreshLibrary(library, refresh, neverReached()))
          .isInstanceOf(LibraryRefreshFailedException.class)
          .hasRootCause(failure);
      assertThat(fetches).hasValue(1);
      assertThat(refreshAdmissions()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should throw InterruptedException when the refreshing thread is interrupted")
    void shouldThrowInterruptedExceptionWhenTheRefreshingThreadIsInterrupted() {
      var library = buildMovieLibrary();
      saveMovieWithTmdbId("Inception", "27205", library);
      stubMovieMetadata("27205", library);

      try {
        Thread.currentThread().interrupt();

        assertThatThrownBy(
                () -> refreshService.refreshLibrary(library, refreshOf(library), neverReached()))
            .isInstanceOf(InterruptedException.class);
      } finally {
        Thread.interrupted();
      }
    }

    @Test
    @DisplayName("Should take no admission for a title without a TMDB id")
    void shouldTakeNoAdmissionForATitleWithoutATmdbId() throws Exception {
      var library = buildMovieLibrary();
      movieRepository.save(Movie.builder().title("No TMDB ID").library(library).build());

      refreshService.refreshLibrary(library, refreshOf(library), neverReached());

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
  void shouldRecordSucceededMetadataWhenAMovieRefreshes() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    stubMovieMetadata("27205", library);
    var operation = refreshOf(library);

    refreshService.refreshLibrary(library, operation, neverReached());

    assertThat(itemResults.find(movie.getId(), ItemStep.METADATA, null))
        .contains(
            ItemResult.builder()
                .itemId(movie.getId())
                .itemType(ImageEntityType.MOVIE)
                .step(ItemStep.METADATA)
                .outcome(new ItemOutcome.Succeeded())
                .attemptedAt(clock.instant())
                .libraryOperationId(Optional.of(operation.id()))
                .build());
  }

  @Test
  @DisplayName("Should record a temporary failure with its detail when the movie fetch fails")
  void shouldRecordATemporaryFailureWithItsDetailWhenTheMovieFetchFails() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(movie.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "IOException: connection reset"));
  }

  @Test
  @DisplayName("Should record a misconfiguration when the provider rejects the credentials")
  void shouldRecordAMisconfigurationWhenTheProviderRejectsTheCredentials() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Failed<>(new TmdbApiException(401, "Invalid API key")));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(movie.getId()))
        .isInstanceOfSatisfying(
            ItemOutcome.Failed.class,
            failed -> assertThat(failed.reason()).isEqualTo(ItemFailureReason.MISCONFIGURED));
  }

  @Test
  @DisplayName("Should record unavailable metadata when the provider no longer has the movie")
  void shouldRecordUnavailableMetadataWhenTheProviderNoLongerHasTheMovie() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.NotFound<>());

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(movie.getId())).isEqualTo(new ItemOutcome.Unavailable());
  }

  @Test
  @DisplayName("Should record a temporary failure when refreshing a movie throws")
  void shouldRecordATemporaryFailureWhenRefreshingAMovieThrows() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenThrow(new IllegalStateException("database hiccup"));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(movie.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(
                ItemFailureReason.TEMPORARY, "IllegalStateException: database hiccup"));
  }

  @Test
  @DisplayName("Should resolve the metadata failure when a later refresh succeeds")
  void shouldResolveTheMetadataFailureWhenALaterRefreshSucceeds() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));
    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    clock.advance(Duration.ofMinutes(5));
    stubMovieMetadata("27205", library);
    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(movie.getId())).isEqualTo(new ItemOutcome.Succeeded());
  }

  @Test
  @DisplayName("Should record the series as failed when a season fetch fails")
  void shouldRecordTheSeriesAsFailedWhenASeasonFetchFails() throws Exception {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1, 2)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(new MetadataFetchOutcome.Found<>(seasonDetails(2)));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(series.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(
                ItemFailureReason.TEMPORARY, "Season 1: IOException: connection reset"));
    assertThat(seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId()))
        .extracting(Season::getSeasonNumber)
        .containsExactly(2);
  }

  @Test
  @DisplayName(
      "Should save a metadata result carrying the refresh's library operation id for each"
          + " refreshed season")
  void shouldSaveAMetadataResultCarryingTheRefreshsLibraryOperationIdForEachRefreshedSeason()
      throws Exception {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    stubSeasons("1396", library, 1, 2);
    var operation = refreshOf(library);

    refreshService.refreshLibrary(library, operation, neverReached());

    assertThat(seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId()))
        .allSatisfy(
            season ->
                assertThat(itemResults.find(season.getId(), ItemStep.METADATA, null))
                    .hasValueSatisfying(
                        result -> {
                          assertThat(result.itemType()).isEqualTo(ImageEntityType.SEASON);
                          assertThat(result.outcome()).isEqualTo(new ItemOutcome.Succeeded());
                          assertThat(result.libraryOperationId()).contains(operation.id());
                        }))
        .hasSize(2);
  }

  @Test
  @DisplayName(
      "Should keep the earlier seasons' results and save the series as failed when a later"
          + " season fails")
  void shouldKeepTheEarlierSeasonsResultsAndSaveTheSeriesAsFailedWhenALaterSeasonFails()
      throws Exception {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    stubSeasons("1396", library, 1, 2, 3);
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(series.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(
                ItemFailureReason.TEMPORARY, "Season 2: IOException: connection reset"));
    assertThat(seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId()))
        .extracting(Season::getSeasonNumber, season -> metadataOutcome(season.getId()))
        .containsExactly(
            tuple(1, new ItemOutcome.Succeeded()), tuple(3, new ItemOutcome.Succeeded()));
  }

  @Test
  @DisplayName("Should record the series as failed when a listed season is no longer found")
  void shouldRecordTheSeriesAsFailedWhenAListedSeasonIsNoLongerFound() throws Exception {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1, 2)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(new MetadataFetchOutcome.Found<>(seasonDetails(1)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));
    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    clock.advance(Duration.ofMinutes(5));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(new MetadataFetchOutcome.NotFound<>());
    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(series.getId()))
        .isEqualTo(new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "Season 2: not found"));
  }

  @Test
  @DisplayName("Should record the series as failed when its season list cannot be fetched")
  void shouldRecordTheSeriesAsFailedWhenItsSeasonListCannotBeFetched() throws Exception {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("connection reset")));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(metadataOutcome(series.getId()))
        .isEqualTo(
            new ItemOutcome.Failed(
                ItemFailureReason.TEMPORARY, "Season list: IOException: connection reset"));
  }

  @Test
  @DisplayName("Should record succeeded series metadata when every season refreshes")
  void shouldRecordSucceededSeriesMetadataWhenEverySeasonRefreshes() throws Exception {
    var library = buildSeriesLibrary();
    var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    stubSeriesMetadata("1396", "Breaking Bad", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396"))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(1)));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenReturn(new MetadataFetchOutcome.Found<>(seasonDetails(1)));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

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

    assertThatThrownBy(
            () -> refreshService.refreshLibrary(library, refreshOf(library), neverReached()))
        .hasRootCause(failure);
  }

  @Test
  @DisplayName("Should refresh all series with fresh TMDB metadata when refreshing library")
  void shouldRefreshAllSeriesWithFreshTmdbMetadataWhenRefreshingLibrary() throws Exception {
    var library = buildSeriesLibrary();
    var series1 = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    var series2 = saveSeriesWithTmdbId("Better Call Saul", "60059", library);

    stubSeriesMetadata("1396", "Breaking Bad (Updated)", library);
    stubSeriesMetadata("60059", "Better Call Saul (Updated)", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any()))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of()));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(seriesRepository.findById(series1.getId()).orElseThrow().getTitle())
        .isEqualTo("Breaking Bad (Updated)");
    assertThat(seriesRepository.findById(series2.getId()).orElseThrow().getTitle())
        .isEqualTo("Better Call Saul (Updated)");
  }

  @Test
  @DisplayName("Should skip series without TMDB ID when refreshing library")
  void shouldSkipSeriesWithoutTmdbIdWhenRefreshingLibrary() throws Exception {
    var library = buildSeriesLibrary();
    var series =
        seriesRepository.save(Series.builder().title("No TMDB ID").library(library).build());

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(seriesRepository.findById(series.getId()).orElseThrow().getTitle())
        .isEqualTo("No TMDB ID");
  }

  @Test
  @DisplayName("Should continue refreshing when one series metadata fetch returns empty")
  void shouldContinueRefreshingWhenOneSeriesMetadataFetchReturnsEmpty() throws Exception {
    var library = buildSeriesLibrary();
    var failingSeries = saveSeriesWithTmdbId("Failing Series", "99999", library);
    var series2 = saveSeriesWithTmdbId("Working Series", "1396", library);

    when(seriesProviderResolver.getMetadata(argThatHasExternalId("99999"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("simulated fetch failure")));
    stubSeriesMetadata("1396", "Working Series (Updated)", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any()))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of()));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(seriesRepository.findById(failingSeries.getId()).orElseThrow().getTitle())
        .isEqualTo("Failing Series");
    assertThat(seriesRepository.findById(series2.getId()).orElseThrow().getTitle())
        .isEqualTo("Working Series (Updated)");
  }

  @Test
  @DisplayName("Should continue refreshing when one series throws exception")
  void shouldContinueRefreshingWhenOneSeriesThrowsException() throws Exception {
    var library = buildSeriesLibrary();
    var explodingSeries = saveSeriesWithTmdbId("Exploding Series", "99999", library);
    var series2 = saveSeriesWithTmdbId("Working Series", "1396", library);

    when(seriesProviderResolver.getMetadata(argThatHasExternalId("99999"), eq(library)))
        .thenThrow(new RuntimeException("TMDB API timeout"));
    stubSeriesMetadata("1396", "Working Series (Updated)", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any()))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of()));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(seriesRepository.findById(explodingSeries.getId()).orElseThrow().getTitle())
        .isEqualTo("Exploding Series");
    assertThat(seriesRepository.findById(series2.getId()).orElseThrow().getTitle())
        .isEqualTo("Working Series (Updated)");
  }

  @Test
  @DisplayName("Should skip series when series has only non-TMDB external IDs")
  void shouldSkipSeriesWhenSeriesHasOnlyNonTmdbExternalIds() throws Exception {
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

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(seriesRepository.findById(series.getId()).orElseThrow().getTitle())
        .isEqualTo("IMDB-only Series");
  }

  @Test
  @DisplayName("Should refresh seasons and episodes when refreshing series")
  void shouldRefreshSeasonsAndEpisodesWhenRefreshingSeries() throws Exception {
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

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

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
  void shouldPropagateImageRefreshModeWhenRefreshingSeriesSeasonAndEpisodeArtwork()
      throws Exception {
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

    refreshService.refreshLibrary(
        library, refreshOf(library, ImageRefreshMode.REFRESH_IF_CHANGED), neverReached());

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
              refreshService.refreshLibrary(library, refreshOf(library), neverReached());
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
              refreshService.refreshLibrary(library, refreshOf(library), neverReached());
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
  void shouldRefreshAllMoviesWithFreshTmdbMetadataWhenRefreshingLibrary() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);

    var freshMovie =
        Movie.builder().title("Inception (Updated)").titleSort("inception (updated)").build();
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(
            new MetadataFetchOutcome.Found<>(
                new MetadataResult<>(freshMovie, List.of(), Map.of(), Map.of())));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(movieRepository.findById(movie.getId()).orElseThrow().getTitle())
        .isEqualTo("Inception (Updated)");
  }

  @Test
  @DisplayName(
      "Should replace movie artwork and fetch person and company artwork when a refresh forces"
          + " image refresh")
  void shouldReplaceMovieArtworkAndFetchPersonAndCompanyArtworkWhenARefreshForcesImageRefresh()
      throws Exception {
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

    refreshService.refreshLibrary(
        library, refreshOf(library, ImageRefreshMode.FORCE_REFRESH), neverReached());

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
  @DisplayName(
      "Should fetch the movie's metadata and finish on the forced refresh's artwork when an older"
          + " request that keeps stored artwork finishes after the refresh starts")
  void shouldFinishOnTheForcedRefreshsArtworkWhenAnOlderPreservingRequestFinishesAfterItStarts()
      throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);
    stubMovieMetadataWithPoster("27205", library);
    var downloader = new GatedImageDownloader(createTestImage(600, 900));
    downloader.holdPathsStartingWith("/poster");
    var resultCheckSleeper = new CountingSleeper();
    var heldArtwork =
        ArtworkFixture.builder()
            .imageRepository(imageRepository)
            .imageDownloader(downloader)
            .itemResults(artworkResults)
            .sleeper(resultCheckSleeper)
            .build();
    heldArtwork.scheduling().holdScheduledSteps();
    var artworkRequestService = heldArtwork.artworkRequestService();
    var movieArtwork =
        ArtworkSources.builder()
            .targetId(movie.getId())
            .targetType(ImageEntityType.MOVIE)
            .sources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
            .build();
    artworkRequestService.requestPrimaryArtwork(
        artworkRequestService.openRun("file discovery", ImageRefreshMode.PRESERVE), movieArtwork);
    var olderGeneration =
        heldArtwork.requests().findRequest(movieArtwork.target()).orElseThrow().generation();
    var service = refreshServiceWith(heldArtwork, admissionsWith(new AdaptiveAdmissions()));
    var refresh = refreshOf(library, ImageRefreshMode.FORCE_REFRESH);

    try (var olderAttempt = BoundedTask.start(() -> heldArtwork.scheduling().runPendingSteps())) {
      downloader.awaitHeldDownloads(1, Duration.ofSeconds(5));
      // The refresh checks for results only after it requested the movie's artwork, so the older
      // attempt finishes after the refresh started.
      resultCheckSleeper.onSleep(
          sleeps -> {
            if (sleeps == 1) {
              downloader.releaseHeldDownloads();
              awaitWithin(olderAttempt);
            }

            heldArtwork.scheduling().runPendingSteps();
          });

      BoundedTask.runWithin(
          Duration.ofSeconds(5), () -> service.refreshLibrary(library, refresh, neverReached()));
    } finally {
      downloader.releaseHeldDownloads();
    }

    assertThat(itemResults.find(movie.getId(), ItemStep.METADATA, null))
        .hasValueSatisfying(
            result -> {
              assertThat(result.outcome()).isEqualTo(new ItemOutcome.Succeeded());
              assertThat(result.libraryOperationId()).contains(refresh.id());
            });
    assertThat(artworkResults.findByItem(movie.getId(), ImageEntityType.MOVIE))
        .filteredOn(result -> result.step() == ItemStep.ARTWORK)
        .isNotEmpty()
        .allSatisfy(
            result -> {
              assertThat(result.artworkGeneration()).isGreaterThan(olderGeneration);
              assertThat(result.libraryOperationId()).contains(refresh.id());
            });
    assertThat(imagesOf(movie.getId(), ImageEntityType.MOVIE))
        .extracting(Image::getImageType)
        .isNotEmpty()
        .containsOnly(ImageType.POSTER);
  }

  private static void awaitWithin(BoundedTask<?> task) {
    try {
      task.await(Duration.ofSeconds(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while the older artwork attempt ran", e);
    } catch (Exception e) {
      throw new AssertionError("The older artwork attempt did not finish", e);
    }
  }

  @Test
  @DisplayName("Should skip movie without TMDB ID when refreshing library")
  void shouldSkipMovieWithoutTmdbIdWhenRefreshingLibrary() throws Exception {
    var library = buildMovieLibrary();
    var movie = movieRepository.save(Movie.builder().title("No TMDB ID").library(library).build());

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

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
    var refresh = refreshOf(library);

    assertThatThrownBy(() -> refreshService.refreshLibrary(library, refresh, neverReached()))
        .isInstanceOf(UnsupportedMediaTypeException.class);
  }

  @Test
  @DisplayName("Should skip season when season details fetch returns empty")
  void shouldSkipSeasonWhenSeasonDetailsFetchReturnsEmpty() throws Exception {
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

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    var seasons = seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId());
    assertThat(seasons).hasSize(1);
    assertThat(seasons.getFirst().getTitle()).isEqualTo("Season 2");
  }

  @Test
  @DisplayName("Should skip movie when metadata fetch returns empty")
  void shouldSkipMovieWhenMetadataFetchReturnsEmpty() throws Exception {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);

    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(new MetadataFetchOutcome.Failed<>(new IOException("simulated fetch failure")));

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(movieRepository.findById(movie.getId()).orElseThrow().getTitle())
        .isEqualTo("Inception");
  }

  @Test
  @DisplayName("Should continue refreshing movies when one movie fails with exception")
  void shouldContinueRefreshingMoviesWhenOneMovieFails() throws Exception {
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

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(movieRepository.findById(failingMovie.getId()).orElseThrow().getTitle())
        .isEqualTo("Failing Movie");
    assertThat(movieRepository.findById(movie2.getId()).orElseThrow().getTitle())
        .isEqualTo("Working Movie (Updated)");
  }

  @Test
  @DisplayName("Should complete successfully when series library has no items")
  void shouldCompleteSuccessfullyWhenSeriesLibraryHasNoItems() throws Exception {
    var library = buildSeriesLibrary();

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(seriesRepository.findAll()).isEmpty();
    assertThat(seasonRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should complete successfully when movie library has no items")
  void shouldCompleteSuccessfullyWhenMovieLibraryHasNoItems() throws Exception {
    var library = buildMovieLibrary();

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(movieRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should abort remaining season refresh when season details fetch throws exception")
  void shouldAbortRemainingSeasonRefreshWhenSeasonDetailsFetchThrowsException() throws Exception {
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

    refreshService.refreshLibrary(library, refreshOf(library), neverReached());

    assertThat(seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId())).isEmpty();
  }

  @Nested
  @DisplayName("Refresh after a restart")
  class RefreshAfterARestartTests {

    @Test
    @DisplayName(
        "Should skip a movie whose metadata result carries the refresh's library operation id")
    void shouldSkipAMovieWhoseMetadataResultCarriesTheRefreshsLibraryOperationId()
        throws Exception {
      var library = buildMovieLibrary();
      var refreshed = saveMovieWithTmdbId("Inception", "27205", library);
      saveMovieWithTmdbId("Interstellar", "157336", library);
      var fetchedTmdbIds = stubEveryMovieFetch(library);
      var operation = refreshOf(library);
      saveSucceededMetadataResult(refreshed.getId(), ImageEntityType.MOVIE, operation.id());

      refreshService.refreshLibrary(library, operation, neverReached());

      assertThat(fetchedTmdbIds).containsExactly("157336");
    }

    @Test
    @DisplayName(
        "Should refresh a movie whose metadata result carries another library operation's id")
    void shouldRefreshAMovieWhoseMetadataResultCarriesAnotherLibraryOperationsId()
        throws Exception {
      var library = buildMovieLibrary();
      var movie = saveMovieWithTmdbId("Inception", "27205", library);
      var fetchedTmdbIds = stubEveryMovieFetch(library);
      saveSucceededMetadataResult(movie.getId(), ImageEntityType.MOVIE, UUID.randomUUID());

      refreshService.refreshLibrary(library, refreshOf(library), neverReached());

      assertThat(fetchedTmdbIds).containsExactly("27205");
    }

    @Test
    @DisplayName(
        "Should skip a series whose metadata result carries the refresh's library operation id")
    void shouldSkipASeriesWhoseMetadataResultCarriesTheRefreshsLibraryOperationId()
        throws Exception {
      var library = buildSeriesLibrary();
      var series = saveSeriesWithTmdbId("Old Title", "1399", library);
      var fetches = new AtomicInteger();
      when(seriesProviderResolver.getMetadata(any(), eq(library)))
          .thenAnswer(
              _ -> {
                fetches.incrementAndGet();
                return new MetadataFetchOutcome.NotFound<>();
              });
      var operation = refreshOf(library);
      saveSucceededMetadataResult(series.getId(), ImageEntityType.SERIES, operation.id());

      refreshService.refreshLibrary(library, operation, neverReached());

      assertThat(fetches).hasValue(0);
    }

    @Test
    @DisplayName("Should refresh a movie again when a shutdown interrupted its fetch")
    void shouldRefreshAMovieAgainWhenAShutdownInterruptedItsFetch() throws Exception {
      var library = buildMovieLibrary();
      saveMovieWithTmdbId("Inception", "27205", library);
      when(movieProviderResolver.getMetadata(any(), eq(library)))
          .thenAnswer(_ -> interruptedFetch());
      var operation = refreshOf(library);
      refreshService.refreshLibrary(library, operation, neverReached());
      var fetchedTmdbIds = stubEveryMovieFetch(library);

      refreshService.refreshLibrary(library, operation, neverReached());

      assertThat(fetchedTmdbIds).containsExactly("27205");
    }

    @Test
    @DisplayName("Should refresh a series again when a shutdown interrupted its fetch")
    void shouldRefreshASeriesAgainWhenAShutdownInterruptedItsFetch() throws Exception {
      var library = buildSeriesLibrary();
      saveSeriesWithTmdbId("Old Title", "1399", library);
      when(seriesProviderResolver.getMetadata(any(), eq(library)))
          .thenAnswer(_ -> interruptedFetch());
      var operation = refreshOf(library);
      refreshService.refreshLibrary(library, operation, neverReached());
      var fetches = new AtomicInteger();
      when(seriesProviderResolver.getMetadata(any(), eq(library)))
          .thenAnswer(
              _ -> {
                fetches.incrementAndGet();
                return new MetadataFetchOutcome.NotFound<>();
              });

      refreshService.refreshLibrary(library, operation, neverReached());

      assertThat(fetches).hasValue(1);
    }

    @Test
    @DisplayName(
        "Should skip a season whose metadata result carries the refresh's library operation id")
    void shouldSkipASeasonWhoseMetadataResultCarriesTheRefreshsLibraryOperationId()
        throws Exception {
      var library = buildSeriesLibrary();
      var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
      var refreshedSeason = saveSeason(series, 1);
      saveSeason(series, 2);
      stubSeriesMetadata("1396", "Breaking Bad", library);
      var fetchedSeasonNumbers = stubSeasons("1396", library, 1, 2, 3);
      var operation = refreshOf(library);
      saveSucceededMetadataResult(refreshedSeason.getId(), ImageEntityType.SEASON, operation.id());

      refreshService.refreshLibrary(library, operation, neverReached());

      assertThat(fetchedSeasonNumbers).containsExactly(2, 3);
      assertThat(metadataOutcome(series.getId())).isEqualTo(new ItemOutcome.Succeeded());
    }

    @Test
    @DisplayName(
        "Should refresh a season whose metadata result carries another library operation's id")
    void shouldRefreshASeasonWhoseMetadataResultCarriesAnotherLibraryOperationsId()
        throws Exception {
      var library = buildSeriesLibrary();
      var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
      var season = saveSeason(series, 1);
      stubSeriesMetadata("1396", "Breaking Bad", library);
      var fetchedSeasonNumbers = stubSeasons("1396", library, 1, 2);
      saveSucceededMetadataResult(season.getId(), ImageEntityType.SEASON, UUID.randomUUID());

      refreshService.refreshLibrary(library, refreshOf(library), neverReached());

      assertThat(fetchedSeasonNumbers).containsExactly(1, 2);
    }

    @Test
    @DisplayName(
        "Should fetch nothing and change no result when the same refresh runs again after it"
            + " finished")
    void shouldFetchNothingAndChangeNoResultWhenTheSameRefreshRunsAgainAfterItFinished()
        throws Exception {
      var library = buildSeriesLibrary();
      var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
      stubSeriesMetadata("1396", "Breaking Bad", library);
      var fetchedSeasonNumbers = stubSeasons("1396", library, 1, 2);
      var operation = refreshOf(library);
      refreshService.refreshLibrary(library, operation, neverReached());
      var resultsAfterFirstRun = itemResults.findAll();
      var seasonsAfterFirstRun = seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId());
      clock.advance(Duration.ofMinutes(5));

      refreshService.refreshLibrary(library, operation, neverReached());

      assertThat(fetchedSeasonNumbers).containsExactly(1, 2);
      assertThat(itemResults.findAll()).containsExactlyInAnyOrderElementsOf(resultsAfterFirstRun);
      assertThat(seasonRepository.findBySeriesIdOrderBySeasonNumber(series.getId()))
          .containsExactlyElementsOf(seasonsAfterFirstRun);
    }

    @Test
    @DisplayName(
        "Should fetch no later season and save no series result when a shutdown interrupts a"
            + " season fetch, then resume at that season")
    void shouldFetchNoLaterSeasonWhenAShutdownInterruptsASeasonFetchThenResumeAtThatSeason()
        throws Exception {
      var library = buildSeriesLibrary();
      var series = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
      stubSeriesMetadata("1396", "Breaking Bad", library);
      var fetchedSeasonNumbers = stubSeasons("1396", library, 1, 2, 3);
      doAnswer(
              _ -> {
                fetchedSeasonNumbers.add(2);
                return interruptedFetch();
              })
          .doAnswer(
              _ -> {
                fetchedSeasonNumbers.add(2);
                return new MetadataFetchOutcome.Found<>(seasonDetails(2));
              })
          .when(seriesProviderResolver)
          .getSeasonDetails(library, "1396", 2);
      var operation = refreshOf(library);
      refreshService.refreshLibrary(library, operation, neverReached());
      var fetchedBeforeRestart = List.copyOf(fetchedSeasonNumbers);
      var seriesResultBeforeRestart = itemResults.find(series.getId(), ItemStep.METADATA, null);
      fetchedSeasonNumbers.clear();

      refreshService.refreshLibrary(library, operation, neverReached());

      assertThat(fetchedBeforeRestart).containsExactly(1, 2);
      assertThat(seriesResultBeforeRestart).isEmpty();
      assertThat(fetchedSeasonNumbers).containsExactly(2, 3);
      assertThat(metadataOutcome(series.getId())).isEqualTo(new ItemOutcome.Succeeded());
    }

    // A provider restores the interrupt flag and reports the interrupted fetch as a failure.
    private static <T> MetadataFetchOutcome<T> interruptedFetch() {
      Thread.currentThread().interrupt();
      return new MetadataFetchOutcome.Failed<>(new InterruptedException("server stopping"));
    }

    @Test
    @DisplayName("Should stamp a series' metadata result with the refresh's library operation id")
    void shouldStampASeriesMetadataResultWithTheRefreshsLibraryOperationId() throws Exception {
      var library = buildSeriesLibrary();
      var series = saveSeriesWithTmdbId("Old Title", "1399", library);
      when(seriesProviderResolver.getMetadata(any(), eq(library)))
          .thenReturn(new MetadataFetchOutcome.NotFound<>());
      var operation = refreshOf(library);

      refreshService.refreshLibrary(library, operation, neverReached());

      assertThat(itemResults.find(series.getId(), ItemStep.METADATA, null))
          .hasValueSatisfying(
              result -> assertThat(result.libraryOperationId()).contains(operation.id()));
    }

    private List<String> stubEveryMovieFetch(Library library) {
      var fetchedTmdbIds = new CopyOnWriteArrayList<String>();
      when(movieProviderResolver.getMetadata(any(), eq(library)))
          .thenAnswer(
              invocation -> {
                fetchedTmdbIds.add(
                    invocation.getArgument(0, RemoteSearchResult.class).externalId());
                return new MetadataFetchOutcome.NotFound<>();
              });
      return fetchedTmdbIds;
    }

    private void saveSucceededMetadataResult(
        UUID itemId, ImageEntityType itemType, UUID libraryOperationId) {
      itemResults.trySave(
          ItemResult.builder()
              .itemId(itemId)
              .itemType(itemType)
              .step(ItemStep.METADATA)
              .outcome(new ItemOutcome.Succeeded())
              .attemptedAt(clock.instant())
              .libraryOperationId(Optional.of(libraryOperationId))
              .build());
    }
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

  private Season saveSeason(Series series, int seasonNumber) {
    return seasonRepository.save(
        Season.builder()
            .title("Season " + seasonNumber)
            .seasonNumber(seasonNumber)
            .series(series)
            .library(series.getLibrary())
            .build());
  }

  // Lists the seasons for the series and finds each one, appending each fetched season number.
  private List<Integer> stubSeasons(String tmdbId, Library library, Integer... seasonNumbers) {
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, tmdbId))
        .thenReturn(new MetadataFetchOutcome.Found<>(List.of(seasonNumbers)));
    var fetchedSeasonNumbers = new CopyOnWriteArrayList<Integer>();
    for (var seasonNumber : seasonNumbers) {
      when(seriesProviderResolver.getSeasonDetails(library, tmdbId, seasonNumber))
          .thenAnswer(
              _ -> {
                fetchedSeasonNumbers.add(seasonNumber);
                return new MetadataFetchOutcome.Found<>(seasonDetails(seasonNumber));
              });
    }

    return fetchedSeasonNumbers;
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

  private static LibraryOperation refreshOf(Library library) {
    return refreshOf(library, ImageRefreshMode.PRESERVE);
  }

  private static LibraryOperation refreshOf(Library library, ImageRefreshMode imageRefreshMode) {
    return LibraryOperationFixture.refreshBuilder(library.getId())
        .imageRefreshMode(Optional.of(imageRefreshMode))
        .build();
  }
}
