package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.repositories.GenreRepository;
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

    return CreditInsertOrder.sortedBySourceId(genres, Genre::getSourceId, "Genre").stream()
        .map(this::findOrCreateGenre)
        .collect(Collectors.toSet());
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
