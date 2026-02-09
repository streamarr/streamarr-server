package com.streamarr.server.services.metadata.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmdbCredit {

  private Integer id;
  private Boolean adult;
  private Integer gender;

  @JsonProperty("known_for_department")
  private String knownForDepartment;

  private String name;

  @JsonProperty("original_name")
  private String originalName;

  private Double popularity;

  @JsonProperty("profile_path")
  private String profilePath;

  @JsonProperty("cast_id")
  private Integer castId;

  private String character;

  @JsonProperty("credit_id")
  private String creditId;

  private Integer order;
  private String department;
  private String job;
}
