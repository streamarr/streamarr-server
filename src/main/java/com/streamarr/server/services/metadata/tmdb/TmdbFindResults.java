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
public class TmdbFindResults {

  @JsonProperty("movie_results")
  private List<TmdbSearchResult> movieResults;

  @JsonProperty("tv_results")
  private List<TmdbTvSearchResult> tvResults;
}
