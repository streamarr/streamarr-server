package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.UUID;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Series Service Tests")
class SeriesServiceTest {

  private FakeSeriesRepository seriesRepository;
  private FakeImageRepository imageRepository;
  private CapturingEventPublisher eventPublisher;
  private SeriesService seriesService;

  @BeforeEach
  void setUp() {
    seriesRepository = new FakeSeriesRepository();
    imageRepository = new FakeImageRepository();
    eventPublisher = new CapturingEventPublisher();
    var cursorUtil = new CursorUtil(new ObjectMapper());
    var relayPaginationService = new RelayPaginationService();
    var fileSystem = Jimfs.newFileSystem(Configuration.unix());
    var imageService =
        new ImageService(
            imageRepository,
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            fileSystem);
    seriesService =
        new SeriesService(
            seriesRepository,
            mock(PersonService.class),
            mock(GenreService.class),
            mock(CompanyService.class),
            cursorUtil,
            relayPaginationService,
            eventPublisher,
            imageService);
  }

  @Test
  @DisplayName("Should publish MetadataEnrichedEvent when creating series with associations")
  void shouldPublishMetadataEnrichedEventWhenCreatingSeriesWithAssociations() {
    var series = Series.builder().title("Breaking Bad").posterPath("/poster.jpg").build();

    seriesService.createSeriesWithAssociations(series);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityId()).isNotNull();
  }

  @Test
  @DisplayName(
      "Should include poster, backdrop, and logo sources in published event when series has all paths")
  void shouldIncludePosterBackdropAndLogoSourcesInPublishedEventWhenSeriesHasAllPaths() {
    var series =
        Series.builder()
            .title("Breaking Bad")
            .posterPath("/poster.jpg")
            .backdropPath("/backdrop.jpg")
            .logoPath("/logo.png")
            .build();

    seriesService.createSeriesWithAssociations(series);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events.getFirst().imageSources())
        .extracting(ImageSource::imageType)
        .containsExactlyInAnyOrder(ImageType.POSTER, ImageType.BACKDROP, ImageType.LOGO);
  }

  @Test
  @DisplayName("Should include only poster source when series backdrop and logo paths are null")
  void shouldIncludeOnlyPosterSourceWhenSeriesBackdropAndLogoPathsAreNull() {
    var series = Series.builder().title("Breaking Bad").posterPath("/poster.jpg").build();

    seriesService.createSeriesWithAssociations(series);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events.getFirst().imageSources())
        .extracting(ImageSource::imageType)
        .containsExactly(ImageType.POSTER);
  }

  @Test
  @DisplayName("Should include only backdrop source when series poster and logo paths are null")
  void shouldIncludeOnlyBackdropSourceWhenSeriesPosterAndLogoPathsAreNull() {
    var series = Series.builder().title("Breaking Bad").backdropPath("/backdrop.jpg").build();

    seriesService.createSeriesWithAssociations(series);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events.getFirst().imageSources())
        .extracting(ImageSource::imageType)
        .containsExactly(ImageType.BACKDROP);
  }

  @Test
  @DisplayName("Should include only logo source when series poster and backdrop paths are null")
  void shouldIncludeOnlyLogoSourceWhenSeriesPosterAndBackdropPathsAreNull() {
    var series = Series.builder().title("Breaking Bad").logoPath("/logo.png").build();

    seriesService.createSeriesWithAssociations(series);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events.getFirst().imageSources())
        .extracting(ImageSource::imageType)
        .containsExactly(ImageType.LOGO);
  }

  @Test
  @DisplayName("Should not publish event when series has no image paths")
  void shouldNotPublishEventWhenSeriesHasNoImagePaths() {
    var series = Series.builder().title("Breaking Bad").build();

    seriesService.createSeriesWithAssociations(series);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).isEmpty();
  }

  @Test
  @DisplayName("Should delete images for entity when deleting series by ID")
  void shouldDeleteImagesForEntityWhenDeletingSeriesById() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    seedImage(series.getId());

    seriesService.deleteSeriesById(series.getId());

    assertThat(imageRepository.findByEntityId(series.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should delete images for each series when deleting by library ID")
  void shouldDeleteImagesForEachSeriesWhenDeletingByLibraryId() {
    var libraryId = UUID.randomUUID();
    var library = Library.builder().id(libraryId).name("TV Shows").build();
    var series1 =
        seriesRepository.save(Series.builder().title("Series 1").library(library).build());
    var series2 =
        seriesRepository.save(Series.builder().title("Series 2").library(library).build());
    seedImage(series1.getId());
    seedImage(series2.getId());

    seriesService.deleteByLibraryId(libraryId);

    assertThat(imageRepository.findByEntityId(series1.getId())).isEmpty();
    assertThat(imageRepository.findByEntityId(series2.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should apply default title ascending sort when given null filter")
  void shouldApplyDefaultTitleAscSortWhenGivenNullFilter() {
    seriesRepository.save(Series.builder().title("Zebra").build());
    seriesRepository.save(Series.builder().title("Apple").build());

    var result = seriesService.getSeriesWithFilter(10, null, 0, null, null);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Apple", "Zebra");
  }

  @Test
  @DisplayName("Should apply provided sort direction when given explicit filter")
  void shouldApplyProvidedSortDirectionWhenGivenExplicitFilter() {
    seriesRepository.save(Series.builder().title("Apple").build());
    seriesRepository.save(Series.builder().title("Zebra").build());

    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.TITLE).sortDirection(SortOrder.DESC).build();

    var result = seriesService.getSeriesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Zebra", "Apple");
  }

  @Test
  @DisplayName("Should paginate forward using cursor when sorted by title")
  void shouldPaginateForwardUsingCursorWhenSortedByTitle() {
    seriesRepository.save(Series.builder().title("Apple").build());
    seriesRepository.save(Series.builder().title("Banana").build());
    seriesRepository.save(Series.builder().title("Cherry").build());

    var firstPage = seriesService.getSeriesWithFilter(1, null, 0, null, null);

    assertThat(firstPage.getEdges()).hasSize(1);
    assertThat(firstPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Apple");
    assertThat(firstPage.getPageInfo().isHasNextPage()).isTrue();

    var cursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = seriesService.getSeriesWithFilter(1, cursor, 0, null, null);

    assertThat(secondPage.getEdges()).hasSize(1);
    assertThat(secondPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Banana");
    assertThat(secondPage.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should paginate backward using cursor when sorted by title")
  void shouldPaginateBackwardUsingCursorWhenSortedByTitle() {
    seriesRepository.save(Series.builder().title("Apple").build());
    seriesRepository.save(Series.builder().title("Banana").build());
    seriesRepository.save(Series.builder().title("Cherry").build());

    var allSeries = seriesService.getSeriesWithFilter(3, null, 0, null, null);
    var endCursor = allSeries.getPageInfo().getEndCursor().getValue();

    var backwardPage = seriesService.getSeriesWithFilter(0, null, 1, endCursor, null);

    assertThat(backwardPage.getEdges()).hasSize(1);
    assertThat(backwardPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Banana");
    assertThat(backwardPage.getPageInfo().isHasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should return empty connection when no series exist")
  void shouldReturnEmptyConnectionWhenNoSeriesExist() {
    var result = seriesService.getSeriesWithFilter(10, null, 0, null, null);

    assertThat(result.getEdges()).isEmpty();
    assertThat(result.getPageInfo().isHasNextPage()).isFalse();
    assertThat(result.getPageInfo().isHasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should reject cursor when sort direction changes between queries")
  void shouldRejectCursorWhenSortDirectionChanges() {
    seriesRepository.save(Series.builder().title("Apple").build());
    seriesRepository.save(Series.builder().title("Banana").build());

    var ascResult = seriesService.getSeriesWithFilter(1, null, 0, null, null);
    var cursor = ascResult.getPageInfo().getEndCursor().getValue();

    var descFilter =
        MediaFilter.builder().sortBy(OrderMediaBy.TITLE).sortDirection(SortOrder.DESC).build();

    assertThatThrownBy(() -> seriesService.getSeriesWithFilter(1, cursor, 0, null, descFilter))
        .isInstanceOf(InvalidCursorException.class);
  }

  private void seedImage(UUID entityId) {
    imageRepository.save(
        Image.builder()
            .entityId(entityId)
            .entityType(ImageEntityType.SERIES)
            .imageType(ImageType.POSTER)
            .variant(ImageSize.SMALL)
            .width(185)
            .height(278)
            .path("series/" + entityId + "/poster/small.jpg")
            .build());
  }
}
