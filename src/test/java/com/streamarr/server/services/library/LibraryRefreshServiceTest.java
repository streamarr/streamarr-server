package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.services.CompanyService;
import com.streamarr.server.services.GenreService;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.PersonService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

  @BeforeEach
  void setUp() {
    seriesRepository = new FakeSeriesRepository();
    movieRepository = new FakeMovieRepository();
    seasonRepository = new FakeSeasonRepository();
    episodeRepository = new FakeEpisodeRepository();
    var eventPublisher = new CapturingEventPublisher();
    seriesProviderResolver = mock(SeriesMetadataProviderResolver.class);
    movieProviderResolver = mock(MovieMetadataProviderResolver.class);

    var personService = mock(PersonService.class);
    var genreService = mock(GenreService.class);
    var companyService = mock(CompanyService.class);
    var fileSystem = Jimfs.newFileSystem(Configuration.unix());
    var imageService =
        new ImageService(
            new FakeImageRepository(),
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            fileSystem);

    var seriesService =
        new SeriesService(
            seriesRepository,
            personService,
            genreService,
            companyService,
            null,
            new PaginationService(),
            eventPublisher,
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
            personService,
            genreService,
            companyService,
            null,
            new PaginationService(),
            eventPublisher,
            imageService,
            null,
            null,
            null,
            null,
            null,
            null);

    refreshService =
        new LibraryRefreshService(
            seriesRepository,
            movieRepository,
            seriesService,
            movieService,
            seriesProviderResolver,
            movieProviderResolver);
  }

  @Test
  @DisplayName("Should refresh all series with fresh TMDB metadata when refreshing library")
  void shouldRefreshAllSeriesWithFreshTmdbMetadataWhenRefreshingLibrary() {
    var library = buildSeriesLibrary();
    var series1 = saveSeriesWithTmdbId("Breaking Bad", "1396", library);
    var series2 = saveSeriesWithTmdbId("Better Call Saul", "60059", library);

    stubSeriesMetadata("1396", "Breaking Bad (Updated)", library);
    stubSeriesMetadata("60059", "Better Call Saul (Updated)", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any())).thenReturn(List.of());

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
        .thenReturn(Optional.empty());
    stubSeriesMetadata("1396", "Working Series (Updated)", library);
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any())).thenReturn(List.of());

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
    when(seriesProviderResolver.getAvailableSeasonNumbers(any(), any())).thenReturn(List.of());

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
    when(seriesProviderResolver.getAvailableSeasonNumbers(library, "1396")).thenReturn(List.of(1));

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
        .thenReturn(Optional.of(seasonDetails));

    refreshService.refreshLibrary(library);

    var seasons = seasonRepository.findBySeriesId(series.getId());
    assertThat(seasons).hasSize(1);
    assertThat(seasons.getFirst().getTitle()).isEqualTo("Season 1");

    var episodes = episodeRepository.findBySeasonId(seasons.getFirst().getId());
    assertThat(episodes).hasSize(1);
    assertThat(episodes.getFirst().getTitle()).isEqualTo("Pilot");
  }

  @Test
  @DisplayName("Should refresh all movies with fresh TMDB metadata when refreshing library")
  void shouldRefreshAllMoviesWithFreshTmdbMetadataWhenRefreshingLibrary() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);

    var freshMovie =
        Movie.builder().title("Inception (Updated)").titleSort("inception (updated)").build();
    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(Optional.of(new MetadataResult<>(freshMovie, List.of(), Map.of(), Map.of())));

    refreshService.refreshLibrary(library);

    assertThat(movieRepository.findById(movie.getId()).orElseThrow().getTitle())
        .isEqualTo("Inception (Updated)");
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
        .thenReturn(List.of(1, 2));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1)).thenReturn(Optional.empty());

    var seasonDetails =
        SeasonDetails.builder()
            .name("Season 2")
            .seasonNumber(2)
            .overview("The second season")
            .imageSources(List.of())
            .episodes(List.of())
            .build();
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(Optional.of(seasonDetails));

    refreshService.refreshLibrary(library);

    var seasons = seasonRepository.findBySeriesId(series.getId());
    assertThat(seasons).hasSize(1);
    assertThat(seasons.getFirst().getTitle()).isEqualTo("Season 2");
  }

  @Test
  @DisplayName("Should skip movie when metadata fetch returns empty")
  void shouldSkipMovieWhenMetadataFetchReturnsEmpty() {
    var library = buildMovieLibrary();
    var movie = saveMovieWithTmdbId("Inception", "27205", library);

    when(movieProviderResolver.getMetadata(argThatHasExternalId("27205"), eq(library)))
        .thenReturn(Optional.empty());

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
        .thenReturn(Optional.of(new MetadataResult<>(freshMovie, List.of(), Map.of(), Map.of())));

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
        .thenReturn(List.of(1, 2));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 1))
        .thenThrow(new RuntimeException("API failure"));
    when(seriesProviderResolver.getSeasonDetails(library, "1396", 2))
        .thenReturn(
            Optional.of(
                SeasonDetails.builder()
                    .name("Season 2")
                    .seasonNumber(2)
                    .imageSources(List.of())
                    .episodes(List.of())
                    .build()));

    refreshService.refreshLibrary(library);

    assertThat(seasonRepository.findBySeriesId(series.getId())).isEmpty();
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

  private void stubSeriesMetadata(String tmdbId, String freshTitle, Library library) {
    var freshSeries =
        Series.builder().title(freshTitle).titleSort(freshTitle.toLowerCase()).build();
    when(seriesProviderResolver.getMetadata(argThatHasExternalId(tmdbId), eq(library)))
        .thenReturn(Optional.of(new MetadataResult<>(freshSeries, List.of(), Map.of(), Map.of())));
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
