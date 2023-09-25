package com.streamarr.server.domain.external.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(value = {"descriptors"})
public class TmdbRelease {

    private String certification;
    @JsonProperty("iso_3166_1")
    private String iso31661;
    private boolean primary;
    @JsonProperty("release_date")
    private String releaseDate;
}
