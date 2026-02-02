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
public class TmdbSearchResults {

  private int page;
  private List<TmdbSearchResult> results;

  @JsonProperty("total_results")
  private int totalResults;

  @JsonProperty("total_pages")
  private int totalPages;
}
