package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildBackwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Series Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeriesServiceIT extends AbstractIntegrationTest {

  @Autowired private SeriesService seriesService;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private GenreRepository genreRepository;
  @Autowired private PersonService personService;

  private Library savedLibrary;
  private Library savedLibraryB;
  private Library savedLibraryC;
  private Library savedLibraryD;

  @BeforeAll
  void setup() {
    savedLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    savedLibraryB = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    savedLibraryC = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Alpha Show")
            .titleSort("Alpha Show")
            .library(savedLibraryB)
            .build());
    seriesRepository.saveAndFlush(
        Series.builder().title("Beta Show").titleSort("Beta Show").library(savedLibraryB).build());
    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Gamma Show")
            .titleSort("Gamma Show")
            .library(savedLibraryB)
            .build());

    seriesRepository.saveAndFlush(
        Series.builder()
            .title("First Show")
            .titleSort("First Show")
            .library(savedLibraryC)
            .build());
    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Second Show")
            .titleSort("Second Show")
            .library(savedLibraryC)
            .build());

    savedLibraryD = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Alpha Show")
            .titleSort("Alpha Show")
            .library(savedLibraryD)
            .build());
    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Avengers Show")
            .titleSort("Avengers Show")
            .library(savedLibraryD)
            .build());
    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Batman Show")
            .titleSort("Batman Show")
            .library(savedLibraryD)
            .build());
    seriesRepository.saveAndFlush(
        Series.builder().title("Beta Show").titleSort("Beta Show").library(savedLibraryD).build());
    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Gamma Show")
            .titleSort("Gamma Show")
            .library(savedLibraryD)
            .build());
    seriesRepository.saveAndFlush(
        Series.builder().title("123 Show").titleSort("123 Show").library(savedLibraryD).build());
    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Zorro Show")
            .titleSort("Zorro Show")
            .library(savedLibraryD)
            .build());
  }

  private MediaFilter filterForLibrary(Library library) {
    return MediaFilter.builder().libraryId(library.getId()).build();
  }

  @Nested
  @DisplayName("Series Persistence")
  class SeriesPersistence {

    @Test
    @DisplayName("Should save series with media file")
    void shouldSaveSeriesWithMediaFile() {
      var series =
          Series.builder()
              .title("Breaking Bad")
              .library(savedLibrary)
              .externalIds(
                  Set.of(
                      ExternalIdentifier.builder()
                          .externalSourceType(ExternalSourceType.TMDB)
                          .externalId("1396")
                          .build()))
              .build();

      var mediaFile =
          MediaFile.builder()
              .filename("breaking.bad.s01e01.mkv")
              .filepathUri("/tv/Breaking Bad/Season 01/breaking.bad.s01e01.mkv")
              .libraryId(savedLibrary.getId())
              .status(MediaFileStatus.MATCHED)
              .size(1024L)
              .build();

      var saved = seriesService.saveSeriesWithMediaFile(series, mediaFile);

      assertThat(saved.getId()).isNotNull();
      assertThat(saved.getTitle()).isEqualTo("Breaking Bad");
      assertThat(saved.getFiles()).hasSize(1);
    }

    @Test
    @DisplayName("Should add media file to existing series by TMDB ID")
    void shouldAddMediaFileToExistingSeriesByTmdbId() {
      var series =
          Series.builder()
              .title("Game of Thrones")
              .library(savedLibrary)
              .externalIds(
                  Set.of(
                      ExternalIdentifier.builder()
                          .externalSourceType(ExternalSourceType.TMDB)
                          .externalId("1399")
                          .build()))
              .build();

      var firstFile =
          MediaFile.builder()
              .filename("got.s01e01.mkv")
              .filepathUri("/tv/Game of Thrones/Season 01/got.s01e01.mkv")
              .libraryId(savedLibrary.getId())
              .status(MediaFileStatus.MATCHED)
              .size(2048L)
              .build();

      seriesService.saveSeriesWithMediaFile(series, firstFile);

      var secondFile =
          MediaFile.builder()
              .filename("got.s01e02.mkv")
              .filepathUri("/tv/Game of Thrones/Season 01/got.s01e02.mkv")
              .libraryId(savedLibrary.getId())
              .status(MediaFileStatus.MATCHED)
              .size(2048L)
              .build();

      var found = seriesService.findByTmdbId("1399");

      assertThat(found).isPresent();

      var updated = seriesService.addMediaFile(found.get().getId(), secondFile);

      assertThat(updated.getFiles()).hasSize(2);
    }

    @Test
    @DisplayName("Should return empty when series not found by TMDB ID")
    void shouldReturnEmptyWhenSeriesNotFoundByTmdbId() {
      var result = seriesService.findByTmdbId("99999");

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should find series by TMDB ID when series exists")
    void shouldFindSeriesByTmdbIdWhenSeriesExists() {
      var tmdbId = "find-tmdb-" + UUID.randomUUID();
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Findable Series")
              .library(savedLibrary)
              .externalIds(
                  Set.of(
                      ExternalIdentifier.builder()
                          .externalSourceType(ExternalSourceType.TMDB)
                          .externalId(tmdbId)
                          .build()))
              .build());

      var result = seriesService.findByTmdbId(tmdbId);

      assertThat(result).isPresent();
      assertThat(result.get().getTitle()).isEqualTo("Findable Series");
    }

    @Test
    @DisplayName("Should return empty when no series with TMDB ID")
    void shouldReturnEmptyWhenNoSeriesWithTmdbId() {
      var result = seriesService.findByTmdbId("nonexistent-" + UUID.randomUUID());

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should save series without media file")
    void shouldSaveSeriesWithoutMediaFile() {
      var series =
          Series.builder()
              .title("Saved Without File")
              .library(savedLibrary)
              .externalIds(
                  Set.of(
                      ExternalIdentifier.builder()
                          .externalSourceType(ExternalSourceType.TMDB)
                          .externalId("save-" + UUID.randomUUID())
                          .build()))
              .build();

      var saved = seriesService.saveSeries(series);

      assertThat(saved.getId()).isNotNull();
      assertThat(saved.getTitle()).isEqualTo("Saved Without File");
      assertThat(seriesRepository.findById(saved.getId())).isPresent();
    }

    @Test
    @DisplayName("Should delete series by library ID")
    void shouldDeleteSeriesByLibraryId() {
      var deleteLibrary =
          libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      var series =
          Series.builder()
              .title("To Delete")
              .library(deleteLibrary)
              .externalIds(
                  Set.of(
                      ExternalIdentifier.builder()
                          .externalSourceType(ExternalSourceType.TMDB)
                          .externalId("delete-" + UUID.randomUUID())
                          .build()))
              .build();

      seriesRepository.saveAndFlush(series);

      assertThat(seriesRepository.findByLibrary_Id(deleteLibrary.getId())).isNotEmpty();

      seriesService.deleteByLibraryId(deleteLibrary.getId());

      assertThat(seriesRepository.findByLibrary_Id(deleteLibrary.getId())).isEmpty();
    }

    @Test
    @DisplayName("Should cascade delete seasons and episodes when series deleted")
    void shouldCascadeDeleteSeasonsAndEpisodesWhenSeriesDeleted() {
      var cascadeLibrary =
          libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      var series =
          seriesRepository.saveAndFlush(
              Series.builder()
                  .title("Cascade Test")
                  .library(cascadeLibrary)
                  .externalIds(
                      Set.of(
                          ExternalIdentifier.builder()
                              .externalSourceType(ExternalSourceType.TMDB)
                              .externalId("cascade-" + UUID.randomUUID())
                              .build()))
                  .build());

      var season =
          seasonRepository.saveAndFlush(
              Season.builder()
                  .title("Season 1")
                  .seasonNumber(1)
                  .series(series)
                  .library(cascadeLibrary)
                  .build());

      var episode =
          episodeRepository.saveAndFlush(
              Episode.builder()
                  .title("Pilot")
                  .episodeNumber(1)
                  .season(season)
                  .library(cascadeLibrary)
                  .build());

      var seasonId = season.getId();
      var episodeId = episode.getId();

      seriesService.deleteSeriesById(series.getId());

      assertThat(seasonRepository.findById(seasonId)).isEmpty();
      assertThat(episodeRepository.findById(episodeId)).isEmpty();
    }
  }

  @Nested
  @DisplayName("Forward Pagination")
  class ForwardPagination {

    @Test
    @DisplayName("Should return first page of series with forward pagination")
    void shouldReturnFirstPageOfSeriesForwardPagination() {
      var filter = filterForLibrary(savedLibraryB);
      var result = seriesService.getSeriesWithFilter(buildForwardOptions(2, filter));

      assertThat(result.items()).hasSize(2);
      assertThat(result.hasNextPage()).isTrue();
    }

    @Test
    @DisplayName("Should return next page using after cursor")
    void shouldReturnNextPageUsingAfterCursor() {
      var filter = filterForLibrary(savedLibraryB);

      var firstPage = seriesService.getSeriesWithFilter(buildForwardOptions(2, filter));
      assertThat(firstPage.items()).hasSize(2);

      var lastItem = firstPage.items().getLast();
      var secondPage =
          seriesService.getSeriesWithFilter(buildForwardContinuation(2, filter, lastItem));
      assertThat(secondPage.items()).hasSize(1);

      assertThat(firstPage.items())
          .map(pi -> pi.item().getId())
          .isNotEmpty()
          .doesNotContain(secondPage.items().getFirst().item().getId());
    }

    @Test
    @DisplayName("Should return series filtered by library")
    void shouldReturnSeriesFilteredByLibrary() {
      var filterB = filterForLibrary(savedLibraryB);
      var filterC = filterForLibrary(savedLibraryC);

      var libraryBSeries = seriesService.getSeriesWithFilter(buildForwardOptions(10, filterB));
      var libraryCSeries = seriesService.getSeriesWithFilter(buildForwardOptions(10, filterC));

      assertThat(libraryBSeries.items()).hasSize(3);
      assertThat(libraryCSeries.items()).hasSize(2);
    }

    @Test
    @DisplayName("Should return series sorted by title")
    void shouldReturnSeriesSortedByTitle() {
      var filter = filterForLibrary(savedLibraryB);
      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactly("Alpha Show", "Beta Show", "Gamma Show");
    }
  }

  @Nested
  @DisplayName("Backward Pagination")
  class BackwardPagination {

    @Test
    @DisplayName("Should paginate backward when given last and before arguments")
    void shouldPaginateBackwardWhenGivenLastAndCursor() {
      var filter = filterForLibrary(savedLibraryB);

      var allSeries = seriesService.getSeriesWithFilter(buildForwardOptions(3, filter));
      assertThat(allSeries.items()).hasSize(3);

      var lastItem = allSeries.items().getLast();
      var backwardPage =
          seriesService.getSeriesWithFilter(buildBackwardContinuation(1, filter, lastItem));

      assertThat(backwardPage.items()).hasSize(1);
      assertThat(backwardPage.items().getFirst().item().getTitle()).isEqualTo("Beta Show");
    }

    @Test
    @DisplayName("Should maintain canonical order when paginating backward")
    void shouldMaintainCanonicalOrderWhenPaginatingBackward() {
      var filter = filterForLibrary(savedLibraryB);

      var forwardAll = seriesService.getSeriesWithFilter(buildForwardOptions(3, filter));
      var forwardTitles = forwardAll.items().stream().map(pi -> pi.item().getTitle()).toList();

      var lastItem = forwardAll.items().getLast();
      var backwardPage =
          seriesService.getSeriesWithFilter(buildBackwardContinuation(2, filter, lastItem));
      var backwardTitles = backwardPage.items().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(backwardTitles).containsExactlyElementsOf(forwardTitles.subList(0, 2));
    }

    @Test
    @DisplayName(
        "Should return empty items with hasNextPage true when paginating backward from first item")
    void shouldReturnEmptyItemsWithHasNextPageTrueWhenPaginatingBackwardFromFirstItem() {
      var filter = filterForLibrary(savedLibraryB);

      var firstPage = seriesService.getSeriesWithFilter(buildForwardOptions(3, filter));
      var firstItem = firstPage.items().getFirst();

      var backwardFromFirst =
          seriesService.getSeriesWithFilter(buildBackwardContinuation(1, filter, firstItem));

      assertThat(backwardFromFirst.items()).isEmpty();
      assertThat(backwardFromFirst.hasNextPage()).isTrue();
    }
  }

  @Nested
  @DisplayName("Sort Orders")
  class SortOrders {

    @Test
    @DisplayName("Should return series sorted by date added descending")
    void shouldReturnSeriesSortedByDateAddedDescending() {
      var filter =
          MediaFilter.builder()
              .sortBy(OrderMediaBy.ADDED)
              .sortDirection(SortOrder.DESC)
              .libraryId(savedLibraryC.getId())
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactly("Second Show", "First Show");
    }

    @Test
    @DisplayName("Should paginate forward with added sort order")
    void shouldPaginateForwardWithAddedSortOrder() {
      var filter =
          MediaFilter.builder()
              .sortBy(OrderMediaBy.ADDED)
              .sortDirection(SortOrder.ASC)
              .libraryId(savedLibraryC.getId())
              .build();

      var page1 = seriesService.getSeriesWithFilter(buildForwardOptions(1, filter));

      assertThat(page1.items()).hasSize(1);
      assertThat(page1.items().getFirst().item().getTitle()).isEqualTo("First Show");
      assertThat(page1.hasNextPage()).isTrue();

      var page2 =
          seriesService.getSeriesWithFilter(
              buildForwardContinuation(1, filter, page1.items().getLast()));

      assertThat(page2.items()).hasSize(1);
      assertThat(page2.items().getFirst().item().getTitle()).isEqualTo("Second Show");
      assertThat(page2.hasNextPage()).isFalse();

      var allTitles =
          Stream.concat(
                  page1.items().stream().map(pi -> pi.item().getTitle()),
                  page2.items().stream().map(pi -> pi.item().getTitle()))
              .toList();

      assertThat(allTitles).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName(
        "Should paginate all items with no duplicates or skips when title DESC and duplicate titles")
    void shouldPaginateAllItemsWithNoDuplicatesWhenTitleDescAndDuplicateTitles() {
      var duplicateLibrary =
          libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Same Show")
              .titleSort("Same Show")
              .library(duplicateLibrary)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Same Show")
              .titleSort("Same Show")
              .library(duplicateLibrary)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Same Show")
              .titleSort("Same Show")
              .library(duplicateLibrary)
              .build());

      var filter =
          MediaFilter.builder()
              .sortBy(OrderMediaBy.TITLE)
              .sortDirection(SortOrder.DESC)
              .libraryId(duplicateLibrary.getId())
              .build();

      var firstPage = seriesService.getSeriesWithFilter(buildForwardOptions(1, filter));
      assertThat(firstPage.items()).hasSize(1);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem1 = firstPage.items().getLast();
      var secondPage =
          seriesService.getSeriesWithFilter(buildForwardContinuation(1, filter, lastItem1));
      assertThat(secondPage.items()).hasSize(1);
      assertThat(secondPage.hasNextPage()).isTrue();

      var lastItem2 = secondPage.items().getLast();
      var thirdPage =
          seriesService.getSeriesWithFilter(buildForwardContinuation(1, filter, lastItem2));
      assertThat(thirdPage.items()).hasSize(1);
      assertThat(thirdPage.hasNextPage()).isFalse();

      var allIds =
          List.of(
              firstPage.items().getFirst().item().getId(),
              secondPage.items().getFirst().item().getId(),
              thirdPage.items().getFirst().item().getId());

      assertThat(allIds).doesNotHaveDuplicates();
    }
  }

  @Nested
  @DisplayName("Alphabet Letter Filters")
  class AlphabetLetterFilters {

    @Test
    @DisplayName("Should return only alpha series when start letter is A")
    void shouldReturnOnlyAlphaSeriesWhenStartLetterIsA() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.A)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles)
          .containsExactly(
              "Alpha Show",
              "Avengers Show",
              "Batman Show",
              "Beta Show",
              "Gamma Show",
              "Zorro Show");
    }

    @Test
    @DisplayName("Should return series from B onward when start letter is B")
    void shouldReturnSeriesFromBOnwardWhenStartLetterIsB() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("Batman Show", "Beta Show", "Gamma Show", "Zorro Show");
    }

    @Test
    @DisplayName("Should return all series when start letter is hash")
    void shouldReturnAllSeriesWhenStartLetterIsHash() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.HASH)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles)
          .containsExactly(
              "123 Show",
              "Alpha Show",
              "Avengers Show",
              "Batman Show",
              "Beta Show",
              "Gamma Show",
              "Zorro Show");
    }

    @Test
    @DisplayName("Should continue pagination onward when start letter is B")
    void shouldContinuePaginationOnwardWhenStartLetterIsB() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .build();

      var firstPage = seriesService.getSeriesWithFilter(buildForwardOptions(2, filter));
      assertThat(firstPage.items()).hasSize(2);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem = firstPage.items().getLast();
      var secondPage =
          seriesService.getSeriesWithFilter(buildForwardContinuation(2, filter, lastItem));
      assertThat(secondPage.items()).hasSize(2);
      assertThat(secondPage.hasNextPage()).isFalse();

      var allTitles =
          Stream.concat(firstPage.items().stream(), secondPage.items().stream())
              .map(pi -> pi.item().getTitle())
              .toList();
      assertThat(allTitles).containsExactly("Batman Show", "Beta Show", "Gamma Show", "Zorro Show");
    }

    @Test
    @DisplayName("Should return only Z series when start letter is Z")
    void shouldReturnOnlyZSeriesWhenStartLetterIsZ() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.Z)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("Zorro Show");
    }

    @Test
    @DisplayName("Should return series from B backward when start letter is B and sort is DESC")
    void shouldReturnSeriesFromBBackwardWhenStartLetterIsBDesc() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .sortDirection(SortOrder.DESC)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles)
          .containsExactly("Beta Show", "Batman Show", "Avengers Show", "Alpha Show", "123 Show");
    }

    @Test
    @DisplayName("Should return all series when start letter is Z and sort is DESC")
    void shouldReturnAllSeriesWhenStartLetterIsZDesc() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.Z)
              .sortDirection(SortOrder.DESC)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles)
          .containsExactly(
              "Zorro Show",
              "Gamma Show",
              "Beta Show",
              "Batman Show",
              "Avengers Show",
              "Alpha Show",
              "123 Show");
    }

    @Test
    @DisplayName("Should return only hash series when start letter is hash and sort is DESC")
    void shouldReturnOnlyHashSeriesWhenStartLetterIsHashDesc() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.HASH)
              .sortDirection(SortOrder.DESC)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("123 Show");
    }

    @Test
    @DisplayName("Should continue pagination backward when start letter is B and sort is DESC")
    void shouldContinuePaginationOnwardWhenStartLetterIsBDesc() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .sortDirection(SortOrder.DESC)
              .build();

      var firstPage = seriesService.getSeriesWithFilter(buildForwardOptions(3, filter));
      assertThat(firstPage.items()).hasSize(3);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem = firstPage.items().getLast();
      var secondPage =
          seriesService.getSeriesWithFilter(buildForwardContinuation(3, filter, lastItem));
      assertThat(secondPage.items()).hasSize(2);
      assertThat(secondPage.hasNextPage()).isFalse();

      var allTitles =
          Stream.concat(firstPage.items().stream(), secondPage.items().stream())
              .map(pi -> pi.item().getTitle())
              .toList();
      assertThat(allTitles)
          .containsExactly("Beta Show", "Batman Show", "Avengers Show", "Alpha Show", "123 Show");
    }

    @Test
    @DisplayName("Should return only B-letter series when start letter is B with added sort")
    void shouldReturnOnlyBLetterSeriesWhenStartLetterIsBWithAddedSort() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .sortBy(OrderMediaBy.ADDED)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactlyInAnyOrder("Batman Show", "Beta Show");
    }
  }

  @Nested
  @DisplayName("Nullable Sort Fields")
  class NullableSortFields {

    @Test
    @DisplayName("Should place nulls last when sorting by RELEASE_DATE ASC (firstAirDate)")
    void shouldPlaceNullsLastWhenSortingByReleaseDateAsc() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Mid Show")
              .titleSort("mid show")
              .firstAirDate(LocalDate.of(2010, 6, 1))
              .library(library)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Early Show")
              .titleSort("early show")
              .firstAirDate(LocalDate.of(2000, 1, 1))
              .library(library)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Undated Show")
              .titleSort("undated show")
              .library(library)
              .build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RELEASE_DATE)
              .sortDirection(SortOrder.ASC)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Early Show", "Mid Show", "Undated Show");
    }

    @Test
    @DisplayName("Should paginate forward using cursor when sorted by RELEASE_DATE ASC")
    void shouldPaginateForwardUsingCursorWhenSortedByReleaseDateAsc() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      seriesRepository.saveAndFlush(
          Series.builder()
              .title("First Show")
              .titleSort("first show")
              .firstAirDate(LocalDate.of(2000, 1, 1))
              .library(library)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Second Show")
              .titleSort("second show")
              .firstAirDate(LocalDate.of(2010, 6, 1))
              .library(library)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Third Show")
              .titleSort("third show")
              .firstAirDate(LocalDate.of(2020, 12, 25))
              .library(library)
              .build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RELEASE_DATE)
              .sortDirection(SortOrder.ASC)
              .build();

      var page1 = seriesService.getSeriesWithFilter(buildForwardOptions(1, filter));
      assertThat(page1.items())
          .first()
          .extracting(pi -> pi.item().getTitle())
          .isEqualTo("First Show");
      assertThat(page1.hasNextPage()).isTrue();

      var lastItem = page1.items().getLast();
      var page2 = seriesService.getSeriesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(page2.items())
          .first()
          .extracting(pi -> pi.item().getTitle())
          .isEqualTo("Second Show");
    }

    @Test
    @DisplayName(
        "Should paginate through null firstAirDate values using cursor when cursor is on null row")
    void shouldPaginateThroughNullFirstAirDateValuesUsingCursorWhenCursorIsOnNullRow() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Dated Show")
              .titleSort("dated show")
              .firstAirDate(LocalDate.of(2010, 1, 1))
              .library(library)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder().title("Undated A").titleSort("undated a").library(library).build());
      seriesRepository.saveAndFlush(
          Series.builder().title("Undated B").titleSort("undated b").library(library).build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RELEASE_DATE)
              .sortDirection(SortOrder.ASC)
              .build();

      // Page 1: first=2 returns Dated Show + one Undated (nulls last, secondary sort by ID)
      var page1 = seriesService.getSeriesWithFilter(buildForwardOptions(2, filter));
      assertThat(page1.items()).hasSize(2);
      assertThat(page1.items())
          .first()
          .extracting(pi -> pi.item().getTitle())
          .isEqualTo("Dated Show");
      assertThat(page1.hasNextPage()).isTrue();

      var page1SecondTitle = page1.items().get(1).item().getTitle();

      // Page 2: cursor from null-valued row exercises IS NULL branch of buildSeekCondition
      var lastItem = page1.items().getLast();
      var page2 = seriesService.getSeriesWithFilter(buildForwardContinuation(2, filter, lastItem));
      assertThat(page2.items()).hasSize(1);
      assertThat(page2.hasNextPage()).isFalse();

      var page2Title = page2.items().getFirst().item().getTitle();

      // Page 2 must contain whichever undated series was not on page 1
      assertThat(page2Title).isNotEqualTo(page1SecondTitle).startsWith("Undated");

      // All 3 series seen — no duplicates, no skips
      assertThat(List.of("Dated Show", page1SecondTitle, page2Title)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Should place nulls last when sorting by RUNTIME ASC")
    void shouldPlaceNullsLastWhenSortingByRuntimeAsc() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Long Show")
              .titleSort("long show")
              .runtime(60)
              .library(library)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Short Show")
              .titleSort("short show")
              .runtime(30)
              .library(library)
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("No Runtime Show")
              .titleSort("no runtime show")
              .library(library)
              .build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RUNTIME)
              .sortDirection(SortOrder.ASC)
              .build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Short Show", "Long Show", "No Runtime Show");
    }

    @Test
    @DisplayName("Should paginate forward using cursor when sorted by RUNTIME ASC")
    void shouldPaginateForwardUsingCursorWhenSortedByRuntimeAsc() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      seriesRepository.saveAndFlush(
          Series.builder().title("Short").titleSort("short").runtime(30).library(library).build());
      seriesRepository.saveAndFlush(
          Series.builder().title("Long").titleSort("long").runtime(60).library(library).build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RUNTIME)
              .sortDirection(SortOrder.ASC)
              .build();

      var page1 = seriesService.getSeriesWithFilter(buildForwardOptions(1, filter));
      assertThat(page1.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Short");

      var lastItem = page1.items().getLast();
      var page2 = seriesService.getSeriesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(page2.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Long");
    }
  }

  @Nested
  @DisplayName("Filter Dimensions")
  class FilterDimensions {

    @Test
    @DisplayName("Should return only matching series when genre filter applied")
    void shouldReturnOnlyMatchingSeriesWhenGenreFilterApplied() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

      var genre =
          genreRepository.saveAndFlush(
              Genre.builder()
                  .name("Drama IT")
                  .sourceId("drama-series-it-" + library.getId())
                  .build());

      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Drama Series")
              .titleSort("drama series")
              .library(library)
              .genres(Set.of(genre))
              .build());
      seriesRepository.saveAndFlush(
          Series.builder()
              .title("Other Series")
              .titleSort("other series")
              .library(library)
              .build());

      var filter =
          MediaFilter.builder().libraryId(library.getId()).genreIds(List.of(genre.getId())).build();

      var result = seriesService.getSeriesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Drama Series");
    }
  }

  @Nested
  @DisplayName("Metadata Lifecycle")
  class MetadataLifecycle {

    @Test
    @DisplayName("Should update cast when refreshing series metadata with changed cast")
    void shouldUpdateCastWhenRefreshingSeriesMetadataWithChangedCast() {
      var personA =
          personService.getOrCreatePersons(
              List.of(Person.builder().name("Actor A").sourceId("src-a").build()), Map.of());
      var personB =
          personService.getOrCreatePersons(
              List.of(Person.builder().name("Actor B").sourceId("src-b").build()), Map.of());

      var series =
          seriesRepository.saveAndFlush(
              Series.builder()
                  .title("Cast Change Test")
                  .library(savedLibrary)
                  .cast(new ArrayList<>(List.of(personA.getFirst(), personB.getFirst())))
                  .build());

      var personC = Person.builder().name("Actor C").sourceId("src-c").build();

      var freshSeries =
          Series.builder()
              .title("Cast Change Test")
              .cast(List.of(personB.getFirst(), personC))
              .directors(List.of())
              .genres(Set.of())
              .studios(Set.of())
              .build();

      var metadataResult = new MetadataResult<>(freshSeries, List.of(), Map.of(), Map.of());

      var refreshed = seriesService.refreshSeriesMetadata(series, metadataResult);

      assertThat(refreshed.getCast())
          .extracting(Person::getName)
          .containsExactly("Actor B", "Actor C");
    }
  }
}
