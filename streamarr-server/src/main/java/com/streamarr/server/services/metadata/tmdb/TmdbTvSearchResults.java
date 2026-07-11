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
public class TmdbTvSearchResults {

  private int page;
  private List<TmdbTvSearchResult> results;

  @JsonProperty("total_results")
  private int totalResults;

  @JsonProperty("total_pages")
  private int totalPages;
}
