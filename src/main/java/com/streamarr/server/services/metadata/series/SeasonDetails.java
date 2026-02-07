package com.streamarr.server.services.metadata.series;

import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

public record SeasonDetails(
    String name,
    int seasonNumber,
    String overview,
    String posterPath,
    LocalDate airDate,
    List<EpisodeDetails> episodes) {

  @Builder
  public SeasonDetails {}

  public record EpisodeDetails(
      int episodeNumber,
      String name,
      String overview,
      String stillPath,
      LocalDate airDate,
      Integer runtime) {

    @Builder
    public EpisodeDetails {}
  }
}
