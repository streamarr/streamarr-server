package com.streamarr.server.domain.external.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

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
