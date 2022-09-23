package com.streamarr.server.domain.external.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
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
