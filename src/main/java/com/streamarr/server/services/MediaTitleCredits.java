package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.Builder;

/** The people, genres and studios credited on one movie or series; absent credits are empty. */
@Builder
public record MediaTitleCredits(
    List<Person> cast, List<Person> directors, Set<Genre> genres, Set<Company> studios) {

  public MediaTitleCredits {
    cast = Objects.requireNonNullElse(cast, List.of());
    directors = Objects.requireNonNullElse(directors, List.of());
    genres = Objects.requireNonNullElse(genres, Set.of());
    studios = Objects.requireNonNullElse(studios, Set.of());
  }
}
