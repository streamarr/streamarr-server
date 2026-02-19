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
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Tag("IntegrationTest")
@DisplayName("Season and Episode Persistence Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeasonEpisodePersistenceIT extends AbstractIntegrationTest {

  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private LibraryRepository libraryRepository;

  private Library savedLibrary;
  private Series savedSeries;

  @BeforeAll
  void setup() {
    savedLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    savedSeries =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Persistence Test Series")
                .library(savedLibrary)
                .externalIds(
                    Set.of(
                        ExternalIdentifier.builder()
                            .externalSourceType(ExternalSourceType.TMDB)
                            .externalId("persist-" + UUID.randomUUID())
                            .build()))
                .build());
  }

  @Test
  @DisplayName("Should persist season with BaseCollectable fields")
  @Transactional
  void shouldPersistSeasonWithBaseCollectableFields() {
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Season 1")
                .seasonNumber(1)
                .overview("The first season")
                .airDate(LocalDate.of(2023, 1, 15))
                .series(savedSeries)
                .library(savedLibrary)
                .externalIds(
                    Set.of(
                        ExternalIdentifier.builder()
                            .externalSourceType(ExternalSourceType.TMDB)
                            .externalId("season-" + UUID.randomUUID())
                            .build()))
                .build());

    var found = seasonRepository.findById(season.getId());

    assertThat(found).isPresent();
    assertThat(found.get().getTitle()).isEqualTo("Season 1");
    assertThat(found.get().getSeasonNumber()).isEqualTo(1);
    assertThat(found.get().getOverview()).isEqualTo("The first season");
    assertThat(found.get().getAirDate()).isEqualTo(LocalDate.of(2023, 1, 15));
    assertThat(found.get().getLibrary()).isEqualTo(savedLibrary);
    assertThat(found.get().getExternalIds()).hasSize(1);
  }

  @Test
  @DisplayName("Should persist episode with media file via BaseCollectable.addFile()")
  @Transactional
  void shouldPersistEpisodeWithMediaFile() {
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Season for Episode Test")
                .seasonNumber(10)
                .series(savedSeries)
                .library(savedLibrary)
                .build());

    var episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .title("Pilot")
                .episodeNumber(1)
                .overview("The pilot episode")
                .airDate(LocalDate.of(2023, 1, 15))
                .runtime(45)
                .season(season)
                .library(savedLibrary)
                .build());

    var mediaFile =
        MediaFile.builder()
            .filename("show.s10e01.mkv")
            .filepathUri("/tv/Show/Season 10/show.s10e01.mkv")
            .libraryId(savedLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .size(1024L)
            .build();

    episode.addFile(mediaFile);
    var savedEpisode = episodeRepository.saveAndFlush(episode);

    var found = episodeRepository.findById(savedEpisode.getId());

    assertThat(found).isPresent();
    assertThat(found.get().getTitle()).isEqualTo("Pilot");
    assertThat(found.get().getEpisodeNumber()).isEqualTo(1);
    assertThat(found.get().getRuntime()).isEqualTo(45);
    assertThat(found.get().getFiles()).hasSize(1);
  }

  @Test
  @DisplayName("Should enforce unique season per series")
  void shouldEnforceUniqueSeasonPerSeries() {
    seasonRepository.saveAndFlush(
        Season.builder()
            .title("Unique Season")
            .seasonNumber(99)
            .series(savedSeries)
            .library(savedLibrary)
            .build());

    var duplicateSeason =
        Season.builder()
            .title("Duplicate Season")
            .seasonNumber(99)
            .series(savedSeries)
            .library(savedLibrary)
            .build();

    assertThatThrownBy(() -> seasonRepository.saveAndFlush(duplicateSeason))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should enforce unique episode per season")
  void shouldEnforceUniqueEpisodePerSeason() {
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Episode Unique Test Season")
                .seasonNumber(98)
                .series(savedSeries)
                .library(savedLibrary)
                .build());

    episodeRepository.saveAndFlush(
        Episode.builder()
            .title("Episode One")
            .episodeNumber(1)
            .season(season)
            .library(savedLibrary)
            .build());

    var duplicateEpisode =
        Episode.builder()
            .title("Duplicate Episode")
            .episodeNumber(1)
            .season(season)
            .library(savedLibrary)
            .build();

    assertThatThrownBy(() -> episodeRepository.saveAndFlush(duplicateEpisode))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should cascade delete episodes when season deleted")
  void shouldCascadeDeleteEpisodesWhenSeasonDeleted() {
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("Cascade Season")
                .seasonNumber(97)
                .series(savedSeries)
                .library(savedLibrary)
                .build());

    var episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .title("Cascade Episode")
                .episodeNumber(1)
                .season(season)
                .library(savedLibrary)
                .build());

    var episodeId = episode.getId();

    seasonRepository.delete(season);
    seasonRepository.flush();

    assertThat(episodeRepository.findById(episodeId)).isEmpty();
  }

  @Test
  @DisplayName("Should cascade delete seasons when series deleted")
  void shouldCascadeDeleteSeasonsWhenSeriesDeleted() {
    var cascadeLibrary =
        libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());

    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Cascade Series")
                .library(cascadeLibrary)
                .externalIds(
                    Set.of(
                        ExternalIdentifier.builder()
                            .externalSourceType(ExternalSourceType.TMDB)
                            .externalId("cascade-s-" + UUID.randomUUID())
                            .build()))
                .build());

    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title("S1")
                .seasonNumber(1)
                .series(series)
                .library(cascadeLibrary)
                .build());

    var episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .title("E1")
                .episodeNumber(1)
                .season(season)
                .library(cascadeLibrary)
                .build());

    var seasonId = season.getId();
    var episodeId = episode.getId();

    seriesRepository.delete(series);
    seriesRepository.flush();

    assertThat(seasonRepository.findById(seasonId)).isEmpty();
    assertThat(episodeRepository.findById(episodeId)).isEmpty();
  }
}
