package com.streamarr.server.services;

import com.streamarr.server.domain.media.CreditedMediaTitle;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/** The people, genres and studios credited on one media title; absent credits are empty. */
@Builder
public record MediaTitleCredits(
    List<Person> cast, List<Person> directors, Set<Genre> genres, Set<Company> studios) {

  public MediaTitleCredits {
    cast = Objects.requireNonNullElse(cast, List.of());
    directors = Objects.requireNonNullElse(directors, List.of());
    genres = Objects.requireNonNullElse(genres, Set.of());
    studios = Objects.requireNonNullElse(studios, Set.of());
  }

  public static MediaTitleCredits listedOn(@NonNull CreditedMediaTitle mediaTitle) {
    return builder()
        .cast(mediaTitle.getCast())
        .directors(mediaTitle.getDirectors())
        .genres(mediaTitle.getGenres())
        .studios(mediaTitle.getStudios())
        .build();
  }

  /** Replaces every credit list on {@code mediaTitle} with these credits. */
  public void copyTo(@NonNull CreditedMediaTitle mediaTitle) {
    mediaTitle.setCast(cast);
    mediaTitle.setDirectors(directors);
    mediaTitle.setGenres(genres);
    mediaTitle.setStudios(studios);
  }
}
