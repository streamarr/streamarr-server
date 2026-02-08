package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.Map;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Movie Service Tests")
class MovieServiceTest {

  private FakeMovieRepository movieRepository;
  private CapturingEventPublisher eventPublisher;
  private MovieService movieService;

  @BeforeEach
  void setUp() {
    movieRepository = new FakeMovieRepository();
    eventPublisher = new CapturingEventPublisher();
    var cursorUtil = new CursorUtil(new ObjectMapper());
    var relayPaginationService = new RelayPaginationService();
    movieService =
        new MovieService(
            movieRepository,
            mock(PersonService.class),
            mock(GenreService.class),
            mock(CompanyService.class),
            cursorUtil,
            relayPaginationService,
            eventPublisher,
            mock(ImageService.class));
  }

  @Test
  @DisplayName("Should apply default title ascending sort when given null filter")
  void shouldApplyDefaultTitleAscSortWhenGivenNullFilter() {
    movieRepository.save(Movie.builder().title("Zebra").build());
    movieRepository.save(Movie.builder().title("Apple").build());

    var result = movieService.getMoviesWithFilter(10, null, 0, null, null);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Apple", "Zebra");
  }

  @Test
  @DisplayName("Should apply provided sort direction when given explicit filter")
  void shouldApplyProvidedSortDirectionWhenGivenExplicitFilter() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Zebra").build());

    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.TITLE).sortDirection(SortOrder.DESC).build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Zebra", "Apple");
  }

  @Test
  @DisplayName("Should paginate forward using cursor when sorted by title")
  void shouldPaginateForwardUsingCursorWhenSortedByTitle() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Banana").build());
    movieRepository.save(Movie.builder().title("Cherry").build());

    var firstPage = movieService.getMoviesWithFilter(1, null, 0, null, null);

    assertThat(firstPage.getEdges()).hasSize(1);
    assertThat(firstPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Apple");
    assertThat(firstPage.getPageInfo().isHasNextPage()).isTrue();

    var cursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = movieService.getMoviesWithFilter(1, cursor, 0, null, null);

    assertThat(secondPage.getEdges()).hasSize(1);
    assertThat(secondPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Banana");
    assertThat(secondPage.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should paginate backward using cursor when sorted by title")
  void shouldPaginateBackwardUsingCursorWhenSortedByTitle() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Banana").build());
    movieRepository.save(Movie.builder().title("Cherry").build());

    var allMovies = movieService.getMoviesWithFilter(3, null, 0, null, null);
    var endCursor = allMovies.getPageInfo().getEndCursor().getValue();

    var backwardPage = movieService.getMoviesWithFilter(0, null, 1, endCursor, null);

    assertThat(backwardPage.getEdges()).hasSize(1);
    assertThat(backwardPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Banana");
    assertThat(backwardPage.getPageInfo().isHasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should return empty connection when no movies exist")
  void shouldReturnEmptyConnectionWhenNoMoviesExist() {
    var result = movieService.getMoviesWithFilter(10, null, 0, null, null);

    assertThat(result.getEdges()).isEmpty();
    assertThat(result.getPageInfo().isHasNextPage()).isFalse();
    assertThat(result.getPageInfo().isHasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should reject cursor when sort direction changes between queries")
  void shouldRejectCursorWhenSortDirectionChanges() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Banana").build());

    var ascResult = movieService.getMoviesWithFilter(1, null, 0, null, null);
    var cursor = ascResult.getPageInfo().getEndCursor().getValue();

    var descFilter =
        MediaFilter.builder().sortBy(OrderMediaBy.TITLE).sortDirection(SortOrder.DESC).build();

    assertThatThrownBy(() -> movieService.getMoviesWithFilter(1, cursor, 0, null, descFilter))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should publish MetadataEnrichedEvent when creating movie with associations")
  void shouldPublishMetadataEnrichedEventWhenCreatingMovieWithAssociations() {
    var movie = Movie.builder().title("Inception").build();
    var imageSources = List.<ImageSource>of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg"));
    var metadataResult = new MetadataResult<>(movie, imageSources, Map.of(), Map.of());
    var mediaFile =
        MediaFile.builder()
            .filename("inception.mkv")
            .filepath("/movies/inception.mkv")
            .size(1000L)
            .build();

    movieService.createMovieWithAssociations(metadataResult, mediaFile);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityId()).isNotNull();
  }

  @Test
  @DisplayName(
      "Should include poster and backdrop sources in published event when metadata has both")
  void shouldIncludePosterAndBackdropSourcesInPublishedEventWhenMetadataHasBoth() {
    var movie = Movie.builder().title("Inception").build();
    var imageSources =
        List.<ImageSource>of(
            new TmdbImageSource(ImageType.POSTER, "/poster.jpg"),
            new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg"));
    var metadataResult = new MetadataResult<>(movie, imageSources, Map.of(), Map.of());
    var mediaFile =
        MediaFile.builder()
            .filename("inception.mkv")
            .filepath("/movies/inception.mkv")
            .size(1000L)
            .build();

    movieService.createMovieWithAssociations(metadataResult, mediaFile);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events.getFirst().imageSources()).hasSize(2);
  }

  @Test
  @DisplayName("Should not publish event when image sources list is empty")
  void shouldNotPublishEventWhenImageSourcesListIsEmpty() {
    var movie = Movie.builder().title("Inception").build();
    var metadataResult = new MetadataResult<>(movie, List.of(), Map.of(), Map.of());
    var mediaFile =
        MediaFile.builder()
            .filename("inception.mkv")
            .filepath("/movies/inception.mkv")
            .size(1000L)
            .build();

    movieService.createMovieWithAssociations(metadataResult, mediaFile);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).isEmpty();
  }
}
