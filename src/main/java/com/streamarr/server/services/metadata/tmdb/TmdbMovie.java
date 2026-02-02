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
public class TmdbMovie {

  private int id;
  private boolean adult;

  @JsonProperty("backdrop_path")
  private String backdropPath;

  @JsonProperty("belongs_to_collection")
  private Object belongsToCollection;

  private Long budget;
  private String homepage;

  @JsonProperty("imdb_id")
  private String imdbId;

  @JsonProperty("original_language")
  private String originalLanguage;

  @JsonProperty("original_title")
  private String originalTitle;

  private String title;
  private String overview;
  private Double popularity;

  @JsonProperty("poster_path")
  private String posterPath;

  @JsonProperty("release_date")
  private String releaseDate;

  private Long revenue;
  private Integer runtime;
  private String status;
  private String tagline;
  private boolean video;

  @JsonProperty("vote_average")
  private Double voteAverage;

  @JsonProperty("vote_count")
  private Integer voteCount;

  @JsonProperty("spoken_languages")
  private List<TmdbSpokenLanguage> spokenLanguages;

  @JsonProperty("production_companies")
  private List<TmdbProductionCompany> productionCompanies;

  @JsonProperty("production_countries")
  private List<TmdbProductionCountry> productionCountries;

  private List<TmdbGenre> genres;

  // append_to_response
  private TmdbCredits credits;
  private TmdbReleases releases;
}
