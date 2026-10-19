package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.events.library.LibraryItemChangedEvent;
import com.streamarr.server.services.library.LibraryContentsService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.MediaOrderKeys;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

// Schema-mutating fixtures use a separate pool without cached result-column metadata.
@TestPropertySource(
    properties = "spring.datasource.hikari.data-source-properties.prepareThreshold=0")
@Transactional
@Tag("IntegrationTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaOrderEquivalenceIT extends AbstractIntegrationTest {
  private static final Instant WHOLE_MINUTE = Instant.parse("2026-10-19T15:07:00Z");
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private SeriesRepository series;
  @Autowired private SeasonRepository seasons;
  @Autowired private EpisodeRepository episodes;
  @Autowired private WatchHistoryRepository history;
  @Autowired private MovieService movieService;
  @Autowired private SeriesService seriesService;
  @Autowired private LibraryContentsService contents;
  @Autowired private MediaOrderKeys orderKeys;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private AuthTestSupport auth;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  private AuthTestSupport.TestIdentity identity;

  @BeforeAll
  void createIdentity() {
    identity = auth.createIdentity();
  }

  @AfterAll
  void deleteIdentity() {
    auth.deleteIdentity(identity);
  }

  @ParameterizedTest
  @CsvSource({
    "MOVIE,C", "SERIES,C", "MOVIE,en-US-x-icu", "SERIES,en-US-x-icu",
    "MOVIE,tr-TR-x-icu", "SERIES,tr-TR-x-icu", "MOVIE,az-Latn-x-icu", "SERIES,az-Latn-x-icu"
  })
  @DisplayName("Should agree on query order, placement and cursors when values and collations vary")
  void shouldAgreeOnQueryOrderPlacementAndCursorsWhenValuesAndCollationsVary(
      MediaType kind, String collation) {
    jdbc.execute(
        "ALTER TABLE base_collectable ALTER COLUMN title_sort TYPE TEXT COLLATE \""
            + collation
            + "\"");
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder().type(kind).build());
    var items = samples().stream().map(sample -> saveItem(library, sample)).toList();
    entityManager.flush();
    entityManager.clear();
    for (var by : OrderMediaBy.values()) {
      for (var direction : SortDirection.values()) {
        var filter =
            MediaFilter.builder()
                .libraryId(library.getId())
                .profileId(identity.profile().getId())
                .sortBy(by)
                .sortDirection(direction)
                .build();
        assertOrdering(kind, filter);
        assertMembership(items, filter.toBuilder().years(List.of(2020)).build());
      }
    }
  }

  private void assertOrdering(MediaType kind, MediaFilter filter) {
    var page = page(kind, buildForwardOptions(100, filter));
    var expectedIds = page.items().stream().map(item -> item.item().getId()).toList();
    assertThat(expectedIds).hasSize(20);
    var orders =
        page.items().stream()
            .map(item -> MediaOrder.from(filter, item.item().getId(), item.sortValue()))
            .collect(Collectors.toSet());
    var queryKeys = keysById(orders);
    var positions =
        page.items().stream()
            .map(
                item -> {
                  var change = changeFor(item.item());
                  var value = contents.findMatchingItem(change, filter).orElseThrow().sortValue();
                  return MediaOrder.from(filter, change.itemId(), value);
                })
            .collect(Collectors.toSet());
    assertThat(keysById(positions))
        .as("placement keys for %s %s", kind, filter.getSortBy())
        .containsExactlyInAnyOrderEntriesOf(queryKeys);
    assertThat(
            queryKeys.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList())
        .as("SQL order for %s %s %s", kind, filter.getSortBy(), filter.getSortDirection())
        .containsExactlyElementsOf(expectedIds);
    assertTraversal(kind, filter, expectedIds);
  }

  private void assertTraversal(MediaType kind, MediaFilter filter, List<UUID> expected) {
    var actual = new ArrayList<UUID>();
    var options = buildForwardOptions(3, filter);
    for (int offset = 0; offset < expected.size(); offset += 3) {
      var page = page(kind, options);
      assertThat(page.items()).isNotEmpty();
      actual.addAll(page.items().stream().map(item -> item.item().getId()).toList());
      assertThat(page.hasNextPage()).isEqualTo(actual.size() < expected.size());
      options = buildForwardContinuation(3, filter, page.items().getLast());
    }

    assertThat(actual)
        .as("cursor traversal for %s %s", kind, filter.getSortBy())
        .containsExactlyElementsOf(expected);
  }

  private void assertMembership(List<? extends BaseCollectable<?>> items, MediaFilter filter) {
    var kind = items.getFirst().getLibrary().getType();
    var selected =
        page(kind, buildForwardOptions(100, filter)).items().stream()
            .map(item -> item.item().getId())
            .collect(Collectors.toSet());
    assertThat(selected).isNotEmpty().hasSizeLessThan(items.size());
    for (var item : items) {
      assertThat(contents.findMatchingItem(changeFor(item), filter).isPresent())
          .as("membership for %s under %s", item.getId(), filter.getSortBy())
          .isEqualTo(selected.contains(item.getId()));
    }
  }

  private Map<UUID, String> keysById(Set<MediaOrder> orders) {
    return orderKeys.getKeys(orders).entrySet().stream()
        .collect(Collectors.toMap(entry -> entry.getKey().itemId(), Map.Entry::getValue));
  }

  private MediaPage<? extends BaseCollectable<?>> page(
      MediaType kind, MediaPaginationOptions options) {
    return switch (kind) {
      case MOVIE -> movieService.getMoviesWithFilter(options);
      case SERIES -> seriesService.getSeriesWithFilter(options);
      default -> throw new IllegalArgumentException("Expected a Library item");
    };
  }

  private LibraryItemChangedEvent changeFor(BaseCollectable<?> item) {
    return LibraryItemChangedEvent.builder()
        .change(LibraryItemChangedEvent.Change.UPDATED)
        .kind(item.getLibrary().getType())
        .libraryId(item.getLibrary().getId())
        .itemId(item.getId())
        .build();
  }

  private BaseCollectable<?> saveItem(Library library, Sample sample) {
    BaseCollectable<?> item =
        switch (library.getType()) {
          case MOVIE ->
              movies.saveAndFlush(
                  Movie.builder()
                      .library(library)
                      .title(sample.label())
                      .titleSort(sample.title())
                      .runtime(sample.runtime())
                      .releaseDate(sample.date())
                      .build());
          case SERIES ->
              series.saveAndFlush(
                  Series.builder()
                      .library(library)
                      .title(sample.label())
                      .titleSort(sample.title())
                      .runtime(sample.runtime())
                      .firstAirDate(sample.date())
                      .build());
          default -> throw new IllegalArgumentException("Expected a Library item");
        };
    auditColumns.overwriteCreatedOn(
        BASE_COLLECTABLE, BASE_COLLECTABLE.ID.eq(item.getId()), sample.added());
    if (sample.watched() != null) {
      history.saveAndFlush(
          WatchHistory.builder()
              .profileId(identity.profile().getId())
              .collectableId(watchedItemId(item))
              .watchedAt(sample.watched())
              .build());
    }

    return item;
  }

  private UUID watchedItemId(BaseCollectable<?> item) {
    if (item instanceof Movie) {
      return item.getId();
    }

    var season =
        seasons.saveAndFlush(
            Season.builder()
                .series((Series) item)
                .library(item.getLibrary())
                .seasonNumber(1)
                .build());
    return episodes
        .saveAndFlush(
            Episode.builder()
                .library(item.getLibrary())
                .season(season)
                .episodeNumber(1)
                .title("Episode")
                .build())
        .getId();
  }

  private List<Sample> samples() {
    var titles =
        new String[] {
          "Alpha",
          "ALPHA",
          "Alphabeta",
          "INDIGO",
          "Indigo",
          "Zulu",
          "Éclair",
          "éclair",
          "İstanbul",
          "ı",
          "Kelvin",
          "😀",
          "",
          null,
          "A",
          "AA",
          "Ångström",
          "Óscar",
          "123",
          "!Bang"
        };
    var random = new Random(598);
    var samples = new ArrayList<Sample>();
    for (int index = 0; index < titles.length; index++) {
      samples.add(
          Sample.builder()
              .label("Item " + index)
              .title(titles[index])
              .runtime(index % 5 == 0 ? null : random.nextInt(4) * 30)
              .date(index % 4 == 0 ? null : LocalDate.of(2020 + random.nextInt(3), 1, 1))
              .added(
                  WHOLE_MINUTE
                      .plusSeconds(random.nextInt(4) * 60L)
                      .plusNanos(index % 2 == 0 ? 0 : 999000))
              .watched(index % 4 == 0 ? null : WHOLE_MINUTE.plusSeconds(random.nextInt(4) * 60L))
              .build());
    }

    return samples;
  }

  @Builder
  private record Sample(
      String label,
      String title,
      Integer runtime,
      LocalDate date,
      Instant added,
      Instant watched) {}
}
