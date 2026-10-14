package com.streamarr.server.services.architecturefixture;

import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.services.GenreService;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/** A service that saves genres itself instead of saving a media title's credits together. */
@RequiredArgsConstructor
public class DirectGenreServiceFixture {

  private final GenreService genreService;

  @SuppressWarnings("java:S1144") // ArchUnit inspects this uninvoked bytecode fixture.
  private Set<Genre> saveGenres(Set<Genre> genres) {
    return genreService.getOrCreateGenres(genres);
  }
}
