package com.streamarr.server.fixtures;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import java.util.Set;
import java.util.UUID;

public final class MediaEntityFixture {

  private MediaEntityFixture() {}

  public static Movie buildMovie() {
    return buildMovie(null);
  }

  public static Movie buildMovie(String title) {
    var movie = Movie.builder().title(title).build();
    movie.setId(UUID.randomUUID());
    return movie;
  }

  public static Series buildSeries() {
    return buildSeries(null);
  }

  public static Series buildSeries(String title) {
    var series = Series.builder().title(title).build();
    series.setId(UUID.randomUUID());
    return series;
  }

  public static Season buildSeason(String title, int seasonNumber) {
    var season = Season.builder().title(title).seasonNumber(seasonNumber).build();
    season.setId(UUID.randomUUID());
    return season;
  }

  public static Episode buildEpisode(String title, int episodeNumber) {
    var episode = Episode.builder().title(title).episodeNumber(episodeNumber).build();
    episode.setId(UUID.randomUUID());
    return episode;
  }

  public static MediaFile buildMatchedMediaFile(UUID mediaId) {
    return MediaFile.builder()
        .mediaId(mediaId)
        .filename("file-" + UUID.randomUUID() + ".mkv")
        .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
        .size(1_000_000)
        .status(MediaFileStatus.MATCHED)
        .build();
  }

  /** An unsaved Movie in the Library with one matched file, saved together through cascade. */
  public static Movie movieWithFile(Library library) {
    return Movie.builder()
        .title("Paged")
        .titleSort("Paged")
        .library(library)
        .files(
            Set.of(
                MediaFile.builder()
                    .libraryId(library.getId())
                    .status(MediaFileStatus.MATCHED)
                    .filename(UUID.randomUUID() + ".mkv")
                    .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
                    .build()))
        .build();
  }
}
