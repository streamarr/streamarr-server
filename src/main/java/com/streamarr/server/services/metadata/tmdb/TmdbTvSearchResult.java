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
public class TmdbTvSearchResult {

  private int id;
  private String name;

  @JsonProperty("original_name")
  private String originalName;

  @JsonProperty("first_air_date")
  private String firstAirDate;

  @JsonProperty("poster_path")
  private String posterPath;

  @JsonProperty("backdrop_path")
  private String backdropPath;

  private String overview;
  private double popularity;

  @JsonProperty("vote_count")
  private int voteCount;

  @JsonProperty("vote_average")
  private double voteAverage;
}
