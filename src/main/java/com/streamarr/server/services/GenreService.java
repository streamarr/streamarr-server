package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.repositories.GenreRepository;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GenreService {

  private final GenreRepository genreRepository;

  @Transactional
  public Set<Genre> getOrCreateGenres(Set<Genre> genres) {
    if (genres == null) {
      return Set.of();
    }

    genres.forEach(GenreService::requireSourceId);

    // Source-id order: every transaction inserts genre keys in the same order (no deadlock cycle).
    return genres.stream()
        .sorted(Comparator.comparing(Genre::getSourceId))
        .map(this::findOrCreateGenre)
        .collect(Collectors.toSet());
  }

  private static void requireSourceId(Genre genre) {
    if (genre.getSourceId() == null) {
      throw new IllegalArgumentException("Genre sourceId must not be null");
    }
  }

  private Genre findOrCreateGenre(Genre genre) {
    genreRepository.insertIfAbsent(genre.getSourceId(), genre.getName());
    var saved =
        genreRepository
            .findBySourceId(genre.getSourceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Genre not found after upsert for sourceId: " + genre.getSourceId()));

    saved.setName(genre.getName());
    return saved;
  }
}
