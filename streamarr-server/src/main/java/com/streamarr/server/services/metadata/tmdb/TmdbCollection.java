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
public class TmdbCollection {

  private Integer id;
  private String name;

  @JsonProperty("poster_path")
  private String posterPath;

  @JsonProperty("backdrop_path")
  private String backdropPath;
}
