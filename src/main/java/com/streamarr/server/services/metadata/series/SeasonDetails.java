package com.streamarr.server.services.metadata.series;

import com.streamarr.server.services.metadata.events.ImageSource;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

public record SeasonDetails(
    String name,
    int seasonNumber,
    String overview,
    List<ImageSource> imageSources,
    LocalDate airDate,
    List<EpisodeDetails> episodes) {

  @Builder
  public SeasonDetails {}

  public record EpisodeDetails(
      int episodeNumber,
      String name,
      String overview,
      List<ImageSource> imageSources,
      LocalDate airDate,
      Integer runtime) {

    @Builder
    public EpisodeDetails {}
  }
}
