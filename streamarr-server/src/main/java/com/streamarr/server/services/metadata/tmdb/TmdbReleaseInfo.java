package com.streamarr.server.services.metadata.tmdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmdbReleaseInfo {

  @JsonProperty("iso_3166_1")
  private String iso31661;

  @JsonProperty("release_dates")
  private List<TmdbRelease> releaseDates;
}
