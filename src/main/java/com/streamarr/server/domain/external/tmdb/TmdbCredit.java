package com.streamarr.server.domain.external.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class TmdbCredit {

    private int id;
    private boolean adult;
    private int gender;
    @JsonProperty("known_for_department")
    private String knownForDepartment;
    private String name;
    @JsonProperty("original_name")
    private String originalName;
    private double popularity;
    @JsonProperty("profile_path")
    private String profilePath;
    @JsonProperty("cast_id")
    private int castId;
    private String character;
    @JsonProperty("credit_id")
    private String creditId;
    private int order;
    private String department;
    private String job;
}
