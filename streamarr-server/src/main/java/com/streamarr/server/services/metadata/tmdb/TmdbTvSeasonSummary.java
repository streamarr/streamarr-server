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
public class TmdbTvSeasonSummary {

  private int id;
  private String name;
  private String overview;

  @JsonProperty("season_number")
  private int seasonNumber;

  @JsonProperty("air_date")
  private String airDate;

  @JsonProperty("poster_path")
  private String posterPath;

  @JsonProperty("episode_count")
  private int episodeCount;
}
