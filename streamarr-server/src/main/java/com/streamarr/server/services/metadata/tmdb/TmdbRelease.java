package com.streamarr.server.services.metadata.tmdb;

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

  @JsonProperty("iso_639_1")
  private String iso6391;

  private String note;

  @JsonProperty("release_date")
  private String releaseDate;

  private Integer type;
}
