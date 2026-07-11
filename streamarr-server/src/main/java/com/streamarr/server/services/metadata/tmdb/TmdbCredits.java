package com.streamarr.server.services.metadata.tmdb;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmdbCredits {

  private Integer id;
  private List<TmdbCredit> cast;
  private List<TmdbCredit> crew;
}
