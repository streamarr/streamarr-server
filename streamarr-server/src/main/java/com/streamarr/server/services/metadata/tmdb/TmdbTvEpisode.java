package com.streamarr.server.services.metadata.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmdbTvEpisode {

  private Integer id;
  private String name;
  private String overview;

  @JsonProperty("episode_number")
  private Integer episodeNumber;

  @JsonProperty("season_number")
  private Integer seasonNumber;

  @JsonProperty("air_date")
  private String airDate;

  private Integer runtime;

  @JsonProperty("still_path")
  private String stillPath;
}
