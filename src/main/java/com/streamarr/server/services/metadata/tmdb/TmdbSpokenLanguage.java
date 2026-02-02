package com.streamarr.server.domain.external.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TmdbSpokenLanguage {

    @JsonProperty("iso_639_1")
    private String iso6391;
    private String name;
    @JsonProperty("english_name")
    private String englishName;
}
