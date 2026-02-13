package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import java.util.List;
import java.util.Map;
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
  private FakeSeasonRepository seasonRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeImageRepository imageRepository;
  private CapturingEventPublisher eventPublisher;
  private SeriesService seriesService;

  @BeforeEach
  void setUp() {
    seriesRepository = new FakeSeriesRepository();
    seasonRepository = new FakeSeasonRepository();
    episodeRepository = new FakeEpisodeRepository();
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
            imageService,
            seasonRepository,
            episodeRepository);
  }

  @Test
  @DisplayName("Should publish MetadataEnrichedEvent when creating series")
  void shouldPublishMetadataEnrichedEventWhenCreatingSeries() {
    var series = Series.builder().title("Breaking Bad").build();
    var imageSources = List.<ImageSource>of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg"));
    var metadataResult = new MetadataResult<>(series, imageSources, Map.of(), Map.of());

    seriesService.createSeriesWithAssociations(metadataResult);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityId()).isNotNull();
  }

  @Test
  @DisplayName("Should not publish event when image sources list is empty")
  void shouldNotPublishEventWhenImageSourcesListIsEmpty() {
    var series = Series.builder().title("Breaking Bad").build();
    var metadataResult = new MetadataResult<>(series, List.of(), Map.of(), Map.of());

    seriesService.createSeriesWithAssociations(metadataResult);

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

  @Test
  @DisplayName("Should publish season image event targeting saved season when season has image sources")
  void shouldPublishSeasonImageEventTargetingSavedSeasonWhenSeasonHasImageSources() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();
    var details =
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/season1.jpg")))
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(1)
                        .name("Pilot")
                        .imageSources(List.of())
                        .build()))
            .build();

    var season = seriesService.createSeasonWithEpisodes(series, details, library);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events)
        .filteredOn(e -> e.entityType() == ImageEntityType.SEASON)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.entityId()).isEqualTo(season.getId());
              assertThat(e.imageSources()).singleElement().isInstanceOf(TmdbImageSource.class);
              var source = (TmdbImageSource) e.imageSources().getFirst();
              assertThat(source.imageType()).isEqualTo(ImageType.POSTER);
              assertThat(source.pathFragment()).isEqualTo("/season1.jpg");
            });
  }

  @Test
  @DisplayName("Should publish episode image event per saved episode when episodes have image sources")
  void shouldPublishEpisodeImageEventPerSavedEpisodeWhenEpisodesHaveImageSources() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();
    var details =
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .imageSources(List.of())
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(1)
                        .name("Pilot")
                        .imageSources(
                            List.of(new TmdbImageSource(ImageType.STILL, "/ep1_still.jpg")))
                        .build(),
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(2)
                        .name("Cat's in the Bag...")
                        .imageSources(
                            List.of(new TmdbImageSource(ImageType.STILL, "/ep2_still.jpg")))
                        .build()))
            .build();

    var season = seriesService.createSeasonWithEpisodes(series, details, library);

    var savedEpisodes = episodeRepository.findBySeasonId(season.getId());
    var episodeEvents =
        eventPublisher.getEventsOfType(MetadataEnrichedEvent.class).stream()
            .filter(e -> e.entityType() == ImageEntityType.EPISODE)
            .toList();

    assertThat(episodeEvents).hasSize(2);
    assertThat(episodeEvents)
        .extracting(MetadataEnrichedEvent::entityId)
        .containsExactlyInAnyOrderElementsOf(savedEpisodes.stream().map(Episode::getId).toList());

    for (var event : episodeEvents) {
      assertThat(event.imageSources()).singleElement().isInstanceOf(TmdbImageSource.class);
      var source = (TmdbImageSource) event.imageSources().getFirst();
      assertThat(source.imageType()).isEqualTo(ImageType.STILL);
    }
  }

  @Test
  @DisplayName("Should not publish any image events when all image sources are empty")
  void shouldNotPublishAnyImageEventsWhenAllImageSourcesAreEmpty() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();
    var details =
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .imageSources(List.of())
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(1)
                        .name("Pilot")
                        .imageSources(List.of())
                        .build()))
            .build();

    seriesService.createSeasonWithEpisodes(series, details, library);

    assertThat(eventPublisher.getEventsOfType(MetadataEnrichedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName("Should persist season and episodes with all metadata fields when details provided")
  void shouldPersistSeasonAndEpisodesWithAllMetadataFieldsWhenDetailsProvided() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();
    var details =
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .overview("The first season of Breaking Bad.")
            .airDate(java.time.LocalDate.of(2008, 1, 20))
            .imageSources(List.of())
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(1)
                        .name("Pilot")
                        .overview("A chemistry teacher turns to crime.")
                        .airDate(java.time.LocalDate.of(2008, 1, 20))
                        .runtime(58)
                        .imageSources(List.of())
                        .build(),
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(2)
                        .name("Cat's in the Bag...")
                        .overview("Walt and Jesse deal with the aftermath.")
                        .airDate(java.time.LocalDate.of(2008, 1, 27))
                        .runtime(48)
                        .imageSources(List.of())
                        .build()))
            .build();

    var season = seriesService.createSeasonWithEpisodes(series, details, library);

    assertThat(seasonRepository.findBySeriesIdAndSeasonNumber(series.getId(), 1)).isPresent();
    assertThat(season.getTitle()).isEqualTo("Season 1");
    assertThat(season.getSeasonNumber()).isEqualTo(1);
    assertThat(season.getOverview()).isEqualTo("The first season of Breaking Bad.");
    assertThat(season.getAirDate()).isEqualTo(java.time.LocalDate.of(2008, 1, 20));
    assertThat(season.getSeries().getId()).isEqualTo(series.getId());

    var episodes = episodeRepository.findBySeasonId(season.getId());
    assertThat(episodes).hasSize(2);
    assertThat(episodes).extracting(Episode::getEpisodeNumber).containsExactlyInAnyOrder(1, 2);
    assertThat(episodes)
        .extracting(Episode::getTitle)
        .containsExactlyInAnyOrder("Pilot", "Cat's in the Bag...");
    assertThat(episodes)
        .extracting(Episode::getOverview)
        .containsExactlyInAnyOrder(
            "A chemistry teacher turns to crime.", "Walt and Jesse deal with the aftermath.");
    assertThat(episodes).extracting(Episode::getRuntime).containsExactlyInAnyOrder(58, 48);
  }

  @Test
  @DisplayName("Should save season with no episodes when episode list is empty")
  void shouldSaveSeasonWithNoEpisodesWhenEpisodeListIsEmpty() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();
    var details =
        SeasonDetails.builder()
            .name("Specials")
            .seasonNumber(0)
            .imageSources(List.of(new TmdbImageSource(ImageType.POSTER, "/specials.jpg")))
            .episodes(List.of())
            .build();

    var season = seriesService.createSeasonWithEpisodes(series, details, library);

    assertThat(season.getTitle()).isEqualTo("Specials");
    assertThat(season.getSeasonNumber()).isZero();
    assertThat(episodeRepository.findBySeasonId(season.getId())).isEmpty();

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.entityType()).isEqualTo(ImageEntityType.SEASON);
              assertThat(e.entityId()).isEqualTo(season.getId());
            });
  }

  @Test
  @DisplayName("Should only publish episode events for episodes that have image sources")
  void shouldOnlyPublishEpisodeEventsForEpisodesThatHaveImageSources() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();
    var details =
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .imageSources(List.of())
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(1)
                        .name("Pilot")
                        .imageSources(
                            List.of(new TmdbImageSource(ImageType.STILL, "/ep1_still.jpg")))
                        .build(),
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(2)
                        .name("Cat's in the Bag...")
                        .imageSources(List.of())
                        .build(),
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(3)
                        .name("...And the Bag's in the River")
                        .imageSources(
                            List.of(new TmdbImageSource(ImageType.STILL, "/ep3_still.jpg")))
                        .build()))
            .build();

    var season = seriesService.createSeasonWithEpisodes(series, details, library);

    var savedEpisodes = episodeRepository.findBySeasonId(season.getId());
    assertThat(savedEpisodes).hasSize(3);

    var episodeEvents =
        eventPublisher.getEventsOfType(MetadataEnrichedEvent.class).stream()
            .filter(e -> e.entityType() == ImageEntityType.EPISODE)
            .toList();

    assertThat(episodeEvents).hasSize(2);

    var ep1 =
        savedEpisodes.stream().filter(e -> e.getEpisodeNumber() == 1).findFirst().orElseThrow();
    var ep3 =
        savedEpisodes.stream().filter(e -> e.getEpisodeNumber() == 3).findFirst().orElseThrow();
    assertThat(episodeEvents)
        .extracting(MetadataEnrichedEvent::entityId)
        .containsExactlyInAnyOrder(ep1.getId(), ep3.getId());
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
