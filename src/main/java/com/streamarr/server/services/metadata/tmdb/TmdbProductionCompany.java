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
public class TmdbProductionCompany {

  private int id;
  private String name;

  @JsonProperty("logo_path")
  private String logoPath;

  @JsonProperty("origin_country")
  private String originCountry;
}
