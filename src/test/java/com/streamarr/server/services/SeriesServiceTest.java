package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private PersonService personService;
  private GenreService genreService;
  private CompanyService companyService;
  private SeriesService seriesService;

  @BeforeEach
  void setUp() {
    seriesRepository = new FakeSeriesRepository();
    seasonRepository = new FakeSeasonRepository();
    episodeRepository = new FakeEpisodeRepository();
    imageRepository = new FakeImageRepository();
    eventPublisher = new CapturingEventPublisher();
    personService = mock(PersonService.class);
    genreService = mock(GenreService.class);
    companyService = mock(CompanyService.class);
    var cursorUtil = new CursorUtil(new ObjectMapper());
    var paginationService = new PaginationService();
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
            personService,
            genreService,
            companyService,
            cursorUtil,
            paginationService,
            eventPublisher,
            imageService,
            seasonRepository,
            episodeRepository,
            null,
            null,
            null,
            null);
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

    var result =
        seriesService.getSeriesAsPage(buildForwardOptions(10, MediaFilter.builder().build()));

    var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

    assertThat(titles).containsExactly("Apple", "Zebra");
  }

  @Test
  @DisplayName("Should apply provided sort direction when given explicit filter")
  void shouldApplyProvidedSortDirectionWhenGivenExplicitFilter() {
    seriesRepository.save(Series.builder().title("Apple").build());
    seriesRepository.save(Series.builder().title("Zebra").build());

    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.TITLE).sortDirection(SortOrder.DESC).build();

    var result = seriesService.getSeriesAsPage(buildForwardOptions(10, filter));

    var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

    assertThat(titles).containsExactly("Zebra", "Apple");
  }

  @Test
  @DisplayName("Should paginate forward using cursor when sorted by title")
  void shouldPaginateForwardUsingCursorWhenSortedByTitle() {
    seriesRepository.save(Series.builder().title("Apple").build());
    seriesRepository.save(Series.builder().title("Banana").build());
    seriesRepository.save(Series.builder().title("Cherry").build());

    var filter = MediaFilter.builder().build();
    var firstPage = seriesService.getSeriesAsPage(buildForwardOptions(1, filter));

    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.items().getFirst().item().getTitle()).isEqualTo("Apple");
    assertThat(firstPage.hasNextPage()).isTrue();

    var lastItem = firstPage.items().getLast();
    var secondPage =
        seriesService.getSeriesAsPage(
            buildCursorOptions(1, PaginationDirection.FORWARD, lastItem, filter));

    assertThat(secondPage.items()).hasSize(1);
    assertThat(secondPage.items().getFirst().item().getTitle()).isEqualTo("Banana");
    assertThat(secondPage.hasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should paginate backward using cursor when sorted by title")
  void shouldPaginateBackwardUsingCursorWhenSortedByTitle() {
    seriesRepository.save(Series.builder().title("Apple").build());
    seriesRepository.save(Series.builder().title("Banana").build());
    seriesRepository.save(Series.builder().title("Cherry").build());

    var filter = MediaFilter.builder().build();
    var allSeries = seriesService.getSeriesAsPage(buildForwardOptions(3, filter));
    var lastItem = allSeries.items().getLast();

    var backwardPage =
        seriesService.getSeriesAsPage(
            buildCursorOptions(1, PaginationDirection.REVERSE, lastItem, filter));

    assertThat(backwardPage.items()).hasSize(1);
    assertThat(backwardPage.items().getFirst().item().getTitle()).isEqualTo("Banana");
    assertThat(backwardPage.hasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should return empty connection when no series exist")
  void shouldReturnEmptyConnectionWhenNoSeriesExist() {
    var result =
        seriesService.getSeriesAsPage(buildForwardOptions(10, MediaFilter.builder().build()));

    assertThat(result.items()).isEmpty();
    assertThat(result.hasNextPage()).isFalse();
    assertThat(result.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName(
      "Should publish season image event targeting saved season when season has image sources")
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
  @DisplayName(
      "Should publish episode image event per saved episode when episodes have image sources")
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
  @DisplayName("Should only publish episode events when episodes have image sources")
  void shouldOnlyPublishEpisodeEventsWhenEpisodesHaveImageSources() {
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

  @Test
  @DisplayName("Should overwrite scalar fields when refreshing series metadata")
  void shouldOverwriteScalarFieldsWhenRefreshingSeriesMetadata() {
    var existing =
        seriesRepository.save(
            Series.builder()
                .title("Old Title")
                .originalTitle("Old Original")
                .titleSort("old title")
                .tagline("Old tagline")
                .summary("Old summary")
                .runtime(30)
                .contentRating(new ContentRating("MPAA", "PG", "US"))
                .firstAirDate(LocalDate.of(2000, 1, 1))
                .build());

    var fresh =
        Series.builder()
            .title("New Title")
            .originalTitle("New Original")
            .titleSort("new title")
            .tagline("New tagline")
            .summary("New summary")
            .runtime(60)
            .contentRating(new ContentRating("TV Parental Guidelines", "TV-MA", "US"))
            .firstAirDate(LocalDate.of(2020, 6, 15))
            .build();
    var metadataResult = new MetadataResult<>(fresh, List.of(), Map.of(), Map.of());

    var result = seriesService.refreshSeriesMetadata(existing, metadataResult);

    assertThat(result.getTitle()).isEqualTo("New Title");
    assertThat(result.getOriginalTitle()).isEqualTo("New Original");
    assertThat(result.getTitleSort()).isEqualTo("new title");
    assertThat(result.getTagline()).isEqualTo("New tagline");
    assertThat(result.getSummary()).isEqualTo("New summary");
    assertThat(result.getRuntime()).isEqualTo(60);
    assertThat(result.getContentRating().value()).isEqualTo("TV-MA");
    assertThat(result.getFirstAirDate()).isEqualTo(LocalDate.of(2020, 6, 15));
    assertThat(result.getId()).isEqualTo(existing.getId());

    var persisted = seriesRepository.findById(result.getId()).orElseThrow();
    assertThat(persisted.getTitle()).isEqualTo("New Title");
  }

  @Test
  @DisplayName("Should overwrite existing fields with null when fresh metadata has null fields")
  void shouldOverwriteExistingFieldsWithNullWhenFreshMetadataHasNullFields() {
    var existing =
        seriesRepository.save(
            Series.builder()
                .title("Breaking Bad")
                .tagline("Old tagline")
                .summary("Old summary")
                .contentRating(new ContentRating("MPAA", "PG", "US"))
                .build());

    var fresh = Series.builder().title("Breaking Bad").titleSort("breaking bad").build();
    var metadataResult = new MetadataResult<>(fresh, List.of(), Map.of(), Map.of());

    var result = seriesService.refreshSeriesMetadata(existing, metadataResult);

    assertThat(result.getTagline()).isNull();
    assertThat(result.getSummary()).isNull();
    assertThat(result.getContentRating()).isNull();
  }

  @Test
  @DisplayName("Should update associations when refreshing series metadata")
  void shouldUpdateAssociationsWhenRefreshingSeriesMetadata() {
    var existing = seriesRepository.save(Series.builder().title("Breaking Bad").build());

    var castInput = List.of(Person.builder().name("Bryan Cranston").build());
    var directorInput = List.of(Person.builder().name("Vince Gilligan").build());
    var freshGenres = Set.of(Genre.builder().name("Drama").build());
    var freshStudios = Set.of(Company.builder().name("Sony Pictures").build());

    when(personService.getOrCreatePersons(
            argThat(
                persons ->
                    persons != null
                        && persons.stream().anyMatch(p -> "Bryan Cranston".equals(p.getName()))),
            any()))
        .thenReturn(castInput);
    when(personService.getOrCreatePersons(
            argThat(
                persons ->
                    persons != null
                        && persons.stream().anyMatch(p -> "Vince Gilligan".equals(p.getName()))),
            any()))
        .thenReturn(directorInput);
    when(genreService.getOrCreateGenres(any())).thenReturn(freshGenres);
    when(companyService.getOrCreateCompanies(any(), any())).thenReturn(freshStudios);

    var fresh =
        Series.builder().title("Breaking Bad").cast(castInput).directors(directorInput).build();
    var metadataResult = new MetadataResult<>(fresh, List.of(), Map.of(), Map.of());

    var result = seriesService.refreshSeriesMetadata(existing, metadataResult);

    assertThat(result.getCast()).extracting(Person::getName).containsExactly("Bryan Cranston");
    assertThat(result.getDirectors()).extracting(Person::getName).containsExactly("Vince Gilligan");
    assertThat(result.getGenres()).extracting(Genre::getName).containsExactly("Drama");
    assertThat(result.getStudios()).extracting(Company::getName).containsExactly("Sony Pictures");
  }

  @Test
  @DisplayName("Should publish image event when refreshing series metadata with image sources")
  void shouldPublishImageEventWhenRefreshingSeriesMetadataWithImageSources() {
    var existing = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var imageSources = List.<ImageSource>of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg"));
    var fresh = Series.builder().title("Breaking Bad").build();
    var metadataResult = new MetadataResult<>(fresh, imageSources, Map.of(), Map.of());

    seriesService.refreshSeriesMetadata(existing, metadataResult);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityId()).isEqualTo(existing.getId());
    assertThat(events.getFirst().entityType()).isEqualTo(ImageEntityType.SERIES);
  }

  @Test
  @DisplayName("Should not publish image event when refreshing series metadata with empty sources")
  void shouldNotPublishImageEventWhenRefreshingSeriesMetadataWithEmptySources() {
    var existing = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var fresh = Series.builder().title("Breaking Bad").build();
    var metadataResult = new MetadataResult<>(fresh, List.of(), Map.of(), Map.of());

    seriesService.refreshSeriesMetadata(existing, metadataResult);

    assertThat(eventPublisher.getEventsOfType(MetadataEnrichedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName("Should update existing season when refreshing with matching season number")
  void shouldUpdateExistingSeasonWhenRefreshingWithMatchingSeasonNumber() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();

    var existingSeason =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Old Season 1")
                .seasonNumber(1)
                .overview("Old overview")
                .series(series)
                .library(library)
                .build());

    var details =
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .overview("Updated overview")
            .airDate(LocalDate.of(2008, 1, 20))
            .imageSources(List.of())
            .episodes(List.of())
            .build();

    var result = seriesService.refreshSeasonWithEpisodes(series, details, library);

    assertThat(result.getId()).isEqualTo(existingSeason.getId());
    assertThat(result.getTitle()).isEqualTo("Season 1");
    assertThat(result.getOverview()).isEqualTo("Updated overview");
    assertThat(result.getAirDate()).isEqualTo(LocalDate.of(2008, 1, 20));
    assertThat(seasonRepository.findBySeriesId(series.getId())).hasSize(1);
  }

  @Test
  @DisplayName("Should create new season when refreshing with unknown season number")
  void shouldCreateNewSeasonWhenRefreshingWithUnknownSeasonNumber() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();

    var details =
        SeasonDetails.builder()
            .name("Season 2")
            .seasonNumber(2)
            .overview("The second season")
            .imageSources(List.of())
            .episodes(List.of())
            .build();

    var result = seriesService.refreshSeasonWithEpisodes(series, details, library);

    assertThat(result.getTitle()).isEqualTo("Season 2");
    assertThat(result.getSeasonNumber()).isEqualTo(2);
    assertThat(result.getSeries().getId()).isEqualTo(series.getId());
  }

  @Test
  @DisplayName("Should update existing episode when refreshing with matching episode number")
  void shouldUpdateExistingEpisodeWhenRefreshingWithMatchingEpisodeNumber() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Season 1")
                .seasonNumber(1)
                .series(series)
                .library(library)
                .build());
    var existingEpisode =
        episodeRepository.save(
            Episode.builder()
                .title("Old Pilot")
                .episodeNumber(1)
                .overview("Old overview")
                .season(season)
                .library(library)
                .build());

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
                        .overview("Updated pilot overview")
                        .airDate(LocalDate.of(2008, 1, 20))
                        .runtime(58)
                        .imageSources(List.of())
                        .build()))
            .build();

    seriesService.refreshSeasonWithEpisodes(series, details, library);

    var episodes = episodeRepository.findBySeasonId(season.getId());
    assertThat(episodes).hasSize(1);
    assertThat(episodes.getFirst().getId()).isEqualTo(existingEpisode.getId());
    assertThat(episodes.getFirst().getTitle()).isEqualTo("Pilot");
    assertThat(episodes.getFirst().getOverview()).isEqualTo("Updated pilot overview");
    assertThat(episodes.getFirst().getRuntime()).isEqualTo(58);
  }

  @Test
  @DisplayName("Should create new episode when refreshing with unknown episode number")
  void shouldCreateNewEpisodeWhenRefreshingWithUnknownEpisodeNumber() {
    var series = seriesRepository.save(Series.builder().title("Breaking Bad").build());
    var library = Library.builder().id(UUID.randomUUID()).name("TV Shows").build();
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Season 1")
                .seasonNumber(1)
                .series(series)
                .library(library)
                .build());
    episodeRepository.save(
        Episode.builder().title("Pilot").episodeNumber(1).season(season).library(library).build());

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
                        .build(),
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(2)
                        .name("Cat's in the Bag...")
                        .imageSources(List.of())
                        .build()))
            .build();

    seriesService.refreshSeasonWithEpisodes(series, details, library);

    var episodes = episodeRepository.findBySeasonId(season.getId());
    assertThat(episodes).hasSize(2);
    assertThat(episodes)
        .extracting(Episode::getTitle)
        .containsExactlyInAnyOrder("Pilot", "Cat's in the Bag...");
  }

  @Test
  @DisplayName("Should publish image events for season and episodes when refreshing")
  void shouldPublishImageEventsForSeasonAndEpisodesWhenRefreshing() {
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
                        .imageSources(
                            List.of(new TmdbImageSource(ImageType.STILL, "/ep1_still.jpg")))
                        .build()))
            .build();

    seriesService.refreshSeasonWithEpisodes(series, details, library);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(2);
    assertThat(events).filteredOn(e -> e.entityType() == ImageEntityType.SEASON).hasSize(1);
    assertThat(events).filteredOn(e -> e.entityType() == ImageEntityType.EPISODE).hasSize(1);
  }

  private static MediaPaginationOptions buildForwardOptions(int limit, MediaFilter filter) {
    return MediaPaginationOptions.builder()
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.empty())
                .paginationDirection(PaginationDirection.FORWARD)
                .limit(limit)
                .build())
        .mediaFilter(filter)
        .build();
  }

  private static MediaPaginationOptions buildCursorOptions(
      int limit, PaginationDirection direction, PageItem<Series> lastItem, MediaFilter filter) {
    return MediaPaginationOptions.builder()
        .cursorId(lastItem.item().getId())
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.of("placeholder"))
                .paginationDirection(direction)
                .limit(limit)
                .build())
        .mediaFilter(filter.toBuilder().previousSortFieldValue(lastItem.sortValue()).build())
        .build();
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
