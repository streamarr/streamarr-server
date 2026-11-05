package com.streamarr.server.domain.language;

import java.util.List;
import lombok.NonNull;

/**
 * The scopes whose overrides apply to one title, most specific first: an episode, its season and
 * its series; a season and its series; a series; or a movie.
 */
public record TitleLineage(@NonNull List<OverrideScope> scopes) {

  public TitleLineage {
    scopes = List.copyOf(scopes);
  }
}
