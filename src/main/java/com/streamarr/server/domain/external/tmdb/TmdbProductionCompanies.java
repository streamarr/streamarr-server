package com.streamarr.server.domain.external.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class TmdbProductionCompanies {

    private int id;
    private String name;
    @JsonProperty("logo_path")
    private String logoPath;
    @JsonProperty("origin_country")
    private String originCountry;

}
