package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaFileTitle;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Media File Titles Integration Tests")
class MediaFileTitlesIT extends AbstractIntegrationTest {

  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;

  @Test
  @DisplayName(
      "Should name a movie's title and an episode's series, season and episode when files are"
          + " listed")
  void shouldNameAMoviesTitleAndAnEpisodesSeriesSeasonAndEpisodeWhenFilesAreListed() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movieFile = savedMovieFile(library);
    var episodeFile = savedEpisodeFile(library);
    var unmatched =
        mediaFileRepository.saveAndFlush(fileIn(library).status(MediaFileStatus.UNMATCHED).build());

    var titles =
        mediaFileRepository.findTitlesByMediaFileIds(
            List.of(movieFile, episodeFile, unmatched.getId(), UUID.randomUUID()));

    assertThat(titles)
        .containsOnlyKeys(movieFile, episodeFile)
        .containsEntry(
            movieFile,
            MediaFileTitle.builder()
                .title(Optional.of("A Movie"))
                .seriesTitle(Optional.empty())
                .seasonNumber(OptionalInt.empty())
                .episodeNumber(OptionalInt.empty())
                .build())
        .containsEntry(
            episodeFile,
            MediaFileTitle.builder()
                .title(Optional.of("Pilot"))
                .seriesTitle(Optional.of("A Series"))
                .seasonNumber(OptionalInt.of(2))
                .episodeNumber(OptionalInt.of(3))
                .build());
  }

  @Test
  @DisplayName("Should name nothing when no media file is listed")
  void shouldNameNothingWhenNoMediaFileIsListed() {
    assertThat(mediaFileRepository.findTitlesByMediaFileIds(List.of())).isEmpty();
  }

  private UUID savedMovieFile(Library library) {
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("A Movie")
                .titleSort("A Movie")
                .library(library)
                .files(Set.of(fileIn(library).build()))
                .build());
    return movie.getFiles().iterator().next().getId();
  }

  private UUID savedEpisodeFile(Library library) {
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title("A Series").titleSort("A Series").library(library).build());
    var season =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(2).series(series).library(library).build());
    var episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .title("Pilot")
                .episodeNumber(3)
                .season(season)
                .library(library)
                .files(Set.of(fileIn(library).build()))
                .build());
    return episode.getFiles().iterator().next().getId();
  }

  private static MediaFile.MediaFileBuilder<?, ?> fileIn(Library library) {
    return MediaFile.builder()
        .libraryId(library.getId())
        .status(MediaFileStatus.MATCHED)
        .filename(UUID.randomUUID() + ".mkv")
        .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv");
  }
}
