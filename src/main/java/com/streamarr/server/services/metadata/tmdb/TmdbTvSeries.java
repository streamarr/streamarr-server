package com.streamarr.server.services.metadata.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmdbTvSeries {

  private Integer id;
  private String name;

  @JsonProperty("original_name")
  private String originalName;

  private String overview;
  private String tagline;

  @JsonProperty("first_air_date")
  private String firstAirDate;

  @JsonProperty("backdrop_path")
  private String backdropPath;

  @JsonProperty("poster_path")
  private String posterPath;

  private List<TmdbGenre> genres;

  @JsonProperty("production_companies")
  private List<TmdbProductionCompany> productionCompanies;

  private List<TmdbTvSeasonSummary> seasons;

  private TmdbCredits credits;

  @JsonProperty("content_ratings")
  private TmdbContentRatings contentRatings;

  @JsonProperty("external_ids")
  private TmdbExternalIds externalIds;

  @JsonProperty("episode_run_time")
  private List<Integer> episodeRunTime;
}
