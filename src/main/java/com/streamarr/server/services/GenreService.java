package com.streamarr.server.services;

import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.repositories.GenreRepository;
import java.util.Objects;
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

    renameBeforeNextInsert(saved, genre.getName());
    return saved;
  }

  // Hibernate would write a rename at its next flush, after this transaction has inserted higher
  // keys, so the row lock would be taken out of insert order. Flushing here takes it in order.
  private void renameBeforeNextInsert(Genre saved, String name) {
    if (Objects.equals(saved.getName(), name)) {
      return;
    }

    saved.setName(name);
    genreRepository.flush();
  }
}
