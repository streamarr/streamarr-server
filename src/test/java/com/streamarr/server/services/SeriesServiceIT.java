package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.Set;
import java.util.UUID;
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

  @BeforeAll
  void setup() {
    savedLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
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
            .filepath("/tv/Breaking Bad/Season 01/breaking.bad.s01e01.mkv")
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
            .filepath("/tv/Game of Thrones/Season 01/got.s01e01.mkv")
            .libraryId(savedLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .size(2048L)
            .build();

    seriesService.saveSeriesWithMediaFile(series, firstFile);

    var secondFile =
        MediaFile.builder()
            .filename("got.s01e02.mkv")
            .filepath("/tv/Game of Thrones/Season 01/got.s01e02.mkv")
            .libraryId(savedLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .size(2048L)
            .build();

    var result = seriesService.addMediaFileToSeriesByTmdbId("1399", secondFile);

    assertThat(result).isPresent();
    assertThat(result.get().getFiles()).hasSize(2);
  }

  @Test
  @DisplayName("Should return empty when series not found by TMDB ID")
  void shouldReturnEmptyWhenSeriesNotFoundByTmdbId() {
    var mediaFile =
        MediaFile.builder()
            .filename("test.mkv")
            .filepath("/tv/test.mkv")
            .libraryId(savedLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .size(512L)
            .build();

    var result = seriesService.addMediaFileToSeriesByTmdbId("99999", mediaFile);

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
