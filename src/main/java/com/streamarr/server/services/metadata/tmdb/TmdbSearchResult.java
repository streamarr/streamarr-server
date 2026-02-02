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
public class TmdbSearchResult {

  @JsonProperty("poster_path")
  private String posterPath;

  private boolean adult;
  private String overview;

  @JsonProperty("release_date")
  private String releaseDate;

  @JsonProperty("genre_ids")
  private List<Integer> genreIds;

  private int id;

  @JsonProperty("original_title")
  private String originalTitle;

  @JsonProperty("original_language")
  private String originalLanguage;

  private String title;

  @JsonProperty("backdrop_path")
  private String backdropPath;

  private double popularity;

  @JsonProperty("vote_count")
  private int voteCount;

  private boolean video;

  @JsonProperty("vote_average")
  private double voteAverage;
}
