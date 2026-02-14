package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

  private Library savedLibrary;
  private Library savedLibraryB;
  private Library savedLibraryC;

  @BeforeAll
  void setup() {
    savedLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    savedLibraryB = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    savedLibraryC = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    seriesRepository.saveAndFlush(
        Series.builder().title("Alpha Show").library(savedLibraryB).build());
    seriesRepository.saveAndFlush(
        Series.builder().title("Beta Show").library(savedLibraryB).build());
    seriesRepository.saveAndFlush(
        Series.builder().title("Gamma Show").library(savedLibraryB).build());

    seriesRepository.saveAndFlush(
        Series.builder().title("First Show").library(savedLibraryC).build());
    seriesRepository.saveAndFlush(
        Series.builder().title("Second Show").library(savedLibraryC).build());
  }

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

  private MediaFilter filterForLibrary(Library library) {
    return MediaFilter.builder().libraryId(library.getId()).build();
  }

  @Test
  @DisplayName("Should return first page of series with forward pagination")
  void shouldReturnFirstPageOfSeriesForwardPagination() {
    var filter = filterForLibrary(savedLibraryB);
    var result = seriesService.getSeriesWithFilter(2, null, 0, null, filter);

    assertThat(result.getEdges()).hasSize(2);
    assertThat(result.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should return next page using after cursor")
  void shouldReturnNextPageUsingAfterCursor() {
    var filter = filterForLibrary(savedLibraryB);

    var firstPage = seriesService.getSeriesWithFilter(2, null, 0, null, filter);
    assertThat(firstPage.getEdges()).hasSize(2);

    var endCursor = firstPage.getPageInfo().getEndCursor();
    var secondPage = seriesService.getSeriesWithFilter(2, endCursor.getValue(), 0, null, filter);
    assertThat(secondPage.getEdges()).hasSize(1);

    assertThat(firstPage.getEdges())
        .map(e -> e.getNode().getId())
        .isNotEmpty()
        .doesNotContain(secondPage.getEdges().get(0).getNode().getId());
  }

  @Test
  @DisplayName("Should return series filtered by library")
  void shouldReturnSeriesFilteredByLibrary() {
    var filterB = filterForLibrary(savedLibraryB);
    var filterC = filterForLibrary(savedLibraryC);

    var libraryBSeries = seriesService.getSeriesWithFilter(10, null, 0, null, filterB);
    var libraryCSeries = seriesService.getSeriesWithFilter(10, null, 0, null, filterC);

    assertThat(libraryBSeries.getEdges()).hasSize(3);
    assertThat(libraryCSeries.getEdges()).hasSize(2);
  }

  @Test
  @DisplayName("Should return series sorted by title")
  void shouldReturnSeriesSortedByTitle() {
    var filter = filterForLibrary(savedLibraryB);
    var result = seriesService.getSeriesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Alpha Show", "Beta Show", "Gamma Show");
  }

  @Test
  @DisplayName("Should return series sorted by date added descending")
  void shouldReturnSeriesSortedByDateAddedDescending() {
    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.ADDED)
            .sortDirection(SortOrder.DESC)
            .libraryId(savedLibraryC.getId())
            .build();

    var result = seriesService.getSeriesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Second Show", "First Show");
  }

  @Test
  @DisplayName("Should reject cursor when libraryId does not match")
  void shouldRejectCursorWhenLibraryIdMismatch() {
    var filterB = filterForLibrary(savedLibraryB);
    var filterC = filterForLibrary(savedLibraryC);

    var libraryBSeries = seriesService.getSeriesWithFilter(1, null, 0, null, filterB);
    var cursorFromLibraryB = libraryBSeries.getPageInfo().getEndCursor().getValue();

    assertThatThrownBy(
            () -> seriesService.getSeriesWithFilter(1, cursorFromLibraryB, 0, null, filterC))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName(
      "Should paginate all items with no duplicates or skips when title DESC and duplicate titles")
  void shouldPaginateAllItemsWithNoDuplicatesWhenTitleDescAndDuplicateTitles() {
    var duplicateLibrary =
        libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    seriesRepository.saveAndFlush(
        Series.builder().title("Same Show").library(duplicateLibrary).build());
    seriesRepository.saveAndFlush(
        Series.builder().title("Same Show").library(duplicateLibrary).build());
    seriesRepository.saveAndFlush(
        Series.builder().title("Same Show").library(duplicateLibrary).build());

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.TITLE)
            .sortDirection(SortOrder.DESC)
            .libraryId(duplicateLibrary.getId())
            .build();

    var firstPage = seriesService.getSeriesWithFilter(1, null, 0, null, filter);
    assertThat(firstPage.getEdges()).hasSize(1);
    assertThat(firstPage.getPageInfo().isHasNextPage()).isTrue();

    var firstCursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = seriesService.getSeriesWithFilter(1, firstCursor, 0, null, filter);
    assertThat(secondPage.getEdges()).hasSize(1);
    assertThat(secondPage.getPageInfo().isHasNextPage()).isTrue();

    var secondCursor = secondPage.getPageInfo().getEndCursor().getValue();
    var thirdPage = seriesService.getSeriesWithFilter(1, secondCursor, 0, null, filter);
    assertThat(thirdPage.getEdges()).hasSize(1);
    assertThat(thirdPage.getPageInfo().isHasNextPage()).isFalse();

    var allIds =
        List.of(
            firstPage.getEdges().get(0).getNode().getId(),
            secondPage.getEdges().get(0).getNode().getId(),
            thirdPage.getEdges().get(0).getNode().getId());

    assertThat(allIds).doesNotHaveDuplicates();
  }
}
